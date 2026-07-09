#!/bin/bash
# ============================================================
# 按日回放脚本 — 一天一天串行处理
# 用法: bash daily-replay.sh 2026-07-02 2026-07-03 2026-07-06
#
# 流程:
#   for each date:
#     1. cluster-clean.sh --kafka (归零+offset归零)
#     2. consumer --date $d --replay
#     3. 等 Kafka lag=0 + 手动/超时停 consumer
#     4. java FlushJob: Redis → MySQL + 清理 Redis
# ============================================================

set -e

APP_DIR="/root/stock-app"
JAR="$APP_DIR/stock-bigdata-core-1.0-SNAPSHOT.jar"
KAFKA_GROUP="stock_streaming_consumer_v2"
MID_HOST="mid"

if [ $# -eq 0 ]; then
    echo "用法: bash daily-replay.sh 2026-07-02 2026-07-03 ..."
    exit 1
fi

echo "=========================================="
echo "  按日回放: $@"
echo "=========================================="

for DATE in "$@"; do
    echo ""
    echo "=========================================="
    echo "  >>> 开始处理: $DATE"
    echo "=========================================="

    # ---- 1. 归零（保留 MySQL 已 flush 的数据） ----
    echo "[1/4] 归零 (Redis + HDFS + Kafka offset)..."
    bash "$(dirname "$0")/cluster-clean.sh" --kafka --no-mysql
    echo ""

    # ---- 2. 启动 consumer ----
    echo "[2/4] 启动 consumer (date=$DATE, replay)..."
    source /etc/profile
    bash "$(dirname "$0")/consumer-start.sh" --replay --date "$DATE"
    sleep 10

    # ---- 3. 等待消费完成 (最多 120 次 × 15s = 30 分钟超时) ----
    echo "[3/4] 等待消费完成 (Kafka lag=0)..."
    MAX_WAIT=120
    ITER=0
    while [ $ITER -lt $MAX_WAIT ]; do
        ITER=$((ITER + 1))
        LAG=$(ssh "$MID_HOST" "export JAVA_HOME=/root/jdk1.8.0_171 && \
            /root/kafka/bin/kafka-consumer-groups.sh \
            --bootstrap-server mid:9092 --group $KAFKA_GROUP \
            --describe 2>/dev/null" \
            | awk '/stock_quote_raw/ {lag+=$5} END {print lag}')
        # 如果 SSH 失败或 consumer group 尚未创建，LAG 为空 → 设为 -1 触发重试
        [ -z "$LAG" ] && LAG=-1
        echo "  [$ITER/$MAX_WAIT] 当前 lag: $LAG"
        if [ "$LAG" -le 0 ] 2>/dev/null; then
            echo "  lag=0, 消费完成"
            break
        fi
        sleep 15
    done
    if [ $ITER -ge $MAX_WAIT ] && [ "$LAG" -gt 0 ] 2>/dev/null; then
        echo "[错误] 等待超时 ($MAX_WAIT 次), lag=$LAG, 仍继续..."
    fi

    # ---- 3.5 停 consumer ----
    echo "  停止 consumer..."
    bash "$(dirname "$0")/consumer-stop.sh"

    # ---- 4. Flush Redis → MySQL ----
    echo "[4/4] Flush Redis → MySQL (date=$DATE)..."
    source /etc/profile
    java -cp "$APP_DIR/mysql-connector-java-5.1.47.jar:$JAR" \
        com.stock.batch.FlushJob --date "$DATE"
    echo ""

    echo "  >>> $DATE 完成"
done

echo ""
echo "=========================================="
echo "  全部回放完成"
echo "=========================================="
