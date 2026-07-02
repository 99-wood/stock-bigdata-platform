package com.stock.streaming;

import com.stock.common.Config;
import com.stock.common.StockQuote;
import org.apache.spark.api.java.JavaRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

/**
 * 个股实时快照 —— Redis 缓存每只股票最新行情
 *
 * Key: stock:quote:{code}
 * Value: JSON 格式的实时快照
 * TTL: 5 分钟（行情失效自动淘汰）
 */
public final class StockQuoteWriter {

    private static final Logger LOG = LoggerFactory.getLogger(StockQuoteWriter.class);
    private static final int TTL_SECONDS = 0;

    private StockQuoteWriter() {
    }

    /**
     * 将本批次每只股票的最新快照写入 Redis
     */
    public static void write(JavaRDD<StockQuote> parsedRDD) {
        parsedRDD.foreachPartition(iterator -> {
            try (Jedis jedis = new Jedis(Config.redisHost(), Config.redisPort(), 3000)) {
                jedis.auth(Config.redisPassword());
                while (iterator.hasNext()) {
                    StockQuote q = iterator.next();
                    String key = "stock:quote:" + q.getCode();
                    // REDIS_SCHEMA §3.2 v2: code 从 key 提取，name 查 MySQL
                    String json = String.format(
                            "{\"price\":%.2f,\"open\":%.2f,\"high\":%.2f,\"low\":%.2f," +
                            "\"pre_close\":%.2f,\"change\":%.2f,\"change_pct\":%.4f," +
                            "\"volume\":%.0f,\"amount\":%.2f," +
                            "\"trade_date\":\"%s\",\"trade_time\":\"%s\"}",
                            q.getPrice(), q.getOpen(), q.getHigh(), q.getLow(),
                            q.getPrevClose(), q.getChangeAmt(), q.getChangePct(),
                            q.getVolume(), q.getAmount(),
                            q.getTradeDate(), q.getTradeTime()
                    );
                    if (TTL_SECONDS > 0) {
                        jedis.setex(key, TTL_SECONDS, json);
                    } else {
                        jedis.set(key, json);
                    }
                }
            } catch (Exception e) {
                LOG.warn("个股快照 Redis 写入失败: {}", e.getMessage());
            }
        });
    }
}
