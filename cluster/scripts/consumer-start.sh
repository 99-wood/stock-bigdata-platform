#!/bin/bash
# ============================================================
# Spark Streaming 消费端启动
# 用法: bash consumer-start.sh [--replay]
#       --replay  从头消费（Kafka offset 置 earliest）
# ============================================================

APP_NAME="QuoteStreamingJob"
APP_DIR="/root/stock-app"
JAR_FILE="$APP_DIR/stock-bigdata-core-1.0-SNAPSHOT.jar"
MYSQL_JAR="$APP_DIR/mysql-connector-java-5.1.47.jar"

REPLAY=""
if [ "$1" = "--replay" ]; then
    REPLAY="--replay"
    echo "=========================================="
    echo "  Spark Streaming 消费端启动 (REPLAY 模式)"
    echo "=========================================="

    # 重置 Kafka offset 到 earliest（否则 auto.offset.reset 不生效）
    echo "  重置 Kafka offset → 0..."
    ssh mid "export JAVA_HOME=/root/jdk1.8.0_171 && /root/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server mid:9092 \
        --group stock_streaming_consumer_v2 \
        --reset-offsets --to-earliest \
        --topic stock_quote_raw \
        --execute 2>/dev/null"
    echo "  完成"
else
    echo "=========================================="
    echo "  Spark Streaming 消费端启动"
    echo "=========================================="
fi

# ---- 1. 检查是否已运行 ----
RUNNING=$(ps aux | grep "SparkSubmit.*$APP_NAME" | grep -v grep)
if [ -n "$RUNNING" ]; then
    echo "[跳过] $APP_NAME 可能已在运行中"
    exit 0
fi

# ---- 2. 检查 jar ----
if [ ! -f "$JAR_FILE" ]; then
    echo "[错误] jar 包不存在: $JAR_FILE"
    exit 1
fi

# ---- 3. 备份旧日志 ----
if [ -f /tmp/consumer.log ]; then
    cp /tmp/consumer.log /tmp/consumer.log.$(date +%Y%m%d_%H%M%S)
fi

# ---- 4. 启动 ----
rm -f /tmp/stock-consumer-stop
source /etc/profile
nohup spark-submit \
    --class com.stock.streaming.QuoteStreamingJob \
    --master spark://master0:7077 \
    --deploy-mode client \
    --executor-memory 2G \
    --total-executor-cores 6 \
    --driver-class-path "$MYSQL_JAR" \
    --jars "$MYSQL_JAR" \
    --conf spark.streaming.kafka.maxRatePerPartition=2000 \
    "$JAR_FILE" \
    $REPLAY \
    >> /tmp/consumer.log 2>&1 &

echo "  PID: $!"
echo "  日志: /tmp/consumer.log"
echo "  Spark UI: http://master0:8080"
echo "=========================================="
