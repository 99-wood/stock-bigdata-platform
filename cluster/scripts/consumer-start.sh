#!/bin/bash
# ============================================================
# Spark Streaming 消费端启动
# 用法: bash consumer-start.sh [--replay] [--date YYYY-MM-DD]
# ============================================================

APP_NAME="QuoteStreamingJob"
APP_DIR="/root/stock-app"
JAR_FILE="$APP_DIR/stock-bigdata-core-1.0-SNAPSHOT.jar"
MYSQL_JAR="$APP_DIR/mysql-connector-java-5.1.47.jar"

EXTRA_ARGS=""
MODE="正常"
while [ $# -gt 0 ]; do
    case "$1" in
        --replay)
            EXTRA_ARGS="$EXTRA_ARGS --replay"
            MODE="REPLAY"
            # 重置 Kafka offset
            echo "  重置 Kafka offset → 0..."
            ssh mid "export JAVA_HOME=/root/jdk1.8.0_171 && /root/kafka/bin/kafka-consumer-groups.sh \
                --bootstrap-server mid:9092 --group stock_streaming_consumer_v2 \
                --reset-offsets --to-earliest --topic stock_quote_raw --execute 2>/dev/null"
            echo "  完成"
            ;;
        --date)
            EXTRA_ARGS="$EXTRA_ARGS --date $2"
            MODE="$MODE date=$2"
            shift
            ;;
    esac
    shift
done

echo "=========================================="
echo "  Spark Streaming 消费端启动 ($MODE)"
echo "=========================================="

# ---- check ----
RUNNING=$(ps aux | grep "SparkSubmit.*$APP_NAME" | grep -v grep)
if [ -n "$RUNNING" ]; then
    echo "[跳过] $APP_NAME 可能已在运行中"
    exit 0
fi
[ ! -f "$JAR_FILE" ] && echo "[错误] jar 不存在: $JAR_FILE" && exit 1

# ---- backup log ----
[ -f /tmp/consumer.log ] && cp /tmp/consumer.log /tmp/consumer.log.$(date +%Y%m%d_%H%M%S)

# ---- start ----
rm -f /tmp/stock-consumer-stop
source /etc/profile
nohup spark-submit \
    --class com.stock.streaming.QuoteStreamingJob \
    --master spark://master0:7077 \
    --deploy-mode client \
    --executor-memory 2G \
    --total-executor-cores 6 \
    --driver-class-path "$APP_DIR:$MYSQL_JAR" \
    --jars "$MYSQL_JAR" \
    --conf spark.streaming.kafka.maxRatePerPartition=2000 \
    "$JAR_FILE" \
    $EXTRA_ARGS \
    >> /tmp/consumer.log 2>&1 &

echo "  PID: $!"
echo "  日志: /tmp/consumer.log"
echo "=========================================="
