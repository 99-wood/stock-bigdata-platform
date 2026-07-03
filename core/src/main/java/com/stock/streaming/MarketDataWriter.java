package com.stock.streaming;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.common.Config;
import com.stock.common.StockQuote;
import org.apache.spark.api.java.JavaRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisNoScriptException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 市场数据统一写入 —— Redis 实时缓存 + MySQL 存档
 *
 * === 整体思路 ===
 *
 * 数据流:
 *   parsedRDD (StockQuote) → foreachPartition 并行写 Redis → Driver HGETALL → JDBC → MySQL
 *
 * Redis 是实时数据的主存储（增量累加），MySQL 是 Redis 的归档副本（定期采样）。
 *
 * 写入的 Redis Key:
 *   stock:market:summary           Hash  — 全市场汇总（8 字段 + stat_date/stat_time）
 *   stock:quote:ohlcv:{code}       String — 个股 OHLCV 快照 JSON
 *
 * MySQL 表:
 *   ads_market_summary  — 字段与 Redis Hash 对齐，每个 batch 插入一行
 *
 * === 日清机制 ===
 *
 * 每个 batch 开始前，Driver 侧检查 stat_date:
 *   - 同一天   → 正常增量更新
 *   - 跨天     → DEL stock:market:summary → HSET stat_date → 从头累加
 *   - batch 内日期不唯一（跨天 batch）→ DEL summary + 跳过 + WARN
 *
 * "从头累加" 的含义: stock:quote:ohlcv:{code} 旧值为 nil → Lua 走新股分支 → 直接加。
 * 昨天残留的 ohlcv key 会在第一天首个 batch 被 SET 覆盖。
 *
 * === 增量更新（Lua 原子脚本） ===
 *
 * 利用 Redis 单线程特性，把 GET → 比较 sign/volume/amount → HINCRBY(FLOAT) → SET
 * 打包为一个 Lua 脚本（redis_ohlcv_upsert.lua），EVALSHA 执行。
 *
 * 每个 StockQuote 调用一次 EVALSHA，脚本内部:
 *   1. cjson.decode 新 JSON
 *   2. GET 旧 JSON
 *   3. 旧 == nil → 新股: total_stocks+1, 对应涨跌平+1, sum_pct/volume/amount 直接加
 *   4. 旧 != nil → 旧股: sign 变了调计数, volume/amount/sum_pct 做 delta
 *   5. SET 新快照
 *   6. HSET stat_time
 *   7. avg_change_pct = _sum_pct / total_stocks
 *
 * === 并发安全 ===
 *
 * - HINCRBY / HINCRBYFLOAT 是 Redis 原子命令，多 Worker 并发写同一 Hash field 安全
 * - Lua 脚本整体原子执行，单个 key 的读-改-写不会被插入
 * - 不同 stock 的数据完全独立，互不冲突
 * - stat_time 被各 Worker 竞写，以最后一个完成者为准
 *
 * === 性能 ===
 *
 * - 首次连接时 SCRIPT LOAD 获取 SHA，后续用 EVALSHA（只传 40 字节 hash）
 * - Redis 重启后脚本丢失 → NOSCRIPT 异常 → 回退 EVAL 并重新 LOAD
 * - 5000 条行情 ≈ 5000 次 EVALSHA（全部在 Redis 服务端执行），单 Worker 可处理
 *
 * @see Config
 * @see StockQuote
 */
public final class MarketDataWriter {

    private static final Logger LOG = LoggerFactory.getLogger(MarketDataWriter.class);

    /** Redis key 前缀 */
    static final String KEY_OHLCV_PREFIX  = "stock:quote:ohlcv:";
    static final String KEY_MARKET_SUMMARY = "stock:market:summary";

    /** Lua 脚本文本（启动时从 classpath 加载） */
    private static volatile String luaScript;
    /** Lua 脚本 SHA1（SCRIPT LOAD 后获取） */
    private static volatile String luaSha;

    /** JSON 序列化（禁用科学计数法） */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    static {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOG.error("MySQL JDBC 驱动加载失败", e);
        }
    }

    private MarketDataWriter() {
    }

    // ============================================================
    // 公共入口
    // ============================================================

    /**
     * 处理一个 batch：Redis 增量写入 → MySQL 归档
     *
     * @param parsedRDD 已解析 + 过滤 + 计算涨跌幅的 StockQuote RDD
     */
    public static void write(JavaRDD<StockQuote> parsedRDD) {
        if (parsedRDD.isEmpty()) return;

        // ---- 1. 检查是否跨天 batch ----
        long distinctDates = parsedRDD.map(StockQuote::getTradeDate).distinct().count();
        if (distinctDates > 1) {
            try (Jedis jedis = newJedis()) {
                jedis.del(KEY_MARKET_SUMMARY);
                clearOhlcvKeys(jedis);
                LOG.warn("本批次跨天, 已清空 market:summary + OHLCV，跳过本 batch");
            } catch (Exception e) {
                LOG.error("跨天清空 Redis 失败", e);
            }
            return;
        }

        // ---- 2. 日清检查（Driver 侧，一天仅一次） ----
        String today = parsedRDD.first().getTradeDate();
        try (Jedis jedis = newJedis()) {
            String storedDate = jedis.hget(KEY_MARKET_SUMMARY, "stat_date");
            if (!today.equals(storedDate)) {
                jedis.del(KEY_MARKET_SUMMARY);
                clearOhlcvKeys(jedis);
                jedis.hset(KEY_MARKET_SUMMARY, "stat_date", today);
                LOG.info("日清: stat_date {} → {}, 市场汇总 + OHLCV 已重置", storedDate, today);
            }
        } catch (Exception e) {
            LOG.error("日清检查失败", e);
            return; // Redis 不可用，跳过本批次
        }

        // ---- 3. 并行写入 Redis（foreachPartition → Lua EVALSHA） ----
        parsedRDD.foreachPartition(iterator -> {
            if (!iterator.hasNext()) return;

            Jedis jedis = null;
            try {
                jedis = newJedis();
                String sha = ensureSha(jedis);

                int count = 0;

                while (iterator.hasNext()) {
                    StockQuote q = iterator.next();
                    String ohlcvKey = KEY_OHLCV_PREFIX + q.getCode();
                    String json = buildOhlcvJson(q);
                    // stat_time 使用行情数据自带的时间，而非系统时间
                    String statTime = formatStatTime(q.getTradeDate(), q.getTradeTime());

                    try {
                        jedis.evalsha(sha,
                                Arrays.asList(ohlcvKey, KEY_MARKET_SUMMARY),
                                Arrays.asList(json, statTime));
                        count++;
                    } catch (JedisNoScriptException e) {
                        LOG.warn("NOSCRIPT, 重新 LOAD Lua 脚本");
                        luaSha = null;
                        sha = ensureSha(jedis);
                        jedis.evalsha(sha,
                                Arrays.asList(ohlcvKey, KEY_MARKET_SUMMARY),
                                Arrays.asList(json, statTime));
                        count++;
                    }
                }
                LOG.info("分区写入完成, {} 条", count);
            } catch (Exception e) {
                LOG.error("Redis 分区写入失败", e);
            } finally {
                if (jedis != null) {
                    try { jedis.close(); } catch (Exception ignored) { }
                }
            }
        });

        // ---- 4. MySQL 归档（读取 Redis 当日累计，写入 MySQL） ----
        archiveToMysql();
    }

    // ============================================================
    // 初始化
    // ============================================================

    /**
     * 加载 Lua 脚本（从 classpath 读取一次）
     */
    static synchronized void loadLuaScript() {
        if (luaScript != null) return;
        try (InputStream in = MarketDataWriter.class.getClassLoader()
                .getResourceAsStream("redis_ohlcv_upsert.lua")) {
            if (in == null) {
                throw new IllegalStateException("redis_ohlcv_upsert.lua 未找到");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                luaScript = reader.lines().collect(Collectors.joining("\n"));
            }
            LOG.info("Lua 脚本加载成功, {} 字节", luaScript.length());
        } catch (Exception e) {
            throw new RuntimeException("Lua 脚本加载失败", e);
        }
    }

    /**
     * SCRIPT LOAD 获取 SHA（每个 Jedis 连接首次调用时执行）
     */
    private static String ensureSha(Jedis jedis) {
        if (luaSha != null) return luaSha;
        synchronized (MarketDataWriter.class) {
            if (luaSha != null) return luaSha;
            loadLuaScript();
            luaSha = jedis.scriptLoad(luaScript);
            LOG.info("Lua 脚本 SCRIPT LOAD 成功, SHA={}", luaSha);
            return luaSha;
        }
    }

    // ============================================================
    // MySQL 归档
    // ============================================================

    /**
     * 从 Redis 读取当日市场概览，写入 MySQL ads_market_summary
     * 字段与 Redis Hash 完全对齐（股/元，不转换单位）
     */
    private static void archiveToMysql() {
        try (Jedis jedis = newJedis()) {
            Map<String, String> map = jedis.hgetAll(KEY_MARKET_SUMMARY);
            if (map == null || map.isEmpty()) {
                LOG.warn("MySQL 归档跳过: Redis market:summary 为空");
                return;
            }

            String sql = "INSERT INTO ads_market_summary " +
                    "(stat_time, total_stocks, up_count, down_count, flat_count, " +
                    " avg_change_pct, total_volume, total_amount) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            try (Connection conn = DriverManager.getConnection(
                    Config.mysqlUrl(), Config.mysqlUser(), Config.mysqlPassword());
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, map.get("stat_time"));
                ps.setLong(2, Long.parseLong(map.get("total_stocks")));
                ps.setLong(3, Long.parseLong(map.get("up_count")));
                ps.setLong(4, Long.parseLong(map.get("down_count")));
                ps.setLong(5, Long.parseLong(map.get("flat_count")));
                ps.setDouble(6, Double.parseDouble(map.get("avg_change_pct")));
                ps.setDouble(7, Double.parseDouble(map.get("total_volume")));
                ps.setDouble(8, Double.parseDouble(map.get("total_amount")));
                ps.executeUpdate();

                LOG.info("MySQL 归档: total_stocks={}, up={}, down={}, flat={}, avg_change_pct={}",
                        map.get("total_stocks"), map.get("up_count"), map.get("down_count"),
                        map.get("flat_count"), map.get("avg_change_pct"));
            }
        } catch (Exception e) {
            LOG.error("MySQL 归档失败", e);
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 日清时删除所有 OHLCV key */
    static void clearOhlcvKeys(Jedis jedis) {
        Set<String> keys = jedis.keys(KEY_OHLCV_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            jedis.del(keys.toArray(new String[0]));
            LOG.info("日清: 删除 {} 个 OHLCV key", keys.size());
        }
    }

    /** 创建 Jedis 连接 */
    static Jedis newJedis() {
        Jedis jedis = new Jedis(Config.redisHost(), Config.redisPort(), 3000);
        String pwd = Config.redisPassword();
        if (pwd != null && !pwd.isEmpty()) {
            jedis.auth(pwd);
        }
        return jedis;
    }

    /**
     * 将 StockQuote 转成 OHLCV JSON
     */
    static String buildOhlcvJson(StockQuote q) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name",       q.getName());
        map.put("price",      bd2(q.getPrice()));
        map.put("open",       bd2(q.getOpen()));
        map.put("high",       bd2(q.getHigh()));
        map.put("low",        bd2(q.getLow()));
        map.put("volume",     (long) q.getVolume());
        map.put("amount",     bd2(q.getAmount()));
        map.put("change",     bd2(q.getChangeAmt()));
        map.put("change_pct", bd2(q.getChangePct()));
        map.put("trade_date", q.getTradeDate());
        map.put("trade_time", q.getTradeTime());
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败: " + q.getCode(), e);
        }
    }

    /**
     * 将行情数据的 tradeDate + tradeTime 拼成 datetime 字符串
     * data_gen 已经输出标准格式: tradeDate=yyyy-MM-dd, tradeTime=HH:mm:ss
     */
    private static String formatStatTime(String tradeDate, String tradeTime) {
        if (tradeDate == null || tradeTime == null) return "";
        return tradeDate + " " + tradeTime;
    }

    /** 转为 BigDecimal 保留 2 位小数 */
    private static BigDecimal bd2(double v) {
        return BigDecimal.valueOf(v).setScale(2, BigDecimal.ROUND_HALF_UP);
    }
}
