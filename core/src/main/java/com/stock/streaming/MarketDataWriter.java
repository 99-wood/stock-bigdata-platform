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
    /** 分钟 OHLCV */
    static final String KEY_MINUTE_PREFIX  = "stock:minute:";
    static final String KEY_MINUTE_WINDOWS = "stock:minute:windows";
    static final String KEY_MINUTE_CODES_PREFIX = "stock:minute:codes:";

    /** Lua 脚本文本（启动时从 classpath 加载） */
    private static volatile String luaScript;
    private static volatile String luaSha;
    /** 分钟 Lua */
    private static volatile String minuteLuaScript;
    private static volatile String minuteLuaSha;

    /** JSON 序列化（禁用科学计数法） */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    static {
        try {
            // 集群使用 mysql-connector-java-5.1.47.jar, 驱动类为 com.mysql.jdbc.Driver
            // 若升级到 8.x 需改为 com.mysql.cj.jdbc.Driver
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
                flushAll(jedis);
                jedis.del(KEY_MARKET_SUMMARY);
                clearOhlcvKeys(jedis);
                clearMinuteKeys(jedis);
                LOG.warn("本批次跨天, flush + 清空完成，跳过本 batch");
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
                flushAll(jedis);
                jedis.del(KEY_MARKET_SUMMARY);
                clearOhlcvKeys(jedis);
                clearMinuteKeys(jedis);
                jedis.hmset(KEY_MARKET_SUMMARY, resetSummaryFields(today));
                LOG.info("日清: stat_date {} → {}, flush + 重置完成", storedDate, today);
            }
        } catch (Exception e) {
            LOG.error("日清检查失败", e);
            return; // Redis 不可用，跳过本批次
        }

        // ---- 3. 并行写入 Redis（foreachPartition → Lua EVALSHA） ----
        final boolean usePipeline = Config.redisPipelineEnabled();
        parsedRDD.foreachPartition(iterator -> {
            if (!iterator.hasNext()) return;

            Jedis jedis = null;
            int count = 0;
            try {
                jedis = newJedis();
                String sha = ensureSha(jedis);

                if (usePipeline) {
                    String mSha = ensureMinuteSha(jedis);
                    final int FLUSH_SIZE = 500;
                    redis.clients.jedis.Pipeline pipeline = jedis.pipelined();
                    while (iterator.hasNext()) {
                        StockQuote q = iterator.next();
                        String json = buildOhlcvJson(q);
                        String statTime = formatStatTime(q.getTradeDate(), q.getTradeTime());
                        pipeline.evalsha(sha,
                                Arrays.asList(KEY_OHLCV_PREFIX + q.getCode(), KEY_MARKET_SUMMARY),
                                Arrays.asList(json, statTime));
                        String minuteWindow = toMinuteWindow(q.getTradeDate(), q.getTradeTime());
                        if (minuteWindow != null) {
                            String minuteTime = minuteWindow.substring(11);
                            pipeline.evalsha(mSha,
                                    Arrays.asList(
                                            KEY_MINUTE_PREFIX + q.getCode() + ":" + minuteWindow,
                                            KEY_MINUTE_CODES_PREFIX + minuteWindow,
                                            KEY_MINUTE_WINDOWS),
                                    Arrays.asList(
                                            String.valueOf(q.getPrice()),
                                            String.valueOf((long) q.getVolume()),
                                            String.valueOf(q.getAmount()),
                                            q.getTradeDate(),
                                            q.getTradeTime(),
                                            q.getCode(),
                                            minuteTime));
                        }
                        if (++count % FLUSH_SIZE == 0) {
                            pipeline.sync();
                            pipeline = jedis.pipelined();
                        }
                    }
                    pipeline.sync();
                } else {
                    // 逐条模式: 每条一次网络往返，支持单条 NOSCRIPT 回退
                    while (iterator.hasNext()) {
                        StockQuote q = iterator.next();
                        String ohlcvKey = KEY_OHLCV_PREFIX + q.getCode();
                        String json = buildOhlcvJson(q);
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
                }
                LOG.info("分区写入完成, {} 条, mode={}", count, usePipeline ? "pipeline" : "plain");
            } catch (JedisNoScriptException e) {
                luaSha = null;
                minuteLuaSha = null;
                LOG.error("Pipeline NOSCRIPT, SHA 已重置, 本分区 {} 条需下 batch 重试", count, e);
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

    /** 加载分钟 Lua */
    static synchronized void loadMinuteLuaScript() {
        if (minuteLuaScript != null) return;
        try (InputStream in = MarketDataWriter.class.getClassLoader()
                .getResourceAsStream("redis_minute_upsert.lua")) {
            if (in == null)
                throw new IllegalStateException("redis_minute_upsert.lua 未找到");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                minuteLuaScript = r.lines().collect(Collectors.joining("\n"));
            }
            LOG.info("分钟 Lua 加载成功, {} 字节", minuteLuaScript.length());
        } catch (Exception e) {
            throw new RuntimeException("分钟 Lua 加载失败", e);
        }
    }

    private static String ensureMinuteSha(Jedis jedis) {
        if (minuteLuaSha != null) return minuteLuaSha;
        synchronized (MarketDataWriter.class) {
            if (minuteLuaSha != null) return minuteLuaSha;
            loadMinuteLuaScript();
            minuteLuaSha = jedis.scriptLoad(minuteLuaScript);
            LOG.info("分钟 Lua SCRIPT LOAD 成功, SHA={}", minuteLuaSha);
            return minuteLuaSha;
        }
    }

    /** tradeTime "HH:mm:ss" → 5分钟窗口 "yyyy-MM-dd HH:mm:00" */
    static String toMinuteWindow(String tradeDate, String tradeTime) {
        if (tradeDate == null || tradeTime == null) return null;
        try {
            int h = Integer.parseInt(tradeTime.substring(0, 2));
            int m = Integer.parseInt(tradeTime.substring(3, 5));
            return String.format("%s %02d:%02d:00", tradeDate, h, (m / 5) * 5);
        } catch (Exception e) { return null; }
    }

    // ============================================================
    // Flush: Redis 分钟 + 日线 → MySQL
    // ============================================================

    /** 公开入口：日清 / shutdown 时调用 */
    public static void flushAll(Jedis jedis) {
        flushAllMinute(jedis);
        flushAllDaily(jedis);
    }

    /** 分钟 flush：遍历所有窗口 → 按 code 分组 → LAG 做差 → MySQL */
    private static void flushAllMinute(Jedis jedis) {
        Set<String> windows = jedis.smembers(KEY_MINUTE_WINDOWS);
        if (windows == null || windows.isEmpty()) return;

        List<String> sortedWindows = new ArrayList<>(windows);
        Collections.sort(sortedWindows);

        // 收集所有数据: code → window → Map<field, value>
        Map<String, Map<String, Map<String, String>>> all = new LinkedHashMap<>();
        for (String window : sortedWindows) {
            String codesKey = KEY_MINUTE_CODES_PREFIX + window;
            Set<String> codes = jedis.smembers(codesKey);
            if (codes == null) continue;
            for (String code : codes) {
                Map<String, String> m = jedis.hgetAll(KEY_MINUTE_PREFIX + code + ":" + window);
                if (m == null || m.isEmpty()) continue;
                all.computeIfAbsent(code, k -> new LinkedHashMap<>()).put(window, m);
            }
        }

        int total = 0;
        try (Connection conn = DriverManager.getConnection(
                Config.mysqlUrl(), Config.mysqlUser(), Config.mysqlPassword())) {
            conn.setAutoCommit(false);
            String sql = "REPLACE INTO dws_stock_minute " +
                    "(code, minute_time, open, high, low, close, volume, amount) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {

                for (Map.Entry<String, Map<String, Map<String, String>>> entry : all.entrySet()) {
                    String code = entry.getKey();
                    long prevVol = 0;
                    double prevAmt = 0;

                    for (String window : sortedWindows) {
                        Map<String, String> m = entry.getValue().get(window);
                        if (m == null) continue;

                        long lastVol  = Long.parseLong(m.getOrDefault("last_vol", "0"));
                        double lastAmt = Double.parseDouble(m.getOrDefault("last_amt", "0"));

                        ps.setString(1, code);
                        ps.setString(2, window);
                        ps.setBigDecimal(3, new BigDecimal(m.getOrDefault("open", "0")));
                        ps.setBigDecimal(4, new BigDecimal(m.getOrDefault("high", "0")));
                        ps.setBigDecimal(5, new BigDecimal(m.getOrDefault("low", "0")));
                        ps.setBigDecimal(6, new BigDecimal(m.getOrDefault("close", "0")));
                        ps.setLong(7, Math.max(lastVol - prevVol, 0));
                        ps.setBigDecimal(8, BigDecimal.valueOf(lastAmt - prevAmt));
                        ps.addBatch();

                        prevVol = lastVol;
                        prevAmt = lastAmt;

                        if (++total % 1000 == 0) ps.executeBatch();
                    }
                }
                ps.executeBatch();
                conn.commit();
            } catch (Exception e) { conn.rollback(); throw e; }
        } catch (Exception e) {
            LOG.error("分钟 flush 失败", e);
        }
        LOG.info("分钟 flush 完成: {} 条, {} 窗口", total, sortedWindows.size());
    }

    /** 日线 flush：OHLCV 快照 → MySQL dws_stock_day */
    private static void flushAllDaily(Jedis jedis) {
        Set<String> keys = jedis.keys(KEY_OHLCV_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return;

        int total = 0;
        try (Connection conn = DriverManager.getConnection(
                Config.mysqlUrl(), Config.mysqlUser(), Config.mysqlPassword())) {
            conn.setAutoCommit(false);
            String sql = "REPLACE INTO dws_stock_day " +
                    "(code, trade_date, open, high, low, close, volume, amount, change_pct) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String key : keys) {
                    String code = key.substring(KEY_OHLCV_PREFIX.length());
                    String json = jedis.get(key);
                    if (json == null) continue;
                    try {
                        StockQuote q = MAPPER.readValue(json, StockQuote.class);
                        ps.setString(1, code);
                        ps.setString(2, q.getTradeDate());
                        ps.setBigDecimal(3, bd2(q.getOpen()));
                        ps.setBigDecimal(4, bd2(q.getHigh()));
                        ps.setBigDecimal(5, bd2(q.getLow()));
                        ps.setBigDecimal(6, bd2(q.getPrice()));
                        ps.setLong(7, (long) q.getVolume());
                        ps.setBigDecimal(8, bd2(q.getAmount()));
                        ps.setBigDecimal(9, bd2(q.getChangePct()));
                        ps.addBatch();
                        if (++total % 1000 == 0) ps.executeBatch();
                    } catch (Exception e) {
                        LOG.warn("日线解析失败: {}", key, e);
                    }
                }
                ps.executeBatch();
                conn.commit();
            } catch (Exception e) { conn.rollback(); throw e; }
        } catch (Exception e) {
            LOG.error("日线 flush 失败", e);
        }
        LOG.info("日线 flush 完成: {} 条", total);
    }

    /** 日清时删除所有分钟 key（用 SET 遍历，避免 KEYS 阻塞） */
    static void clearMinuteKeys(Jedis jedis) {
        Set<String> windows = jedis.smembers(KEY_MINUTE_WINDOWS);
        if (windows != null && !windows.isEmpty()) {
            int count = 0;
            for (String window : windows) {
                String codesKey = KEY_MINUTE_CODES_PREFIX + window;
                Set<String> codes = jedis.smembers(codesKey);
                if (codes != null) {
                    for (String code : codes) {
                        jedis.del(KEY_MINUTE_PREFIX + code + ":" + window);
                        count++;
                    }
                }
                jedis.del(codesKey);
            }
            jedis.del(KEY_MINUTE_WINDOWS);
            LOG.info("日清: 删除 {} 个分钟 key", count);
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

            // fix #5: 午休/收盘后 stat_time 不变, INSERT 会违反唯一约束 → REPLACE INTO
            // fix #3: 保持原始单位 股/元, DDL COMMENT 已同步更新
            String sql = "REPLACE INTO ads_market_summary " +
                    "(stat_time, total_stocks, up_count, down_count, flat_count, " +
                    " avg_change_pct, total_volume, total_amount) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            // fix #2: 字段缺失时跳过, 避免 NPE 导致静默失败
            String statTime = map.get("stat_time");
            String totalStocks = map.get("total_stocks");
            String upCount = map.get("up_count");
            String downCount = map.get("down_count");
            String flatCount = map.get("flat_count");
            String avgChangePct = map.get("avg_change_pct");
            String totalVolume = map.get("total_volume");
            String totalAmount = map.get("total_amount");
            if (statTime == null || totalStocks == null || upCount == null
                    || downCount == null || flatCount == null
                    || avgChangePct == null || totalVolume == null || totalAmount == null) {
                LOG.warn("MySQL 归档跳过: Redis Hash 字段缺失");
                return;
            }

            try (Connection conn = DriverManager.getConnection(
                    Config.mysqlUrl(), Config.mysqlUser(), Config.mysqlPassword());
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, statTime);
                ps.setLong(2, Long.parseLong(totalStocks));
                ps.setLong(3, Long.parseLong(upCount));
                ps.setLong(4, Long.parseLong(downCount));
                ps.setLong(5, Long.parseLong(flatCount));
                ps.setDouble(6, Double.parseDouble(avgChangePct));
                // fix #8: total_volume 是 BIGINT, 用 parseLong + setLong
                ps.setLong(7, Long.parseLong(totalVolume));
                // fix #3: total_amount 存原始 元, 精度高于 万元
                ps.setDouble(8, Double.parseDouble(totalAmount));
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

    /** 日清时预置 summary Hash 全部字段为 0, stat_date 为当日 */
    private static Map<String, String> resetSummaryFields(String statDate) {
        Map<String, String> map = new HashMap<>();
        map.put("stat_date",     statDate);
        map.put("stat_time",     "");
        map.put("total_stocks",  "0");
        map.put("up_count",      "0");
        map.put("down_count",    "0");
        map.put("flat_count",    "0");
        map.put("_sum_pct",      "0");
        map.put("total_volume",  "0");
        map.put("total_amount",  "0");
        map.put("avg_change_pct","0");
        return map;
    }

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
     * fix #4: null 时用系统时间兜底, 避免 "" 写入 MySQL stat_time NOT NULL 导致归档失败
     */
    private static String formatStatTime(String tradeDate, String tradeTime) {
        if (tradeDate == null || tradeTime == null) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        }
        return tradeDate + " " + tradeTime;
    }

    /** 转为 BigDecimal 保留 2 位小数 */
    private static BigDecimal bd2(double v) {
        return BigDecimal.valueOf(v).setScale(2, BigDecimal.ROUND_HALF_UP);
    }
}
