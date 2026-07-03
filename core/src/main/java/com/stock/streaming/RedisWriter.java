package com.stock.streaming;

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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis 实时数据写入 —— 市场概览 + 个股 OHLCV 行情
 *
 * === 整体思路 ===
 *
 * 数据流:
 *   parsedRDD (StockQuote)  →  foreachPartition 并行写 Redis
 *
 * 写入的 Redis Key:
 *   stock:market:summary           Hash  — 全市场汇总（8 字段 + stat_date/stat_time）
 *   stock:quote:ohlcv:{code}       String — 个股 OHLCV 快照 JSON
 *
 * === 日清机制 ===
 *
 * 每个 batch 开始前，Driver 侧检查 stat_date:
 *   - 同一天   → 正常增量更新
 *   - 跨天     → DEL stock:market:summary → HSET stat_date → 从头累加
 *   - batch 内日期不唯一（跨天 batch）→ DEL summary + 跳过 + WARN
 *
 * "从头累加" 的含义: stock:quote:ohlcv:{code} 旧值为 nil → Lua 走新股分支 → 直接加。
 * 昨天残留的 ohlcv key 会在第一天首个 batch 被 SET 覆盖，不删除也无害。
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
 * - stat_time 被各 Worker 竞写，以最后一个完成者为准（毫秒级差异可忽略）
 *
 * === 性能 ===
 *
 * - 首次连接时 SCRIPT LOAD 获取 SHA，后续用 EVALSHA（只传 40 字节 hash）
 * - Redis 重启后脚本丢失 → NOSCRIPT 异常 → 回退 EVAL 并重新 LOAD
 * - 5000 条行情 ≈ 5000 次 EVALSHA（全部在 Redis 服务端执行），单 Worker 可处理
 *
 * === 依赖 ===
 *
 * - config: application.properties 中的 redis.host/port/password
 * - Lua 脚本: classpath 下的 redis_ohlcv_upsert.lua
 *
 * @see Config
 * @see StockQuote
 */
public final class RedisWriter {

    private static final Logger LOG = LoggerFactory.getLogger(RedisWriter.class);

    /** Redis key 前缀 */
    static final String KEY_OHLCV_PREFIX  = "stock:quote:ohlcv:";
    static final String KEY_MARKET_SUMMARY = "stock:market:summary";

    /** Lua 脚本文本（启动时从 classpath 加载） */
    private static volatile String luaScript;
    /** Lua 脚本 SHA1（SCRIPT LOAD 后获取） */
    private static volatile String luaSha;

    /** JSON 序列化（禁用科学计数法） */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    private RedisWriter() {
    }

    // ============================================================
    // 初始化
    // ============================================================

    /**
     * 加载 Lua 脚本（从 classpath 读取一次）
     */
    static synchronized void loadLuaScript() {
        if (luaScript != null) return;
        try (InputStream in = RedisWriter.class.getClassLoader()
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
     * 放在此方法中而非 static 块，避免初始化时没 Redis 连接
     */
    private static String ensureSha(Jedis jedis) {
        if (luaSha != null) return luaSha;
        synchronized (RedisWriter.class) {
            if (luaSha != null) return luaSha;
            loadLuaScript();
            luaSha = jedis.scriptLoad(luaScript);
            LOG.info("Lua 脚本 SCRIPT LOAD 成功, SHA={}", luaSha);
            return luaSha;
        }
    }

    // ============================================================
    // 公共入口
    // ============================================================

    /**
     * 处理一个 batch 的 Redis 写入（在 QuoteStreamingJob.foreachRDD 中调用）
     *
     * @param parsedRDD 已解析 + 过滤 + 计算涨跌幅的 StockQuote RDD
     */
    public static void updateMarketData(JavaRDD<StockQuote> parsedRDD) {
        if (parsedRDD.isEmpty()) return;

        // ---- 1. 检查是否跨天 batch ----
        long distinctDates = parsedRDD.map(StockQuote::getTradeDate).distinct().count();
        if (distinctDates > 1) {
            // 跨天 batch: 清空汇总，跳过本批次，打 WARN
            try (Jedis jedis = newJedis()) {
                jedis.del(KEY_MARKET_SUMMARY);
                LOG.warn("本批次跨天 ({} 个日期)，已清空 market:summary，跳过本 batch", distinctDates);
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
                jedis.hset(KEY_MARKET_SUMMARY, "stat_date", today);
                LOG.info("日清: stat_date {} → {}, 市场汇总已重置", storedDate, today);
            }
        } catch (Exception e) {
            LOG.error("日清检查失败", e);
            return; // Redis 不可用，跳过本批次
        }

        // ---- 3. 并行写入（foreachPartition） ----
        parsedRDD.foreachPartition(iterator -> {
            if (!iterator.hasNext()) return;

            Jedis jedis = null;
            try {
                jedis = newJedis();
                String sha = ensureSha(jedis);

                String statTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new Date());
                int count = 0;

                while (iterator.hasNext()) {
                    StockQuote q = iterator.next();
                    String ohlcvKey = KEY_OHLCV_PREFIX + q.getCode();
                    String json = buildOhlcvJson(q);

                    try {
                        jedis.evalsha(sha,
                                Arrays.asList(ohlcvKey, KEY_MARKET_SUMMARY),
                                Arrays.asList(json, statTime));
                        count++;
                    } catch (JedisNoScriptException e) {
                        // Redis 重启后脚本丢失，重新 LOAD 并重试
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
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 创建 Jedis 连接
     */
    static Jedis newJedis() {
        Jedis jedis = new Jedis(Config.redisHost(), Config.redisPort(), 3000);
        String pwd = Config.redisPassword();
        if (pwd != null && !pwd.isEmpty()) {
            jedis.auth(pwd);
        }
        return jedis;
    }

    /**
     * 将 StockQuote 转成 OHLCV JSON（snake_case，对齐 REDIS_SCHEMA.md v2 扩展字段）
     *
     * JSON 示例:
     * {"price":21.95,"open":22.14,"high":22.30,"low":21.80,"volume":12345678.0,
     *  "amount":271123456.0,"change":-0.19,"change_pct":-0.86,
     *  "trade_date":"20260703","trade_time":"093900"}
     *
     * @param q 行情快照
     * @return JSON 字符串
     */
    static String buildOhlcvJson(StockQuote q) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("price",      round2(q.getPrice()));
        map.put("open",       round2(q.getOpen()));
        map.put("high",       round2(q.getHigh()));
        map.put("low",        round2(q.getLow()));
        map.put("volume",     (long) q.getVolume());                            // 成交量: 整数
        map.put("amount",     BigDecimal.valueOf(q.getAmount()).setScale(2, BigDecimal.ROUND_HALF_UP));
        map.put("change",     round2(q.getChangeAmt()));
        map.put("change_pct", round2(q.getChangePct()));
        map.put("trade_date", q.getTradeDate());
        map.put("trade_time", q.getTradeTime());
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败: " + q.getCode(), e);
        }
    }

    /** 保留 2 位小数，避免 0.03999999999 这种浮点误差 */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
