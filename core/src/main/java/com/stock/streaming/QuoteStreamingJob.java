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

import java.io.Serializable;
import java.util.*;

/**
 * Spark Streaming 实时行情消费主入口
 *
 * 数据流:
 *   Kafka(stock_quote_raw) → 解析JSON → 过滤脏数据 → 计算涨跌幅
 *   → dim_stock / MarketDataWriter(Redis+MySQL) / HDFS ODS+DWD 落盘
 */
public class QuoteStreamingJob {

    private static final Logger LOG = LoggerFactory.getLogger(QuoteStreamingJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 优雅停止标记文件: 存在则 Streaming 在下个 batch 开始时主动退出 */
    private static final String SHUTDOWN_MARKER = "/tmp/stock-consumer-stop";

    /** StreamingContext 的 static 持有, 避免 lambda 捕获非序列化的 ssc 局部变量 */
    private static volatile JavaStreamingContext streamingContext;

    /** 优雅停止标记: 后台线程检测到此标记后从外部调用 ssc.stop() */
    private static volatile boolean shutdownRequested = false;

    public static void main(String[] args) throws InterruptedException {

        // ---- 1. SparkConf ----
        SparkConf conf = new SparkConf()
                .setAppName("QuoteStreamingJob")
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .set("spark.streaming.stopGracefullyOnShutdown", "true");

        // ---- 2. StreamingContext：getOrCreate 支持 checkpoint 恢复 ----
        String checkpointPath = Config.hdfsCheckpointPath();
        JavaStreamingContext ssc = JavaStreamingContext.getOrCreate(
                checkpointPath,
                () -> createContext(conf, checkpointPath)
        );
        ssc.sparkContext().setLogLevel("INFO");

        // ---- 3. 启动 ----
        ssc.start();
        LOG.info("QuoteStreamingJob 已启动, 等待数据...");
        ssc.awaitTermination();
    }

    private static JavaStreamingContext createContext(SparkConf conf, String checkpointPath) {
        LOG.info("Creating new StreamingContext (checkpoint not found or invalid)");
        JavaStreamingContext ssc = new JavaStreamingContext(
                conf,
                Durations.seconds(Config.batchDurationSeconds())
        );
        ssc.checkpoint(checkpointPath);
        streamingContext = ssc;

        // 后台线程: 每隔 2s 检查 shutdown 标记, 从外部调用 ssc.stop 避免 foreachRDD 内死锁
        Thread shutdownMonitor = new Thread(() -> {
            while (!shutdownRequested) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
            LOG.info("shutdown monitor 检测到停止标记, 正在优雅停止 StreamingContext...");
            streamingContext.stop(true, true);
        }, "shutdown-monitor");
        shutdownMonitor.setDaemon(true);
        shutdownMonitor.start();

        SparkSession spark = SparkSession.builder()
                .sparkContext(ssc.sparkContext().sc())
                .getOrCreate();

        // ---- Kafka 配置 ----
        Map<String, Object> kafkaParams = new HashMap<>();
        kafkaParams.put("bootstrap.servers", Config.kafkaBootstrapServers());
        kafkaParams.put("key.deserializer", StringDeserializer.class);
        kafkaParams.put("value.deserializer", StringDeserializer.class);
        kafkaParams.put("group.id", Config.kafkaGroupId());
        kafkaParams.put("auto.offset.reset", "latest");  // fix #6: 生产用 latest，避免全量重放
        kafkaParams.put("enable.auto.commit", false);

        Collection<String> topics = Collections.singletonList(Config.kafkaTopic());

        JavaInputDStream<ConsumerRecord<String, String>> stream = KafkaUtils.createDirectStream(
                ssc,
                LocationStrategies.PreferConsistent(),
                ConsumerStrategies.Subscribe(topics, kafkaParams)
        );

        // ---- 核心处理 ----
        stream.foreachRDD(rdd -> {
            // 优雅停止: 检查 shutdown marker, 置标记位让后台线程调用 stop
            if (isShutdownRequested()) {
                LOG.info("检测到 shutdown marker, 置停止标记");
                shutdownRequested = true;
                return;
            }

            if (rdd.isEmpty()) {
                return;
            }

            // ODS 层 —— 原始 JSON 归档
            Dataset<Row> rawDF = spark.createDataFrame(
                    rdd.map(r -> {
                        String json = r.value();
                        String td = parseTradeDate(json);
                        return new OdsRecord(td, json);
                    }), OdsRecord.class);
            rawDF.write()
                    .mode("append")
                    .partitionBy("tradeDate")
                    .json(Config.hdfsUri() + Config.hdfsOdsPath());
            LOG.info("ODS 归档完成");

            // 解析 JSON → StockQuote，过滤脏数据，计算涨跌幅
            JavaRDD<StockQuote> parsedRDD = rdd.map(ConsumerRecord::value)
                    .map(QuoteStreamingJob::parseAndCalc)
                    .filter(Objects::nonNull)
                    .filter(q -> q.getCode() != null && !q.getCode().isEmpty())
                    .filter(q -> q.getName() != null && !q.getName().isEmpty())
                    .filter(q -> q.getPrice() > 0)
                    .filter(q -> q.getPrevClose() > 0)
                    .cache();

            try {
                if (parsedRDD.isEmpty()) {
                    LOG.info("本批次无有效数据");
                    return;
                }

                // 打印示例
                List<StockQuote> sample = parsedRDD.take(3);
                sample.forEach(q -> LOG.info("收到行情: {}", q));

                Dataset<Row> quoteDF = spark.createDataFrame(parsedRDD, StockQuote.class);

                // dim_stock —— 新股票代码
                DimWriter.updateDimStock(spark, quoteDF);

                // 市场数据 —— Redis 实时缓存 + MySQL 归档
                MarketDataWriter.write(parsedRDD);

                // HDFS DWD 落盘 —— 离线团队的入口
                quoteDF.write()
                        .mode("append")
                        .partitionBy("tradeDate")
                        .parquet(Config.hdfsUri() + Config.hdfsDwdPath());
                LOG.info("DWD 落盘完成");

                // 提交 offset 到 Kafka
                OffsetRange[] offsetRanges = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
                ((CanCommitOffsets) stream.inputDStream()).commitAsync(offsetRanges);
            } catch (Exception e) {
                LOG.error("Batch processing failed", e);
            } finally {
                // fix #13: unpersist 放在 finally 中，确保异常时也能释放内存
                parsedRDD.unpersist();
            }
        });

        return ssc;
    }

    /** 从 JSON 中提取 tradeDate */
    private static String parseTradeDate(String json) {
        try {
            StockQuote q = MAPPER.readValue(json, StockQuote.class);
            return q.getTradeDate() != null ? q.getTradeDate() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** ODS 层原始归档记录 */
    public static class OdsRecord implements Serializable {
        private String tradeDate;
        private String rawJson;

        public OdsRecord() {}

        public OdsRecord(String tradeDate, String rawJson) {
            this.tradeDate = tradeDate;
            this.rawJson = rawJson;
        }

        public String getTradeDate() { return tradeDate; }
        public void setTradeDate(String tradeDate) { this.tradeDate = tradeDate; }
        public String getRawJson() { return rawJson; }
        public void setRawJson(String rawJson) { this.rawJson = rawJson; }
    }

    /** 检查 shutdown marker 文件是否存在（优雅停止） */
    private static boolean isShutdownRequested() {
        return new java.io.File(SHUTDOWN_MARKER).exists();
    }

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
