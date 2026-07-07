package com.stock.streaming;

import com.stock.common.Config;
import com.stock.common.RedisKeys;
import com.stock.common.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统运行状态写入 — stock:system:status Hash (25 字段)
 *
 * <pre>
 * 写入时机:
 *   - 启动:         状态字段 + 数据字段清零
 *   - 每 batch:     数据统计字段 + uptime (不覆盖 error 状态)
 *   - flush 进行中:  flush 进度字段 (不写 status，保持 flushing)
 *   - flush 结束:    状态恢复 running
 *   - 错误:          last_error
 *   - consumer_lag / consumer_pct: 由外部脚本更新 (需 Kafka 查询)
 *
 * 所有方法外部捕获异常 — 状态写入不应影响主流程
 * </pre>
 */
public final class SystemStatusWriter {

    private static final Logger LOG = LoggerFactory.getLogger(SystemStatusWriter.class);
    private static final String KEY = "stock:system:status";

    private static volatile long batchCount = 0;
    private static volatile long startedAt = 0;
    private static String consumerMode = "normal";
    private static String targetDate = "all";
    private static volatile boolean heartbeatRunning = false;
    private static volatile boolean heartbeatFailed = false;

    private SystemStatusWriter() {}

    // ============================================================
    // 心跳线程 — 独立于 batch，每 10 秒更新 heartbeat_at
    // ============================================================

    private static synchronized void startHeartbeat() {
        if (heartbeatRunning) return;
        heartbeatRunning = true;
        Thread t = new Thread(() -> {
            while (heartbeatRunning) {
                try {
                    try (Jedis jedis = RedisUtil.newJedis()) {
                        jedis.hset(KEY, "heartbeat_at", now());
                        heartbeatFailed = false;
                    }
                } catch (Exception e) {
                    if (!heartbeatFailed) {
                        heartbeatFailed = true;
                        LOG.warn("[SystemStatus] 心跳写入失败 (Redis 不可用?): {}", e.getMessage());
                    }
                }
                try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
            }
        }, "sys-status-heartbeat");
        t.setDaemon(true); // JVM 退出时自动终止
        t.start();
        LOG.info("[SystemStatus] 心跳线程启动 (间隔 10s)");
    }

    // ============================================================
    // 启动 — 初始化全部字段
    // ============================================================

    public static void markStartup(String mode, String date) {
        consumerMode = mode;
        targetDate = (date != null) ? date : "all";
        startedAt = System.currentTimeMillis();

        try (Jedis jedis = RedisUtil.newJedis()) {
            Map<String, String> m = new HashMap<>();
            // 运行状态
            m.put("status",       "running");
            m.put("mode",         consumerMode);
            m.put("target_date",  targetDate);
            m.put("started_at",   now());
            m.put("uptime_seconds", "0");
            m.put("heartbeat_at",  now());
            // 特性开关
            m.put("feature_ods",    String.valueOf(Config.odsEnabled()));
            m.put("feature_dwd",    String.valueOf(Config.dwdEnabled()));
            m.put("feature_rank",   String.valueOf(Config.rankEnabled()));
            m.put("feature_market", String.valueOf(Config.marketEnabled()));
            m.put("feature_minute", String.valueOf(Config.minuteEnabled()));
            m.put("feature_flush_minute", String.valueOf(Config.minuteFlushEnabled()));
            m.put("feature_flush_daily",  String.valueOf(Config.dailyFlushEnabled()));
            m.put("feature_flushdb",  String.valueOf(Config.flushdbOnStart()));
            // 数据统计
            m.put("current_date",   "");
            m.put("redis_keys",     "0");
            m.put("minute_windows", "0");
            m.put("ohlcv_codes",    "0");
            m.put("rank_up_count",  "0");
            m.put("rank_amount_count", "0");
            m.put("batch_count",    "0");
            m.put("batch_ms",       "0");
            // 消费进度（外部脚本更新）
            m.put("consumer_lag", "0");
            m.put("consumer_pct", "0");
            // flush 进度
            m.put("flush_windows_done",  "0");
            m.put("flush_windows_total", "0");
            m.put("flush_rows",          "0");
            m.put("flush_elapsed_sec",   "0");
            // 事件
            m.put("last_flush_at",   "");
            m.put("last_flush_type", "");
            m.put("last_error",      "");
            m.put("last_error_at",   "");
            // 时间戳
            m.put("updated_at", now());
            jedis.hmset(KEY, m);
            startHeartbeat();
            LOG.info("[SystemStatus] 启动: mode={}, date={}", consumerMode, targetDate);
        } catch (Exception e) {
            LOG.warn("[SystemStatus] 写入启动状态失败: {}", e.getMessage());
        }
    }

    // ============================================================
    // 每 batch (数据统计 + uptime, 不覆盖 error 状态)
    // ============================================================

    public static void markBatch(String currentDate, long batchElapsedMs) {
        batchCount++;
        try (Jedis jedis = RedisUtil.newJedis()) {
            Map<String, String> m = new HashMap<>();
            // 不写 status — 保留 markError 设置的 "error"，由 markFlushEnd 恢复 "running"
            m.put("current_date",   nvl(currentDate));
            m.put("redis_keys",     String.valueOf(jedis.dbSize()));
            m.put("minute_windows", String.valueOf(jedis.scard(RedisKeys.MINUTE_WINDOWS)));
            m.put("ohlcv_codes",    String.valueOf(jedis.scard(RedisKeys.OHLCV_CODES)));
            m.put("rank_up_count",  String.valueOf(jedis.zcard(RedisKeys.RANK_UP)));
            m.put("rank_amount_count", String.valueOf(jedis.zcard(RedisKeys.RANK_AMOUNT)));
            m.put("batch_count",    String.valueOf(batchCount));
            m.put("batch_ms",       String.valueOf(batchElapsedMs));
            m.put("uptime_seconds", String.valueOf((System.currentTimeMillis() - startedAt) / 1000));
            m.put("updated_at",    now());
            jedis.hmset(KEY, m);
        } catch (Exception e) {
            LOG.warn("[SystemStatus] 写入 batch 状态失败: {}", e.getMessage());
        }
    }

    // ============================================================
    // flush 事件
    // ============================================================

    public static void markFlushStart(String flushType) {
        try (Jedis jedis = RedisUtil.newJedis()) {
            Map<String, String> m = new HashMap<>();
            m.put("status",           "flushing");
            m.put("last_flush_at",    now());
            m.put("last_flush_type",  flushType);
            m.put("flush_windows_done",  "0");
            m.put("flush_windows_total", "0");
            m.put("flush_rows",          "0");
            m.put("flush_elapsed_sec",   "0");
            m.put("updated_at",       now());
            jedis.hmset(KEY, m);
        } catch (Exception e) {
            LOG.warn("[SystemStatus] 写入 flush 开始失败: {}", e.getMessage());
        }
    }

    /**
     * flush 进行中更新进度。
     * 不写 status 字段 — hmset 只覆盖传入字段，status 保持 markFlushStart 设置的 "flushing"。
     */
    public static void markFlushProgress(int windowsDone, int windowsTotal, int rows, long elapsedMs) {
        try (Jedis jedis = RedisUtil.newJedis()) {
            Map<String, String> m = new HashMap<>();
            m.put("flush_windows_done",  String.valueOf(windowsDone));
            m.put("flush_windows_total", String.valueOf(windowsTotal));
            m.put("flush_rows",          String.valueOf(rows));
            m.put("flush_elapsed_sec",   String.valueOf(elapsedMs / 1000));
            m.put("updated_at",          now());
            jedis.hmset(KEY, m);
        } catch (Exception e) {
            // 静默，不打断 flush
        }
    }

    public static void markFlushEnd() {
        try (Jedis jedis = RedisUtil.newJedis()) {
            Map<String, String> m = new HashMap<>();
            m.put("status",              "running"); // 恢复 running
            m.put("flush_windows_done",  "0");
            m.put("flush_windows_total", "0");
            m.put("flush_rows",          "0");
            m.put("flush_elapsed_sec",   "0");
            m.put("updated_at",          now());
            jedis.hmset(KEY, m);
        } catch (Exception e) {
            LOG.warn("[SystemStatus] 写入 flush 结束失败: {}", e.getMessage());
        }
    }

    // ============================================================
    // 错误
    // ============================================================

    public static void markError(String errorMsg) {
        try (Jedis jedis = RedisUtil.newJedis()) {
            Map<String, String> m = new HashMap<>();
            m.put("status",        "error");
            m.put("last_error",    nvl(errorMsg));
            m.put("last_error_at", now());
            m.put("updated_at",    now());
            jedis.hmset(KEY, m);
        } catch (Exception e) {
            // Redis 不可用时 fallback 到日志，让运维至少能查到
            LOG.warn("[SystemStatus] 写入错误状态失败 (Redis 不可用?): {}", errorMsg);
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
