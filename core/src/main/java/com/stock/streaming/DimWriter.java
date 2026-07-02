package com.stock.streaming;

import com.stock.common.Config;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.api.java.JavaSparkContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * dim_stock 维表写入 —— 新股票代码去重写入
 *
 * 通过广播变量 + LEFT ANTI JOIN 实现 INSERT IGNORE 语义：
 * 缓存 MySQL dim_stock 的 code 集合，每 5 分钟刷新一次，避免每批次全表扫描
 */
public final class DimWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DimWriter.class);

    /** 广播缓存：dim_stock 中已存在的 code 集合 */
    private static Broadcast<Set<String>> cachedCodes = null;

    /** 上次缓存刷新时间 */
    private static long lastRefreshTime = 0;

    /** 缓存刷新间隔（毫秒） */
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;

    private DimWriter() {
    }

    static {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOG.error("MySQL JDBC 驱动加载失败", e);
        }
    }

    public static void updateDimStock(SparkSession spark, Dataset<Row> quoteDF) {
        Properties props = new Properties();
        props.setProperty("user", Config.mysqlUser());
        props.setProperty("password", Config.mysqlPassword());

        // 1. 提取 code + name，按 code 去重，推导 market
        quoteDF.select("code", "name").dropDuplicates("code").createOrReplaceTempView("tmp_new_codes");

        Dataset<Row> newDimDF = spark.sql(
                "SELECT code, name, " +
                "  CASE " +
                "    WHEN LOWER(code) LIKE 'sh%' THEN 'sh' " +
                "    WHEN LOWER(code) LIKE 'sz%' THEN 'sz' " +
                "    WHEN LOWER(code) LIKE 'bj%' THEN 'bj' " +
                "    ELSE 'unknown' " +
                "  END AS market " +
                "FROM tmp_new_codes"
        );

        // 2. 获取已存在的 code 集合（fix #11: 广播缓存，5 分钟刷新一次）
        Set<String> existingSet = getExistingCodes(spark, props);

        // 3. 内存去重：排除已存在的 code
        List<Row> newRows = newDimDF.collectAsList().stream()
                .filter(row -> !existingSet.contains(row.getString(0)))
                .collect(Collectors.toList());

        if (newRows.isEmpty()) {
            LOG.info("dim_stock 无新增股票代码");
            return;
        }

        Dataset<Row> toInsert = spark.createDataFrame(newRows, newDimDF.schema()).cache();

        long newCount = toInsert.count();
        toInsert.write()
                .mode("append")
                .jdbc(Config.mysqlUrl(), "dim_stock", props);

        LOG.info("dim_stock 维表更新完成, 新增 {} 条", newCount);

        // 新增的 code 加入本地缓存，避免广播重建
        newRows.stream().map(r -> r.getString(0)).forEach(existingSet::add);

        toInsert.unpersist();
    }

    /**
     * 获取 dim_stock 中已存在的 code 集合
     * 使用广播变量缓存，每 5 分钟从 MySQL 刷新一次
     */
    private static Set<String> getExistingCodes(SparkSession spark, Properties props) {
        long now = System.currentTimeMillis();
        if (cachedCodes != null && (now - lastRefreshTime) < REFRESH_INTERVAL_MS) {
            return cachedCodes.getValue();
        }

        // 全量刷新
        LOG.info("刷新 dim_stock 广播缓存...");
        Set<String> codes;
        try {
            codes = spark.read()
                    .jdbc(Config.mysqlUrl(), "dim_stock", props)
                    .select("code")
                    .collectAsList().stream()
                    .map(r -> r.getString(0))
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            LOG.error("读取 dim_stock 失败, 使用空缓存", e);
            codes = new HashSet<>();
        }

        // 销毁旧广播，创建新广播
        if (cachedCodes != null) {
            cachedCodes.destroy();
        }
        cachedCodes = JavaSparkContext.fromSparkContext(spark.sparkContext()).broadcast(codes);
        lastRefreshTime = now;
        LOG.info("dim_stock 广播缓存刷新完成, {} 条", codes.size());
        return codes;
    }
}
