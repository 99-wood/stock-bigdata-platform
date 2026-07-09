#!/bin/bash
# ============================================================
# 全量回放脚本 — 一次消费所有数据，日清自动分天 flush MySQL
# 用法: bash full-replay.sh
#
# 流程:
#   1. cluster-clean.sh --kafka --no-mysql (归零 Redis/HDFS/Kafka offset)
#   2. consumer --replay (消费全部，日清自动 flush)
#   3. 等 Kafka lag=0
#   4. consumer-stop (优雅停止，shutdown flush 最后一天)
#   5. FlushJob --date $TODAY (兜底 flush 最后一天残留)
# ============================================================

set -e

source /etc/profile

APP_DIR="/root/stock-app"
JAR="$APP_DIR/stock-bigdata-core-1.0-SNAPSHOT.jar"
KAFKA_GROUP="stock_streaming_consumer_v2"
MID_HOST="mid"
SCRIPT_DIR="$(dirname "$0")"

echo "=========================================="
echo "  全量回放 (一次消费所有天)"
echo "=========================================="

# ---- 1. 归零 ----
echo "[1/5] 归零 (Redis + HDFS + Kafka offset)..."
bash "$SCRIPT_DIR/cluster-clean.sh" --kafka --no-mysql
echo ""

# ---- 2. 启动 consumer (不加 --date，消费全部) ----
echo "[2/5] 启动 consumer (replay 全量)..."
bash "$SCRIPT_DIR/consumer-start.sh" --replay
sleep 10

# ---- 3. 等待消费完成 ----
echo "[3/5] 等待消费完成 (Kafka lag=0)..."
ITER=0
while true; do
    ITER=$((ITER + 1))
    KAFA_OUT=$(ssh "$MID_HOST" "export JAVA_HOME=/root/jdk1.8.0_171 && \
        /root/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server mid:9092 --group $KAFKA_GROUP \
        --describe 2>/dev/null")
    TOTAL=$(echo "$KAFA_OUT" | awk '/stock_quote_raw/ {total+=$4} END {print total}')
    LAG=$(echo "$KAFA_OUT"   | awk '/stock_quote_raw/ {lag+=$5} END {print lag}')
    [ -z "$LAG" ] && LAG=-1
    [ -z "$TOTAL" ] && TOTAL=1
    PCT=$(( ($TOTAL - $LAG) * 100 / $TOTAL ))
    echo "  [$ITER] 当前 lag: $LAG ($PCT%)"

    # 更新 Redis 系统状态
    redis-cli -a 1 -h "$MID_HOST" HSET stock:system:status consumer_lag "$LAG" consumer_pct "$PCT" 2>/dev/null

    if [ "$LAG" -le 0 ] 2>/dev/null; then
        echo "  lag=0, 消费完成"
        break
    fi
    sleep 15
done

# ---- 4. 停 consumer ----
echo "[4/5] 停止 consumer (shutdown flush)..."
bash "$SCRIPT_DIR/consumer-stop.sh"

# ---- 5. 兜底 flush (处理最后一天可能残留的数据) ----
echo "[5/5] 兜底 FlushJob..."
java -cp "$APP_DIR/mysql-connector-java-5.1.47.jar:$JAR" \
    com.stock.batch.FlushJob
echo ""

echo "=========================================="
echo "  全量回放完成"
echo "=========================================="
