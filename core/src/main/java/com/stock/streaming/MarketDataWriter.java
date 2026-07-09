package com.stock.streaming;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.batch.MarketFlusher;
import com.stock.common.*;
import org.apache.spark.api.java.JavaRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisNoScriptException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;

/**
 * 市场数据实时写入 —— Redis（Lua EVALSHA）+ MySQL 市场概览归档
 *
 * <pre>
 * 数据流:
 *   parsedRDD → foreachPartition 并行写 Redis (OHLCV + 分钟) → Driver 写 MySQL 市场概览
 *
 * Redis Key:
 *   stock:quote:ohlcv:{code}   String — 个股 OHLCV 快照 JSON
 *   stock:market:summary       Hash   — 全市场汇总
 *   stock:minute:{code}:{window} Hash — 分钟 OHLCV
 *
 * MySQL 表:
 *   ads_market_summary — 实时增量写入，仅此表由此类负责
 *   dws_stock_minute / dws_stock_day — 由 MarketFlusher 按日/关闭时写入
 * </pre>
 *
 * @see MarketFlusher  flush + clear 逻辑
 * @see RedisKeys     Redis key 常量
 * @see LuaScriptManager Lua 脚本 + SHA 缓存
 */
public final class MarketDataWriter {

    private static final Logger LOG = LoggerFactory.getLogger(MarketDataWriter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    private MarketDataWriter() {}

    // ============================================================
    // 公共入口
    // ============================================================

    /**
     * 处理一个 batch：日清检查 → Redis 写入 → MySQL 市场概览归档
     *
     * <pre>
     * 日清检查两阶段:
     *   1. 跨天 batch: 本批日期不唯一 → flush 旧数据 → 跳过（极少发生）
     *   2. 日清: stat_date ≠ today → flush 昨天数据 → 重置所有 key → 今日从零累加
     *
     * Redis 写入:
     *   - Pipeline 模式(默认): 每 500 条 sync 一次，减少网络往返
     *   - 非 Pipeline 模式: 逐条 EVALSHA，Redis 重启时 NOSCRIPT → 回退重试
     * </pre>
     */
    public static void write(JavaRDD<StockQuote> parsedRDD) {
        if (parsedRDD.isEmpty()) return; // 防御性检查，caller 已确保非空

        long t0 = System.currentTimeMillis();

        // ---- 1. 跨天 batch 检查 ----
        long distinctDates = parsedRDD.map(StockQuote::getTradeDate).distinct().count();
        if (distinctDates > 1) {
            try (Jedis jedis = RedisUtil.newJedis()) {
                MarketFlusher.flushAll(jedis);
                jedis.del(RedisKeys.MARKET_SUMMARY);
                MarketFlusher.clearOhlcvKeys(jedis);
                MarketFlusher.clearMinuteKeys(jedis);
                LOG.warn("[MarketDataWriter] 跨天 batch ({} 个日期), flush + 跳过", distinctDates);
            } catch (Exception e) {
                LOG.error("[MarketDataWriter] 跨天清空 Redis 失败", e);
            }
            return;
        }

        // ---- 2. 日清检查 ----
        String today = parsedRDD.first().getTradeDate();
        try (Jedis jedis = RedisUtil.newJedis()) {
            String storedDate = jedis.hget(RedisKeys.MARKET_SUMMARY, "stat_date");
            if (!today.equals(storedDate)) {
                MarketFlusher.flushAll(jedis);
                jedis.del(RedisKeys.MARKET_SUMMARY);
                MarketFlusher.clearOhlcvKeys(jedis);
                MarketFlusher.clearMinuteKeys(jedis);
                jedis.hmset(RedisKeys.MARKET_SUMMARY, resetSummaryFields(today));
                LOG.info("[MarketDataWriter] 日清: {} → {}", storedDate, today);
            }
        } catch (Exception e) {
            LOG.error("[MarketDataWriter] 日清检查失败", e);
            return;
        }

        // ---- 3. 并行写入 Redis（foreachPartition → Lua EVALSHA） ----
        final boolean usePipeline = Config.redisPipelineEnabled();
        parsedRDD.foreachPartition(iterator -> {
            if (!iterator.hasNext()) return;

            Jedis jedis = null;
            int count = 0;
            try {
                jedis = RedisUtil.newJedis();
                // SCRIPT LOAD 获取 SHA（每个 Executor JVM 首次调用时加载）
                String sha = LuaScriptManager.ensureOhlcvSha(jedis);

                if (usePipeline) {
                    // Pipeline 模式: 把多条 EVALSHA 打包发送
                    String mSha = Config.minuteEnabled() ? LuaScriptManager.ensureMinuteSha(jedis) : null;
                    final int FLUSH_SIZE = 500;
                    redis.clients.jedis.Pipeline pipeline = jedis.pipelined();
                    while (iterator.hasNext()) {
                        StockQuote q = iterator.next();

                        // ---- 日线 OHLCV upsert ----
                        // KEYS: ohlcv:{code}, market:summary, ohlcv:codes (跟踪 SET)
                        // ARGV: OHLCV JSON, stat_time, code
                        String json = buildOhlcvJson(q);
                        String statTime = formatStatTime(q.getTradeDate(), q.getTradeTime());
                        pipeline.evalsha(sha,
                                Arrays.asList(RedisKeys.ohlcvKey(q.getCode()), RedisKeys.MARKET_SUMMARY, RedisKeys.OHLCV_CODES),
                                Arrays.asList(json, statTime, q.getCode()));

                        // ---- 分钟 OHLCV 记录 ----
                        // 只在交易时段 (09:25-15:05) 写入，按 5 分钟窗口取整
                        String minuteWindow = toMinuteWindow(q.getTradeDate(), q.getTradeTime());
                        if (mSha != null && minuteWindow != null && isTradingTime(q.getTradeTime())) {
                            String minuteTime = minuteWindow.substring(11); // 只取 HH:mm:00 部分
                            pipeline.evalsha(mSha,
                                    Arrays.asList(
                                            RedisKeys.minuteKey(q.getCode(), minuteWindow),
                                            RedisKeys.minuteCodesKey(minuteWindow),
                                            RedisKeys.MINUTE_WINDOWS),
                                    Arrays.asList(
                                            String.valueOf(q.getPrice()),
                                            String.valueOf((long) q.getVolume()),  // 当日累计量
                                            String.valueOf(q.getAmount()),        // 当日累计额
                                            q.getTradeDate(),
                                            q.getTradeTime(),
                                            q.getCode(),
                                            minuteTime));  // Lua 内拼 tradeDate + minuteTime = 窗口全名
                        }

                        if (++count % FLUSH_SIZE == 0) {
                            pipeline.sync();          // 发送已缓冲的命令
                            pipeline = jedis.pipelined(); // 新建 Pipeline 继续
                        }
                    }
                    pipeline.sync(); // 发送最后一批
                } else {
                    // 非 Pipeline 模式: 逐条发送，支持单条 NOSCRIPT 回退
                    String mSha = Config.minuteEnabled() ? LuaScriptManager.ensureMinuteSha(jedis) : null;
                    while (iterator.hasNext()) {
                        StockQuote q = iterator.next();
                        String json = buildOhlcvJson(q);
                        String statTime = formatStatTime(q.getTradeDate(), q.getTradeTime());

                        // 日线 OHLCV
                        try {
                            jedis.evalsha(sha,
                                    Arrays.asList(RedisKeys.ohlcvKey(q.getCode()), RedisKeys.MARKET_SUMMARY, RedisKeys.OHLCV_CODES),
                                    Arrays.asList(json, statTime, q.getCode()));
                            count++;
                        } catch (JedisNoScriptException e) {
                            LOG.warn("[MarketDataWriter] NOSCRIPT (OHLCV), 重新 LOAD");
                            LuaScriptManager.resetAll();
                            sha = LuaScriptManager.ensureOhlcvSha(jedis);
                            jedis.evalsha(sha,
                                    Arrays.asList(RedisKeys.ohlcvKey(q.getCode()), RedisKeys.MARKET_SUMMARY, RedisKeys.OHLCV_CODES),
                                    Arrays.asList(json, statTime, q.getCode()));
                            count++;
                        }

                        // 分钟 OHLCV
                        if (mSha != null) {
                            String minuteWindow = toMinuteWindow(q.getTradeDate(), q.getTradeTime());
                            if (minuteWindow != null && isTradingTime(q.getTradeTime())) {
                                String minuteTime = minuteWindow.substring(11);
                                try {
                                    jedis.evalsha(mSha,
                                        Arrays.asList(
                                                RedisKeys.minuteKey(q.getCode(), minuteWindow),
                                                RedisKeys.minuteCodesKey(minuteWindow),
                                                RedisKeys.MINUTE_WINDOWS),
                                        Arrays.asList(
                                                String.valueOf(q.getPrice()),
                                                String.valueOf((long) q.getVolume()),
                                                String.valueOf(q.getAmount()),
                                                q.getTradeDate(),
                                                q.getTradeTime(),
                                                q.getCode(),
                                                minuteTime));
                            } catch (JedisNoScriptException e) {
                                LOG.warn("[MarketDataWriter] NOSCRIPT (分钟), 重新 LOAD");
                                LuaScriptManager.resetAll();
                                mSha = LuaScriptManager.ensureMinuteSha(jedis);
                                jedis.evalsha(mSha,
                                        Arrays.asList(
                                                RedisKeys.minuteKey(q.getCode(), minuteWindow),
                                                RedisKeys.minuteCodesKey(minuteWindow),
                                                RedisKeys.MINUTE_WINDOWS),
                                        Arrays.asList(
                                                String.valueOf(q.getPrice()),
                                                String.valueOf((long) q.getVolume()),
                                                String.valueOf(q.getAmount()),
                                                q.getTradeDate(),
                                                q.getTradeTime(),
                                                q.getCode(),
                                                minuteTime));
                            }
                        }
                        } // Config.minuteEnabled()
                    }
                }
                LOG.info("[MarketDataWriter] 分区写入完成, {} 条, mode={}", count, usePipeline ? "pipeline" : "plain");
            } catch (JedisNoScriptException e) {
                // Pipeline 模式下无法逐条恢复 → 重置 SHA → 下个 batch 重试
                LuaScriptManager.resetAll();
                LOG.error("Pipeline NOSCRIPT, SHA 已重置, 本分区 {} 条需下 batch 重试", count, e);
            } catch (Exception e) {
                LOG.error("Redis 分区写入失败", e);
            } finally {
                if (jedis != null) {
                    try { jedis.close(); } catch (Exception ignored) { }
                }
            }
        });

        // ---- 4. MySQL 市场概览归档（Driver 侧，读 Redis Hash → 写 MySQL，每 batch 一行） ----
        archiveToMysql();
    }

    // ============================================================
    // MySQL 市场概览归档
    // ============================================================

    /**
     * 从 Redis stock:market:summary Hash 读取当日累计，写入 MySQL ads_market_summary
     *
     * 使用 REPLACE INTO: stat_time 不变时覆盖旧行（午休/收盘后持续写入不报错）
     * 单位保持原始 股/元，不做转换
     */
    private static void archiveToMysql() {
        if (!Config.marketEnabled()) {
            LOG.debug("[MarketDataWriter] feature.market.enabled=false, 跳过市场概览归档");
            return;
        }
        try (Jedis jedis = RedisUtil.newJedis()) {
            Map<String, String> map = jedis.hgetAll(RedisKeys.MARKET_SUMMARY);
            if (map == null || map.isEmpty()) {
                LOG.warn("MySQL 归档跳过: Redis market:summary 为空");
                return;
            }

            // 字段完整性检查: 日清后重置的 summary 也可能缺少字段
            String sql = "REPLACE INTO ads_market_summary " +
                    "(stat_time, total_stocks, up_count, down_count, flat_count, " +
                    " avg_change_pct, total_volume, total_amount) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            String[] required = {"stat_time", "total_stocks", "up_count", "down_count",
                    "flat_count", "avg_change_pct", "total_volume", "total_amount"};
            for (String field : required) {
                if (map.get(field) == null) {
                    LOG.warn("MySQL 归档跳过: Redis Hash 字段缺失 ({})", field);
                    return;
                }
            }

            try (Connection conn = JdbcUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, map.get("stat_time"));
                ps.setLong(2, Long.parseLong(map.get("total_stocks")));
                ps.setLong(3, Long.parseLong(map.get("up_count")));
                ps.setLong(4, Long.parseLong(map.get("down_count")));
                ps.setLong(5, Long.parseLong(map.get("flat_count")));
                ps.setDouble(6, Double.parseDouble(map.get("avg_change_pct")));
                ps.setLong(7, Long.parseLong(map.get("total_volume")));   // BIGINT, 存股
                ps.setDouble(8, Double.parseDouble(map.get("total_amount"))); // DECIMAL(24,4), 存元
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

    /** 日清时预置 summary Hash 全部字段为 0（_sum_pct 是内部计算中间值） */
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
     * tradeDate + tradeTime → "yyyy-MM-dd HH:mm:ss"
     * null 时用系统时间兜底，避免写入 stat_time NOT NULL 失败
     */
    private static String formatStatTime(String tradeDate, String tradeTime) {
        if (tradeDate == null || tradeTime == null) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        }
        return tradeDate + " " + tradeTime;
    }

    /**
     * tradeTime → 5 分钟窗口，向下取整
     * (m / 5) * 5: 0→0, 1→0, 4→0, 5→5, 9→5 ...
     * 例: "09:33:15" → "2026-07-01 09:30:00"
     */
    static String toMinuteWindow(String tradeDate, String tradeTime) {
        if (tradeDate == null || tradeTime == null) return null;
        try {
            int h = Integer.parseInt(tradeTime.substring(0, 2));
            int m = Integer.parseInt(tradeTime.substring(3, 5));
            return String.format("%s %02d:%02d:00", tradeDate, h, (m / 5) * 5);
        } catch (Exception e) { return null; }
    }

    /**
     * 交易时段过滤: 09:25 ~ 15:05
     * 09:25 起: 集合竞价后才有有效成交价
     * 15:05 止: 覆盖收盘后延迟到达的尾盘数据
     */
    private static boolean isTradingTime(String tradeTime) {
        if (tradeTime == null) return false;
        return tradeTime.compareTo("09:25:00") >= 0 && tradeTime.compareTo("15:05:00") < 0;
    }

    static BigDecimal bd2(double v) {
        return BigDecimal.valueOf(v).setScale(2, BigDecimal.ROUND_HALF_UP);
    }
}
