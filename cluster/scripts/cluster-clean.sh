#!/bin/bash
# ============================================================
# 集群数据归零脚本
# 用法: bash cluster-clean.sh [--kafka] [--no-mysql]
#       --kafka    同时重置 Kafka offset 为 0（不删数据，只回位）
#       --no-mysql 跳过 MySQL truncate（daily-replay 专用）
# 说明: 清空 Redis / MySQL / HDFS，可选重置 Kafka offset
#       执行前请确保 consumer 和 data_gen 已停止
# ============================================================

RESET_KAFKA=false
NO_MYSQL=false
while [ $# -gt 0 ]; do
    case "$1" in
        --kafka)   RESET_KAFKA=true ;;
        --no-mysql) NO_MYSQL=true ;;
    esac
    shift
done

echo "=========================================="
echo "  集群数据归零"
[ "$RESET_KAFKA" = true ] && echo "  (含 Kafka offset 归零)"
echo "=========================================="
echo ""

# ---- 1. 检查 consumer 是否还在运行 ----
RUNNING=$(ps aux | grep "SparkSubmit.*QuoteStreamingJob" | grep -v grep)
if [ -n "$RUNNING" ]; then
    echo "[错误] 消费端仍在运行, 请先执行 consumer-stop.sh"
    exit 1
fi
echo "[确认] 消费端已停止"
echo ""

# ---- 2. 清空 Redis (FLUSHDB 秒级完成) ----
echo "  清空 Redis..."
ssh mid 'redis-cli -a 1 FLUSHDB 2>/dev/null'
echo "  ✓ Redis 已清空"

# ---- 3. 清空 MySQL ----
if [ "$NO_MYSQL" = true ]; then
    echo "  跳过 MySQL (--no-mysql)"
else
    echo "  清空 MySQL..."
    ssh mid \
        'mysql -ustock_admin -pstock2026 stock_ads -e "
           TRUNCATE TABLE ads_market_summary;
           TRUNCATE TABLE ads_stock_rank;
           TRUNCATE TABLE dws_stock_minute;
           TRUNCATE TABLE dws_stock_day;
         " 2>/dev/null
         echo "  ✓ MySQL 4 表已清空"'
fi

# ---- 4. 清空 HDFS ----
echo "  清空 HDFS..."
source /etc/profile
hdfs dfsadmin -safemode wait 2>/dev/null  # 等 NameNode 退出安全模式
hdfs dfs -rm -r -f /stock/checkpoint /stock/ods /stock/dwd /stock/dws
echo "  ✓ HDFS /stock/* 已清空"

# ---- 5. Kafka offset 归零 (可选) ----
if [ "$RESET_KAFKA" = true ]; then
    echo "  重置 Kafka offset → 0..."
    ssh mid 'export JAVA_HOME=/root/jdk1.8.0_171 && /root/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server mid:9092 \
        --group stock_streaming_consumer_v2 \
        --reset-offsets --to-earliest \
        --topic stock_quote_raw \
        --execute 2>/dev/null'
    echo "  ✓ Kafka offset 已归零"
fi

echo ""
echo "=========================================="
echo "  [完成] Redis / MySQL / HDFS 已归零"
[ "$RESET_KAFKA" = true ] && echo "  Kafka offset = 0"
echo "=========================================="
