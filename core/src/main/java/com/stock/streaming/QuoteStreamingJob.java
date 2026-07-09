package com.stock.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.batch.MarketFlusher;
import com.stock.common.Config;
import com.stock.common.OdsRecord;
import com.stock.common.RedisUtil;
import com.stock.common.StockQuote;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
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
 * <pre>
 * 数据流:
 *   Kafka(stock_quote_raw) → 解析JSON → 过滤脏数据 → 计算涨跌幅
 *   → dim_stock 维表更新 / MarketDataWriter(Redis+MySQL) / HDFS ODS+DWD 落盘
 *
 * 启动模式:
 *   正常: auto.offset.reset=latest, 消费新数据
 *   --replay: auto.offset.reset=earliest + 重置Kafka offset → 从头消费（调试用）
 *   --date YYYY-MM-DD: 只处理指定日期（需配合 --replay，daily-replay 专用）
 *
 * 优雅停止:
 *   1. consumer-stop.sh 创建 /tmp/stock-consumer-stop 标记文件
 *   2. 下个 batch 开始时检测到标记 → flush Redis → MySQL → 置 shutdownRequested=true
 *   3. 后台守护线程检测到标记 → 从外部调用 ssc.stop()（避免 foreachRDD 内死锁）
 * </pre>
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

    private static boolean replayMode = false;
    /** 只处理指定日期的数据（null=全部处理） */
    private static String targetDate = null;

    public static void main(String[] args) throws InterruptedException {

        for (int i = 0; i < args.length; i++) {
            if ("--replay".equals(args[i])) {
                replayMode = true;
            } else if ("--date".equals(args[i]) && i + 1 < args.length) {
                targetDate = args[++i];
            }
        }

        // ---- 0. Redis 初始化 ----
        if (Config.flushdbOnStart()) {
            try (redis.clients.jedis.Jedis jedis = RedisUtil.newJedis()) {
                jedis.flushDB();
                LOG.info("[QuoteStreamingJob] Redis FLUSHDB 完成");
            } catch (Exception e) {
                LOG.warn("[QuoteStreamingJob] Redis FLUSHDB 失败: {}", e.getMessage());
            }
        } else {
            LOG.info("[QuoteStreamingJob] 跳过 FLUSHDB (feature.redis.flushdb.onstart=false)");
        }

        // ---- 系统状态 ----
        SystemStatusWriter.markStartup(replayMode ? "replay" : "normal", targetDate);

        // ---- 1. StreamingContext（去 checkpoint，offset 由 Kafka 管理） ----
        SparkConf conf = new SparkConf()
                .setAppName("QuoteStreamingJob")
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .set("spark.streaming.stopGracefullyOnShutdown", "true");

        JavaStreamingContext ssc = createContext(conf);
        ssc.sparkContext().setLogLevel("INFO");

        streamingContext = ssc;
        startShutdownMonitor();

        // ---- 2. 启动 ----
        ssc.start();
        String mode = replayMode ? "replay" : "正常";
        if (targetDate != null) mode += ", date=" + targetDate;
        LOG.info("[QuoteStreamingJob] 启动: {}模式, 等待数据...", mode);
        ssc.awaitTermination();
    }

    private static JavaStreamingContext createContext(SparkConf conf) {
        JavaStreamingContext ssc = new JavaStreamingContext(
                conf,
                Durations.seconds(Config.batchDurationSeconds())
        );

        // ---- Kafka 配置 ----
        Map<String, Object> kafkaParams = new HashMap<>();
        kafkaParams.put("bootstrap.servers", Config.kafkaBootstrapServers());
        kafkaParams.put("key.deserializer", StringDeserializer.class);
        kafkaParams.put("value.deserializer", StringDeserializer.class);
        kafkaParams.put("group.id", Config.kafkaGroupId());
        kafkaParams.put("auto.offset.reset", replayMode ? "earliest" : "latest");
        kafkaParams.put("enable.auto.commit", false);

        Collection<String> topics = Collections.singletonList(Config.kafkaTopic());

        JavaInputDStream<ConsumerRecord<String, String>> stream = KafkaUtils.createDirectStream(
                ssc,
                LocationStrategies.PreferConsistent(),
                ConsumerStrategies.Subscribe(topics, kafkaParams)
        );

        // ---- 核心处理: 每个 batch 执行一次 ----
        stream.foreachRDD(rdd -> {
            // ============================================================
            // 优雅停止检测
            // ============================================================
            if (isShutdownRequested()) {
                LOG.info("[QuoteStreamingJob] 检测到 shutdown marker, 开始 flush...");
                long t0 = System.currentTimeMillis();
                try (redis.clients.jedis.Jedis jedis = RedisUtil.newJedis()) {
                    MarketFlusher.flushAll(jedis);
                } catch (Exception e) {
                    LOG.error("[QuoteStreamingJob] shutdown flush 失败", e);
                }
                LOG.info("[QuoteStreamingJob] shutdown flush 完成, elapsed={}ms",
                        System.currentTimeMillis() - t0);
                shutdownRequested = true;
                return;
            }

            if (rdd.isEmpty()) return;

            long batchStart = System.currentTimeMillis();
            // 用 Kafka offset range 算 raw count，不触发额外 Spark action
            OffsetRange[] batchOffsets = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
            long rddCount = 0;
            for (OffsetRange r : batchOffsets) rddCount += r.count();
            int partitions = rdd.getNumPartitions();
            LOG.info("[QuoteStreamingJob] batch 开始: records={}, partitions={}", rddCount, partitions);

            // ============================================================
            // 1. ODS 层: 原始 JSON → HDFS
            // ============================================================
            SparkSession spark = SparkSession.builder()
                    .sparkContext(rdd.context())
                    .getOrCreate();
            if (Config.odsEnabled()) {
                long t1 = System.currentTimeMillis();
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
                LOG.info("[QuoteStreamingJob] ODS 完成, elapsed={}ms", System.currentTimeMillis() - t1);
            }

            // ============================================================
            // 2. 解析 + 过滤
            // ============================================================
            long t2 = System.currentTimeMillis();
            JavaRDD<StockQuote> parsedRDD = rdd.map(ConsumerRecord::value)
                    .map(QuoteStreamingJob::parseAndCalc)
                    .filter(Objects::nonNull)
                    .filter(q -> q.getCode() != null && !q.getCode().isEmpty())
                    .filter(q -> q.getName() != null && !q.getName().isEmpty())
                    .filter(q -> q.getPrice() > 0)
                    .filter(q -> q.getPrevClose() > 0);

            final String filterDate = targetDate;
            if (filterDate != null) {
                parsedRDD = parsedRDD.filter(q -> filterDate.equals(q.getTradeDate()));
            }
            parsedRDD = parsedRDD.cache();

            try {
                long validCount = parsedRDD.count();
                if (parsedRDD.isEmpty()) {
                    LOG.info("[QuoteStreamingJob] 本批次无有效数据, 提交 offset (空批次)");
                    OffsetRange[] off = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
                    ((CanCommitOffsets) stream.inputDStream()).commitAsync(off,
                            (o, ex) -> {
                                if (ex != null) LOG.error("[QuoteStreamingJob] offset 提交失败: {}", ex.getMessage());
                            });
                    return;
                }
                LOG.info("[QuoteStreamingJob] 解析完成: raw={}, valid={}, elapsed={}ms",
                        rddCount, validCount, System.currentTimeMillis() - t2);

                // 样本日志
                List<StockQuote> sample = parsedRDD.take(3);
                if (LOG.isDebugEnabled()) {
                    sample.forEach(q -> LOG.debug("[QuoteStreamingJob] 样本: {}", q));
                }

                Dataset<Row> quoteDF = spark.createDataFrame(parsedRDD, StockQuote.class);

                // ---- 2a. 维表 ----
                DimWriter.updateDimStock(spark, quoteDF);

                // ---- 2b. Redis 实时缓存 ----
                MarketDataWriter.write(parsedRDD);

                // ---- 2c. 实时榜单 ----
                long tRank = System.currentTimeMillis();
                RankWriter.update();
                LOG.info("[QuoteStreamingJob] 榜单更新完成, elapsed={}ms",
                        System.currentTimeMillis() - tRank);

                // ---- 2d. HDFS DWD ----
                if (Config.dwdEnabled()) {
                    long tDwd = System.currentTimeMillis();
                    quoteDF.write()
                            .mode("append")
                            .partitionBy("tradeDate")
                            .parquet(Config.hdfsUri() + Config.hdfsDwdPath());
                    LOG.info("[QuoteStreamingJob] DWD 完成, elapsed={}ms", System.currentTimeMillis() - tDwd);
                }

                // ---- 提交 offset（带回调，防止静默失败导致重启后重复消费） ----
                OffsetRange[] offsetRanges = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
                ((CanCommitOffsets) stream.inputDStream()).commitAsync(offsetRanges,
                        (offsets, ex) -> {
                            if (ex != null) {
                                LOG.error("[QuoteStreamingJob] offset 提交失败: {}", ex.getMessage());
                            }
                        });

                LOG.info("[QuoteStreamingJob] batch 完成: totalElapsed={}ms",
                        System.currentTimeMillis() - batchStart);

                // 更新系统状态（取样本中的日期）
                String curDate = sample.isEmpty() ? null : sample.get(0).getTradeDate();
                SystemStatusWriter.markBatch(curDate, System.currentTimeMillis() - batchStart);

            } catch (Exception e) {
                LOG.error("[QuoteStreamingJob] batch 处理失败", e);
                SystemStatusWriter.markError("batch: " + e.getMessage());
            } finally {
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

    /**
     * 启动 shutdown-monitor 守护线程
     *
     * 两阶段停止:
     *   1. foreachRDD 检测到 marker 文件 → flush Redis→MySQL → 设置 shutdownRequested=true → return
     *   2. 本线程检测到 shutdownRequested → 从外部调用 ssc.stop(true, true) 优雅停止
     *
     * 为什么不在 foreachRDD 内直接 stop? Spark 2.4 DStream 的 foreachRDD 在 job 线程
     * 内执行，调用 ssc.stop() 会尝试等待自身完成 → 死锁。必须由外部线程调用。
     */
    private static void startShutdownMonitor() {
        Thread monitor = new Thread(() -> {
            while (!shutdownRequested) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
            if (streamingContext != null) {
                LOG.info("[QuoteStreamingJob] shutdown monitor 检测到停止标记, 正在优雅停止...");
                streamingContext.stop(true, true); // stopSparkContext=true, stopGracefully=true
            }
        }, "shutdown-monitor");
        monitor.setDaemon(true); // JVM 退出时自动终止
        monitor.start();
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
