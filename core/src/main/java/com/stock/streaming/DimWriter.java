package com.stock.streaming;

import com.stock.common.Config;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * dim_stock 维表写入 —— 新股票代码 INSERT IGNORE 语义
 *
 * 从行情数据中提取 code + name，推导 market（sh/sz/bj），去重后批量写入
 */
public final class DimWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DimWriter.class);

    private DimWriter() {
    }

    static {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOG.error("MySQL JDBC 驱动加载失败", e);
        }
    }

    /**
     * 从行情 DataFrame 中提取新股票代码，INSERT IGNORE 写入 dim_stock
     *
     * 先去重：排除 dim_stock 中已存在的 code，只插入新股票
     */
    public static void updateDimStock(SparkSession spark, Dataset<Row> quoteDF) {
        Properties props = new Properties();
        props.setProperty("user", Config.mysqlUser());
        props.setProperty("password", Config.mysqlPassword());

        // 1. 从行情数据中提取 code + name + market，按 code 去重（同一 code 只保留一条）
        quoteDF.select("code", "name").dropDuplicates("code").createOrReplaceTempView("tmp_new_codes");

        Dataset<Row> newDimDF = spark.sql(
                "SELECT code, name, " +
                "  CASE " +
                "    WHEN code LIKE 'sh%' THEN 'sh' " +
                "    WHEN code LIKE 'sz%' THEN 'sz' " +
                "    WHEN code LIKE 'bj%' THEN 'bj' " +
                "    ELSE 'unknown' " +
                "  END AS market " +
                "FROM tmp_new_codes"
        );

        // 2. 注册新股票视图
        newDimDF.createOrReplaceTempView("new_stocks");

        // 3. 读取 dim_stock 已有 code
        Dataset<Row> existingCodes;
        try {
            existingCodes = spark.read()
                    .jdbc(Config.mysqlUrl(), "dim_stock", props)
                    .select("code");
        } catch (Exception e) {
            existingCodes = spark.emptyDataFrame();
        }

        // 4. 排除已存在的 code（表为空时跳过 JOIN）
        Dataset<Row> toInsert;
        if (existingCodes.isEmpty()) {
            toInsert = newDimDF;
        } else {
            existingCodes.createOrReplaceTempView("existing_stocks");
            toInsert = spark.sql(
                    "SELECT n.code, n.name, n.market " +
                    "FROM new_stocks n " +
                    "LEFT ANTI JOIN existing_stocks e ON n.code = e.code"
            );
        }

        long newCount = toInsert.count();
        if (newCount > 0) {
            toInsert.write()
                    .mode("append")
                    .jdbc(Config.mysqlUrl(), "dim_stock", props);

            LOG.info("dim_stock 维表更新完成, 新增 {} 条", newCount);
        } else {
            LOG.info("dim_stock 无新增股票代码");
        }
    }
}
