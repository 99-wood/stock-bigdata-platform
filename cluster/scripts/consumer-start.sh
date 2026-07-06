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

# ---- 3. 启动 ----
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
    --conf spark.streaming.backpressure.enabled=true \
    --conf spark.streaming.kafka.maxRatePerPartition=10000 \
    "$JAR_FILE" \
    $REPLAY \
    > /tmp/consumer.log 2>&1 &

echo "  PID: $!"
echo "  日志: /tmp/consumer.log"
echo "  Spark UI: http://master0:8080"
echo "=========================================="
