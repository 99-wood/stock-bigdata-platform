package com.stock.streaming;

import com.stock.common.Config;
import com.stock.common.StockQuote;
import org.apache.spark.api.java.JavaRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 股票排行榜 —— Redis ZSET
 *
 * 每批次全量刷新三个榜单：
 *   stock:rank:up     — 涨幅榜（score = changePct，正值）
 *   stock:rank:down   — 跌幅榜（score = changePct，负值）
 *   stock:rank:amount — 成交额榜（score = amount，万元）
 *
 * 读取方: platform-web API → RankItemDTO（ZREVRANGE/ZRANGE 取 Top N）
 */
public final class RankWriter {

    private static final Logger LOG = LoggerFactory.getLogger(RankWriter.class);

    private static final String KEY_RANK_UP     = "stock:rank:up";
    private static final String KEY_RANK_DOWN   = "stock:rank:down";
    private static final String KEY_RANK_AMOUNT = "stock:rank:amount";

    /** 每个榜单保留前 100 名 */
    private static final int TOP_N = 100;

    private RankWriter() {
    }

    /**
     * 从本批次行情数据中提取排行，写入 Redis ZSET
     */
    public static void write(JavaRDD<StockQuote> parsedRDD) {
        List<StockQuote> quotes = parsedRDD.collect();
        if (quotes.isEmpty()) {
            return;
        }

        try (Jedis jedis = new Jedis(Config.redisHost(), Config.redisPort(), 3000)) {
            jedis.auth(Config.redisPassword());

            // 1. 涨幅榜
            writeUpRank(jedis, quotes);

            // 2. 跌幅榜
            writeDownRank(jedis, quotes);

            // 3. 成交额榜
            writeAmountRank(jedis, quotes);

            LOG.info("排行榜刷新完成");
        } catch (Exception e) {
            LOG.warn("排行榜 Redis 写入失败: {}", e.getMessage());
        }
    }

    /** 涨幅榜: 按 changePct 降序，取正值的 TOP N */
    private static void writeUpRank(Jedis jedis, List<StockQuote> quotes) {
        List<StockQuote> up = quotes.stream()
                .filter(q -> q.getChangePct() > 0)
                .sorted(Comparator.comparingDouble(StockQuote::getChangePct).reversed())
                .limit(TOP_N)
                .collect(Collectors.toList());

        jedis.del(KEY_RANK_UP);
        for (StockQuote q : up) {
            // amount 元 → 万元（与 REDIS_SCHEMA §3.5 一致）
            jedis.zadd(KEY_RANK_UP, q.getChangePct(), q.getCode());
        }
        LOG.info("涨幅榜: {} 只上榜", up.size());
    }

    /** 跌幅榜: 按 changePct 升序（负值最小 = 跌最多），取负值 TOP N */
    private static void writeDownRank(Jedis jedis, List<StockQuote> quotes) {
        List<StockQuote> down = quotes.stream()
                .filter(q -> q.getChangePct() < 0)
                .sorted(Comparator.comparingDouble(StockQuote::getChangePct))
                .limit(TOP_N)
                .collect(Collectors.toList());

        jedis.del(KEY_RANK_DOWN);
        for (StockQuote q : down) {
            jedis.zadd(KEY_RANK_DOWN, q.getChangePct(), q.getCode());
        }
        LOG.info("跌幅榜: {} 只上榜", down.size());
    }

    /** 成交额榜: 按 amount 降序，amount 元 → 万元做分数 */
    private static void writeAmountRank(Jedis jedis, List<StockQuote> quotes) {
        List<StockQuote> sorted = quotes.stream()
                .sorted(Comparator.comparingDouble(StockQuote::getAmount).reversed())
                .limit(TOP_N)
                .collect(Collectors.toList());

        jedis.del(KEY_RANK_AMOUNT);
        for (StockQuote q : sorted) {
            // amount 元 → 万元
            jedis.zadd(KEY_RANK_AMOUNT, q.getAmount() / 10000.0, q.getCode());
        }
        LOG.info("成交额榜: {} 只上榜", sorted.size());
    }
}
