package com.stock.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.common.Config;
import com.stock.common.JdbcUtil;
import com.stock.common.RedisKeys;
import com.stock.common.RedisUtil;
import com.stock.common.StockQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;

/**
 * 实时榜单 —— Redis ZSet 排名 + MySQL 归档
 *
 * <pre>
 * 每 batch 执行一次:
 *   1. 从 stock:quote:ohlcv:* 读取所有个股 OHLCV JSON
 *   2. 提取 change_pct / amount 写入 Redis ZSet（自动排序）
 *   3. 取 Top N 写入 MySQL ads_stock_rank
 *
 * Redis key:
 *   stock:rank:up      ZSet — 涨幅榜 (score=change_pct)
 *   stock:rank:amount  ZSet — 成交额榜 (score=amount)
 *
 * ZSet vs 每次全量排序:
 *   ZADD O(log N) 逐股更新, ZREVRANGE O(log N + M) 取 Top N
 *   比每次 5000 条全量排序高效得多, 且天然支持 web 端实时查询
 * </pre>
 */
public final class RankWriter {

    private static final Logger LOG = LoggerFactory.getLogger(RankWriter.class);

    /** 榜单保留 Top N */
    private static final int TOP_N = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Rank keys 统一在 RedisKeys 中定义

    private RankWriter() {}

    /**
     * 更新全量排名（Driver 侧调用，每 batch 一次）
     */
    public static void update() {
        if (!Config.rankEnabled()) {
            LOG.debug("[RankWriter] feature.rank.enabled=false, 跳过");
            return;
        }
        long t0 = System.currentTimeMillis();
        try (Jedis jedis = RedisUtil.newJedis()) {

            // ---- 1. 获取所有 OHLCV code ----
            Set<String> codes = jedis.smembers(RedisKeys.OHLCV_CODES);
            if (codes == null || codes.isEmpty()) {
                LOG.debug("[RankWriter] 无 OHLCV 数据, 跳过");
                return;
            }

            // ---- 2. Pipeline 批量读取 JSON ----
            List<String> codeList = new ArrayList<>(codes);
            redis.clients.jedis.Pipeline pipe = jedis.pipelined();
            List<redis.clients.jedis.Response<String>> responses = new ArrayList<>();
            for (String code : codeList) responses.add(pipe.get(RedisKeys.ohlcvKey(code)));
            pipe.sync();

            // ---- 3. ZADD 更新排名 ----
            jedis.del(RedisKeys.RANK_UP, RedisKeys.RANK_AMOUNT);
            Map<String, String> codeNames = new HashMap<>();
            int parseErrors = 0;

            for (int i = 0; i < codeList.size(); i++) {
                String code = codeList.get(i);
                String json = responses.get(i).get();
                if (json == null) continue;
                try {
                    StockQuote q = MAPPER.readValue(json, StockQuote.class);
                    codeNames.put(code, q.getName());
                    jedis.zadd(RedisKeys.RANK_UP,     q.getChangePct(), code);
                    jedis.zadd(RedisKeys.RANK_AMOUNT, q.getAmount(),    code);
                } catch (Exception e) {
                    parseErrors++;
                    if (parseErrors <= 3) {
                        LOG.warn("[RankWriter] JSON 解析失败: code={}", code, e);
                    }
                }
            }
            long tZadd = System.currentTimeMillis();
            LOG.info("[RankWriter] ZADD 完成: codes={}, errors={}, elapsed={}ms",
                    codeList.size(), parseErrors, tZadd - t0);

            // ---- 4. 取 Top N 写入 MySQL ----
            String statTime = jedis.hget(RedisKeys.MARKET_SUMMARY, "stat_time");
            if (statTime == null) statTime = "";
            writeTopToMysql(jedis, statTime, codeNames);

            LOG.info("[RankWriter] 完成: totalElapsed={}ms", System.currentTimeMillis() - t0);

        } catch (Exception e) {
            LOG.error("[RankWriter] 更新失败", e);
            SystemStatusWriter.markError("Rank: " + e.getMessage());
        }
    }

    /**
     * 从 Redis ZSet 取 Top N，写入 MySQL
     *
     * ZREVRANGE: 从高到低取前 N（涨幅榜正值排前, 成交额榜大额排前）
     * 跌幅榜: ZRANGE 从低到高取前 N（最负的排前）
     */
    private static void writeTopToMysql(Jedis jedis, String statTime,
                                         Map<String, String> codeNames) {
        // 涨幅榜: ZREVRANGE (从高到低)
        Set<redis.clients.jedis.Tuple> upTop = jedis.zrevrangeWithScores(RedisKeys.RANK_UP, 0, TOP_N - 1);
        // 跌幅榜: ZRANGE (从低到高, 最负在前)
        Set<redis.clients.jedis.Tuple> downTop = jedis.zrangeWithScores(RedisKeys.RANK_UP, 0, TOP_N - 1);
        // 成交额榜: ZREVRANGE (从高到低)
        Set<redis.clients.jedis.Tuple> amountTop = jedis.zrevrangeWithScores(RedisKeys.RANK_AMOUNT, 0, TOP_N - 1);

        String sql = "REPLACE INTO ads_stock_rank " +
                "(rank_type, code, name, rank_no, score, stat_time) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        int total = 0;
        try (Connection conn = JdbcUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            total += writeRank(ps, "up",     upTop,     codeNames, statTime);
            total += writeRank(ps, "down",   downTop,   codeNames, statTime);
            total += writeRank(ps, "amount", amountTop, codeNames, statTime);

            LOG.info("[RankWriter] MySQL 写入: {} 条 (up={}, down={}, amount={})",
                    total, upTop.size(), downTop.size(), amountTop.size());
        } catch (Exception e) {
            LOG.error("[RankWriter] MySQL 写入失败", e);
            SystemStatusWriter.markError("Rank MySQL: " + e.getMessage());
        }
    }

    /** 将一种榜单写入 PreparedStatement（复用同一个 PS） */
    private static int writeRank(PreparedStatement ps, String rankType,
                                  Set<redis.clients.jedis.Tuple> top,
                                  Map<String, String> codeNames, String statTime)
            throws java.sql.SQLException {
        int rank = 0;
        for (redis.clients.jedis.Tuple t : top) {
            rank++;
            String code = t.getElement();
            ps.setString(1, rankType);
            ps.setString(2, code);
            ps.setString(3, codeNames.getOrDefault(code, ""));
            ps.setInt(4, rank);
            ps.setBigDecimal(5, BigDecimal.valueOf(t.getScore()).setScale(2, BigDecimal.ROUND_HALF_UP));
            ps.setString(6, statTime);
            ps.addBatch();
        }
        ps.executeBatch();
        return rank;
    }
}
