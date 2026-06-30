package com.stock.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.common.Config;
import com.stock.common.StockQuote;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Spark Streaming 实时行情消费主入口
 *
 * 数据流:
 *   Kafka(stock_quote_raw) → 解析JSON → 过滤脏数据 → 计算涨跌幅
 *   → dim_stock维表更新
 */
public class QuoteStreamingJob {

    private static final Logger LOG = LoggerFactory.getLogger(QuoteStreamingJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws InterruptedException {

        // ---- 1. SparkConf ----
        SparkConf conf = new SparkConf()
                .setAppName("QuoteStreamingJob")
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .set("spark.streaming.stopGracefullyOnShutdown", "true");

        // ---- 2. StreamingContext（先创建，内部会创建唯一的 SparkContext） ----
        JavaStreamingContext ssc = new JavaStreamingContext(
                conf,
                Durations.seconds(Config.batchDurationSeconds())
        );
        // 开发调试阶段用 INFO，上线后改为 WARN
        ssc.sparkContext().setLogLevel("INFO");

        // Checkpoint：HDFS 持久化 offset，重启后断点恢复
        ssc.checkpoint(Config.hdfsCheckpointPath());

        // ---- 3. SparkSession（复用 StreamingContext 的 SparkContext） ----
        SparkSession spark = SparkSession.builder()
                .sparkContext(ssc.sparkContext().sc())
                .getOrCreate();

        // ---- 4. Kafka 配置 ----
        Map<String, Object> kafkaParams = new HashMap<>();
        kafkaParams.put("bootstrap.servers", Config.kafkaBootstrapServers());
        kafkaParams.put("key.deserializer", StringDeserializer.class);
        kafkaParams.put("value.deserializer", StringDeserializer.class);
        kafkaParams.put("group.id", Config.kafkaGroupId());
        kafkaParams.put("auto.offset.reset", "earliest");
        kafkaParams.put("enable.auto.commit", false);

        Collection<String> topics = Collections.singletonList(Config.kafkaTopic());

        // ---- 5. 创建 DStream ----
        JavaInputDStream<ConsumerRecord<String, String>> stream = KafkaUtils.createDirectStream(
                ssc,
                LocationStrategies.PreferConsistent(),
                ConsumerStrategies.Subscribe(topics, kafkaParams)
        );

        // ---- 6. 核心处理 ----
        stream.foreachRDD(rdd -> {
            if (rdd.isEmpty()) {
                return;
            }

            // 6.1 解析 JSON → StockQuote，过滤脏数据，计算涨跌幅
            JavaRDD<StockQuote> parsedRDD = rdd.map(ConsumerRecord::value)
                    .map(QuoteStreamingJob::parseAndCalc)
                    .filter(Objects::nonNull)
                    .filter(q -> q.getCode() != null && !q.getCode().isEmpty())
                    .filter(q -> q.getPrice() > 0)
                    .filter(q -> q.getPrevClose() > 0);

            if (parsedRDD.isEmpty()) {
                LOG.info("本批次无有效数据");
                return;
            }

            parsedRDD.cache();

            // 打印示例
            List<StockQuote> sample = parsedRDD.take(3);
            sample.forEach(q -> LOG.info("收到行情: {}", q));

            // dim_stock —— 新股票代码 INSERT IGNORE
            Dataset<Row> quoteDF = spark.createDataFrame(parsedRDD, StockQuote.class);
            DimWriter.updateDimStock(spark, quoteDF);

            // 手动提交 offset 到 Kafka，LAG 可视化 + 重启恢复
            OffsetRange[] offsetRanges = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
            ((CanCommitOffsets) stream.inputDStream()).commitAsync(offsetRanges);

            parsedRDD.unpersist();
        });

        // ---- 7. 启动 ----
        ssc.start();
        LOG.info("QuoteStreamingJob 已启动, 等待数据...");
        ssc.awaitTermination();
    }

    /**
     * JSON 解析 + 计算涨跌额/涨跌幅
     * 解析失败或关键字段缺失返回 null（被 filter 过滤）
     */
    private static StockQuote parseAndCalc(String json) {
        try {
            StockQuote quote = MAPPER.readValue(json, StockQuote.class);
            quote.calcChange();
            return quote;
        } catch (Exception e) {
            LOG.warn("JSON 解析失败: {}", json.length() > 200 ? json.substring(0, 200) + "..." : json);
            return null;
        }
    }
}
