#!/bin/bash
# ============================================================
# Spark Streaming 消费端启动（后台运行）
# 用法: bash consumer-start.sh
# ============================================================

APP_NAME="QuoteStreamingJob"
APP_DIR="/root/stock-app"
LOG_FILE="$APP_DIR/spark-job.log"
JAR_FILE="$APP_DIR/stock-bigdata-core-1.0-SNAPSHOT.jar"
MYSQL_JAR="$APP_DIR/mysql-connector-java-5.1.47.jar"

echo "=========================================="
echo "  Spark Streaming 消费端启动"
echo "=========================================="

# ---- 1. 检查是否已运行 ----
# fix: spark-submit 没有 --status 子命令, 改用 ps 检测
RUNNING=$(ps aux | grep "SparkSubmit.*$APP_NAME" | grep -v grep)
if [ -n "$RUNNING" ]; then
    echo "[跳过] $APP_NAME 可能已在运行中"
    echo "$RUNNING"
    exit 0
fi

# ---- 2. 检查 jar ----
if [ ! -f "$JAR_FILE" ]; then
    echo "[错误] jar 包不存在: $JAR_FILE"
    exit 1
fi

# ---- 3. 备份旧日志 ----
if [ -f "$LOG_FILE" ]; then
    BACKUP="$LOG_FILE.$(date +%Y%m%d_%H%M%S)"
    mv "$LOG_FILE" "$BACKUP"
    echo "[备份] 旧日志 → $BACKUP"
fi

# ---- 4. 启动 ----
rm -f /tmp/stock-consumer-stop  # 清理旧 shutdown marker
source /etc/profile
nohup spark-submit \
    --class com.stock.streaming.QuoteStreamingJob \
    --master spark://master0:7077 \
    --deploy-mode client \
    --executor-memory 1G \
    --total-executor-cores 6 \
    --driver-class-path "$MYSQL_JAR" --jars "$MYSQL_JAR" \
    "$JAR_FILE" \
    >> "$LOG_FILE" 2>&1 &

echo "  PID: $!"
echo "  日志: $LOG_FILE"
echo ""
echo "  实时查看: tail -f $LOG_FILE"
echo "  Spark UI: http://master0:8080"
echo "=========================================="
