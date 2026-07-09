package com.stock.batch;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.common.Config;
import com.stock.common.JdbcUtil;
import com.stock.common.RedisKeys;
import com.stock.common.StockQuote;
import com.stock.streaming.SystemStatusWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;

/**
 * Redis → MySQL 数据持久化 + Redis key 清理
 *
 * <pre>
 * 分钟 flush: 逐窗口流式处理，每窗口一个事务 → dws_stock_minute
 * 日线 flush: Pipeline GET 全量 → 批量 INSERT → dws_stock_day
 * </pre>
 */
public final class MarketFlusher {

    private static final Logger LOG = LoggerFactory.getLogger(MarketFlusher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    private MarketFlusher() {}

    // ============================================================
    // 公开入口
    // ============================================================

    /** flush 全部（日清 / shutdown 用），受 feature.flush.*.enabled 控制 */
    public static void flushAll(Jedis jedis) {
        SystemStatusWriter.markFlushStart("auto");
        try {
            if (Config.minuteFlushEnabled()) flushAllMinute(jedis, null);
            if (Config.dailyFlushEnabled())  flushAllDaily(jedis);
        } finally {
            SystemStatusWriter.markFlushEnd();
        }
    }

    /** flush 指定日期（daily-replay 专用），受 feature.flush.*.enabled 控制 */
    public static void flushAll(Jedis jedis, String date) {
        SystemStatusWriter.markFlushStart("manual");
        try {
            if (Config.minuteFlushEnabled()) flushAllMinute(jedis, date);
            if (Config.dailyFlushEnabled())  flushAllDaily(jedis);
        } finally {
            SystemStatusWriter.markFlushEnd();
        }
    }

    // ============================================================
    // 分钟 flush
    // ============================================================

    /**
     * 逐窗口流式 flush → dws_stock_minute
     *
     * <pre>
     * 为什么按窗口流式而非全量收集:
     *   - 内存: 只存 ~5K 条 prevState，而非 27 万条 3 层 HashMap
     *   - 事务: 每窗口 ~5K 行一个事务，而非 27 万行单事务
     *
     * LAG 做差逻辑:
     *   Redis 存的 volume/amount 是当日累计值（从 Lua 脚本写入的 cum_vol/cum_amt）
     *   要得到每个 5 分钟窗口的独立成交量，需要: 本窗口累计 − 上一窗口累计
     *   首窗口: lagVol = rawVol（没有上一窗口，直接取累计值）
     *   后续窗口: lagVol = max(rawVol - prevVol, 0)（防负数，cover 极端乱序）
     * </pre>
     *
     * @param jedis      Redis 连接
     * @param dateFilter 只处理匹配日期的窗口，null=全部
     */
    private static void flushAllMinute(Jedis jedis, String dateFilter) {
        long t0 = System.currentTimeMillis();

        // 获取所有窗口，按时间排序
        Set<String> windows = jedis.smembers(RedisKeys.MINUTE_WINDOWS);
        if (windows == null || windows.isEmpty()) {
            LOG.info("[MarketFlusher] 分钟 flush: 无窗口数据");
            return;
        }

        List<String> sortedWindows = new ArrayList<>(windows);
        Collections.sort(sortedWindows);

        // 按日期过滤（daily-replay 传 "2026-07-02" 只处理当天窗口）
        if (dateFilter != null && !dateFilter.isEmpty()) {
            List<String> filtered = new ArrayList<>();
            for (String w : sortedWindows) {
                if (w.startsWith(dateFilter)) filtered.add(w);
            }
            sortedWindows = filtered;
        }
        if (sortedWindows.isEmpty()) {
            LOG.info("[MarketFlusher] 分钟 flush: 无匹配窗口, dateFilter={}", dateFilter);
            return;
        }

        LOG.info("[MarketFlusher] 分钟 flush 开始: {} 个窗口, dateFilter={}",
                sortedWindows.size(), dateFilter != null ? dateFilter : "all");

        // 跟踪每个股票上一窗口的累计量，用于 LAG 计算
        Map<String, Long> prevVol = new HashMap<>();
        Map<String, Double> prevAmt = new HashMap<>();

        int total = 0, windowsOk = 0, windowsSkipped = 0;
        String sql = "REPLACE INTO dws_stock_minute " +
                "(code, minute_time, open, high, low, close, volume, amount) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = JdbcUtil.getConnection()) {
            for (String window : sortedWindows) {
                String codesKey = RedisKeys.minuteCodesKey(window);
                Set<String> codes = jedis.smembers(codesKey);
                if (codes == null || codes.isEmpty()) {
                    windowsSkipped++;
                    continue;
                }

                // Pipeline 一次网络往返读取本窗口所有股票
                List<String> codeList = new ArrayList<>(codes);
                redis.clients.jedis.Pipeline pipe = jedis.pipelined();
                List<redis.clients.jedis.Response<Map<String, String>>> responses = new ArrayList<>();
                for (String code : codeList) {
                    responses.add(pipe.hgetAll(RedisKeys.minuteKey(code, window)));
                }
                pipe.sync();

                // 本窗口一个独立事务: ~5K 行 → commit
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int batchRows = 0;
                    for (int i = 0; i < codeList.size(); i++) {
                        Map<String, String> m = responses.get(i).get();
                        if (m == null || m.isEmpty()) continue;

                        String code = codeList.get(i);
                        long rawVol  = Long.parseLong(m.getOrDefault("last_vol", "0"));
                        double rawAmt = Double.parseDouble(m.getOrDefault("last_amt", "0"));

                        // LAG = 当前累计 − 上一窗口累计
                        Long pv = prevVol.get(code);
                        Double pa = prevAmt.get(code);
                        long lagVol   = (pv == null) ? rawVol : Math.max(rawVol - pv, 0);
                        double lagAmt = (pa == null) ? rawAmt : Math.max(rawAmt - pa, 0.0);

                        prevVol.put(code, rawVol);
                        prevAmt.put(code, rawAmt);

                        if (lagVol == 0 && lagAmt == 0) continue; // 本窗口无成交

                        ps.setString(1, code);
                        ps.setString(2, window);
                        ps.setBigDecimal(3, new BigDecimal(m.getOrDefault("open", "0")));
                        ps.setBigDecimal(4, new BigDecimal(m.getOrDefault("high", "0")));
                        ps.setBigDecimal(5, new BigDecimal(m.getOrDefault("low", "0")));
                        ps.setBigDecimal(6, new BigDecimal(m.getOrDefault("close", "0")));
                        ps.setLong(7, lagVol);
                        ps.setBigDecimal(8, BigDecimal.valueOf(lagAmt));
                        ps.addBatch();
                        batchRows++;
                        total++;
                    }
                    if (batchRows > 0) {
                        ps.executeBatch();
                        conn.commit();
                    }
                    windowsOk++;
                    // 每 5 个窗口更新一次进度
                    if (windowsOk % 5 == 0) {
                        SystemStatusWriter.markFlushProgress(
                                windowsOk, sortedWindows.size(), total,
                                System.currentTimeMillis() - t0);
                    }
                } catch (Exception e) {
                    conn.rollback();
                    LOG.error("[MarketFlusher] 窗口 flush 失败: {}", window, e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        } catch (Exception e) {
            LOG.error("[MarketFlusher] 分钟 flush 失败", e);
        }

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("[MarketFlusher] 分钟 flush 完成: rows={}, ok={}/{}, elapsed={}ms",
                total, windowsOk, sortedWindows.size(), elapsed);
        if (windowsSkipped > 0) {
            LOG.info("[MarketFlusher] 空窗口跳过: {} 个", windowsSkipped);
        }
    }

    // ============================================================
    // 日线 flush
    // ============================================================

    /** Pipeline GET 全量 OHLCV → 批量 INSERT dws_stock_day */
    private static void flushAllDaily(Jedis jedis) {
        long t0 = System.currentTimeMillis();

        Set<String> codes = jedis.smembers(RedisKeys.OHLCV_CODES);
        if (codes == null || codes.isEmpty()) {
            LOG.info("[MarketFlusher] 日线 flush: 无数据");
            return;
        }
        LOG.info("[MarketFlusher] 日线 flush 开始: {} 只股票", codes.size());

        // 保留 code 列表，避免后续 substring 反解
        List<String> codeList = new ArrayList<>(codes);

        redis.clients.jedis.Pipeline pipe = jedis.pipelined();
        List<redis.clients.jedis.Response<String>> responses = new ArrayList<>();
        for (String code : codeList) responses.add(pipe.get(RedisKeys.ohlcvKey(code)));
        pipe.sync();

        int total = 0, batchSize = 0, parseErrors = 0;
        String sql = "REPLACE INTO dws_stock_day " +
                "(code, trade_date, open, high, low, close, volume, amount, change_pct) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = JdbcUtil.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < codeList.size(); i++) {
                    String code = codeList.get(i);
                    String json = responses.get(i).get();
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
                        batchSize++;

                        if (batchSize >= 10000) {
                            ps.executeBatch();
                            conn.commit();
                            total += batchSize;
                            batchSize = 0;
                        }
                    } catch (Exception e) {
                        parseErrors++;
                        LOG.warn("[MarketFlusher] 日线 JSON 解析失败: code={}", code, e);
                    }
                }
                if (batchSize > 0) {
                    ps.executeBatch();
                    conn.commit();
                    total += batchSize;
                }
            } catch (Exception e) { conn.rollback(); throw e; }
        } catch (Exception e) {
            LOG.error("[MarketFlusher] 日线 flush 失败", e);
        }

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("[MarketFlusher] 日线 flush 完成: rows={}/{}, errors={}, elapsed={}ms",
                total, codeList.size(), parseErrors, elapsed);
    }

    // ============================================================
    // 清理
    // ============================================================

    /**
     * 清理所有分钟 key
     *
     * 不用 KEYS 命令（O(N) 阻塞全库），而是:
     *   1. SMEMBERS stock:minute:windows → 拿到所有窗口
     *   2. 对每个窗口 SMEMBERS stock:minute:codes:{window} → 拿到该窗口的 code 列表
     *   3. 拼出 stock:minute:{code}:{window} → 批量 DEL（每 1000 个 key 一次）
     *   4. 删 tracking SET 本身
     *
     * 复杂度 O(windows × codes)，但全程非阻塞
     */
    public static void clearMinuteKeys(Jedis jedis) {
        Set<String> windows = jedis.smembers(RedisKeys.MINUTE_WINDOWS);
        if (windows == null || windows.isEmpty()) return;

        int count = 0;
        List<String> batch = new ArrayList<>(1000); // 每 1000 个 key 一次 DEL
        for (String window : windows) {
            Set<String> codes = jedis.smembers(RedisKeys.minuteCodesKey(window));
            if (codes != null) {
                for (String code : codes) {
                    batch.add(RedisKeys.minuteKey(code, window));
                    count++;
                    if (batch.size() >= 1000) {
                        jedis.del(batch.toArray(new String[0])); // 批量删除
                        batch.clear();
                    }
                }
            }
            jedis.del(RedisKeys.minuteCodesKey(window)); // 删 code 集合本身
        }
        if (!batch.isEmpty()) {
            jedis.del(batch.toArray(new String[0])); // 尾批
        }
        jedis.del(RedisKeys.MINUTE_WINDOWS); // 最后删窗口集合
        LOG.info("清理分钟 key: {} 个", count);
    }

    /** 清理所有 OHLCV key（遍历 tracking SET + 批量 DEL，每 1000 个一批） */
    public static void clearOhlcvKeys(Jedis jedis) {
        Set<String> codes = jedis.smembers(RedisKeys.OHLCV_CODES);
        if (codes == null || codes.isEmpty()) return;

        int count = 0;
        List<String> batch = new ArrayList<>(1000);
        for (String code : codes) {
            batch.add(RedisKeys.ohlcvKey(code));
            count++;
            if (batch.size() >= 1000) {
                jedis.del(batch.toArray(new String[0]));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jedis.del(batch.toArray(new String[0]));
        }
        jedis.del(RedisKeys.OHLCV_CODES);
        LOG.info("[MarketFlusher] 清理 OHLCV key: {} 个", count);
    }

    // ============================================================
    // 工具
    // ============================================================

    private static BigDecimal bd2(double v) {
        return BigDecimal.valueOf(v).setScale(2, BigDecimal.ROUND_HALF_UP);
    }
}
