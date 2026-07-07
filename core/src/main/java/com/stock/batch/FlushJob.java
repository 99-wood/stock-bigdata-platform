package com.stock.batch;

import com.stock.common.Config;
import com.stock.common.RedisKeys;
import com.stock.common.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

/**
 * Redis → MySQL 持久化 + 清理（独立任务，consumer 退出后执行）
 *
 * <pre>
 * java -cp .../stock-bigdata-core-1.0-SNAPSHOT.jar com.stock.batch.FlushJob [--date YYYY-MM-DD]
 * </pre>
 */
public class FlushJob {

    private static final Logger LOG = LoggerFactory.getLogger(FlushJob.class);

    public static void main(String[] args) {
        Config.load();

        String date = null;
        for (int i = 0; i < args.length; i++) {
            if ("--date".equals(args[i]) && i + 1 < args.length) {
                date = args[++i];
            }
        }

        LOG.info("[FlushJob] 开始, date={}, redis={}:{}",
                date != null ? date : "all", Config.redisHost(), Config.redisPort());

        long t0 = System.currentTimeMillis();
        try (Jedis jedis = RedisUtil.newJedis()) {

            long minuteWindows = jedis.scard(RedisKeys.MINUTE_WINDOWS);
            long ohlcvCodes   = jedis.scard(RedisKeys.OHLCV_CODES);
            LOG.info("[FlushJob] Redis 当前状态: minuteWindows={}, ohlcvCodes={}",
                    minuteWindows, ohlcvCodes);

            if (date != null) {
                MarketFlusher.flushAll(jedis, date);
            } else {
                MarketFlusher.flushAll(jedis);
            }
            long t1 = System.currentTimeMillis();
            LOG.info("[FlushJob] flush 完成, elapsed={}ms", t1 - t0);

            MarketFlusher.clearMinuteKeys(jedis);
            MarketFlusher.clearOhlcvKeys(jedis);
            jedis.del(RedisKeys.MARKET_SUMMARY);
            LOG.info("[FlushJob] Redis key 清理完成");

        } catch (Exception e) {
            LOG.error("[FlushJob] 执行失败", e);
            System.exit(1);
        }

        long total = System.currentTimeMillis() - t0;
        LOG.info("[FlushJob] 完成, totalElapsed={}ms", total);
    }
}
