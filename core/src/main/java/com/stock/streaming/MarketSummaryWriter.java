package com.stock.streaming;

import com.stock.common.Config;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Properties;

/**
 * ads_market_summary — 市场概览
 *
 * 每个 batch 统计全市场涨跌分布，每只股票只计一次
 * 实时写入 Redis（主） + MySQL（兜底）
 */
public final class MarketSummaryWriter {

    private static final Logger LOG = LoggerFactory.getLogger(MarketSummaryWriter.class);
    private static final String REDIS_KEY = "stock:market:summary";

    private MarketSummaryWriter() {
    }

    static {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOG.error("MySQL JDBC 驱动加载失败", e);
        }
    }

    /**
     * 对本批次行情统计市场概览，写入 Redis + MySQL
     *
     * 单位转换:
     *   volume: 股 → 手（÷100）
     *   amount: 元 → 万元（÷10000）
     */
    public static void write(SparkSession spark, Dataset<Row> quoteDF) {
        quoteDF.createOrReplaceTempView("batch_quotes");

        // 子查询: 每只股票只保留一条记录
        Dataset<Row> summary = spark.sql(
                "SELECT " +
                "  COUNT(*)                                                                       AS total_stocks, " +
                "  SUM(CASE WHEN changePct > 0  THEN 1 ELSE 0 END)                               AS up_count, " +
                "  SUM(CASE WHEN changePct < 0  THEN 1 ELSE 0 END)                               AS down_count, " +
                "  SUM(CASE WHEN changePct = 0  THEN 1 ELSE 0 END)                               AS flat_count, " +
                "  ROUND(AVG(changePct), 4)                                                      AS avg_change_pct, " +
                "  CAST(SUM(volume) / 100 AS BIGINT)                                             AS total_volume, " +
                "  ROUND(SUM(amount) / 10000, 4)                                                 AS total_amount, " +
                "  CAST('" + new Timestamp(System.currentTimeMillis()) + "' AS TIMESTAMP)        AS stat_time " +
                "FROM ( " +
                "  SELECT code, MAX(changePct) AS changePct, MAX(volume) AS volume, MAX(amount) AS amount " +
                "  FROM batch_quotes GROUP BY code " +
                ") stock_snapshot"
        );

        // fix #5: first() 只调用一次
        Row r = summary.first();
        long total = r.getLong(0);
        if (total == 0) {
            LOG.info("市场概览: 本批次无有效股票");
            return;
        }

        // 1. 写入 Redis（实时查询，覆盖旧值）
        writeRedis(r);

        // 2. 写入 MySQL（兜底持久化）
        Properties props = new Properties();
        props.setProperty("user", Config.mysqlUser());
        props.setProperty("password", Config.mysqlPassword());

        summary.write()
                .mode("append")
                .jdbc(Config.mysqlUrl(), "ads_market_summary", props);

        LOG.info("市场概览: 总计={}, 涨={}, 跌={}, 平={}, 均涨幅={}%, 总成交量={}手, 总成交额={}万元",
                r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5), r.get(6));
    }

    /**
     * 写入 Redis Hash: stock:market:summary
     * 每次 HSET 覆盖更新（对齐 REDIS_SCHEMA §3.1）
     */
    private static void writeRedis(Row r) {
        try (Jedis jedis = new Jedis(Config.redisHost(), Config.redisPort(), 3000)) {
            jedis.auth(Config.redisPassword());
            jedis.hset(REDIS_KEY, "stat_time", r.getTimestamp(7).toString());
            jedis.hset(REDIS_KEY, "total_stocks", String.valueOf(r.getLong(0)));
            jedis.hset(REDIS_KEY, "up_count", String.valueOf(r.getLong(1)));
            jedis.hset(REDIS_KEY, "down_count", String.valueOf(r.getLong(2)));
            jedis.hset(REDIS_KEY, "flat_count", String.valueOf(r.getLong(3)));
            jedis.hset(REDIS_KEY, "avg_change_pct", String.valueOf(r.getDouble(4)));
            jedis.hset(REDIS_KEY, "total_volume", String.valueOf(r.getLong(5)));
            jedis.hset(REDIS_KEY, "total_amount", String.valueOf(r.getDouble(6)));
            LOG.info("Redis 写入成功");
        } catch (Exception e) {
            LOG.warn("Redis 写入失败: {}", e.getMessage());
        }
    }
}
