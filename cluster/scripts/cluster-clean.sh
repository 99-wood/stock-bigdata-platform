#!/bin/bash
# ============================================================
# 集群数据归零脚本
# 用法: bash cluster-clean.sh [--kafka]
#       --kafka  同时重置 Kafka offset 为 0（不删数据，只回位）
# 说明: 清空 Redis / MySQL / HDFS，可选重置 Kafka offset
#       执行前请确保 consumer 和 data_gen 已停止
# ============================================================

RESET_KAFKA=false
if [ "$1" = "--kafka" ]; then
    RESET_KAFKA=true
fi

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
echo "  清空 MySQL..."
ssh mid \
    'mysql -ustock_admin -pstock2026 stock_ads -e "
       TRUNCATE TABLE ads_market_summary;
       TRUNCATE TABLE dws_stock_minute;
       TRUNCATE TABLE dws_stock_day;
     " 2>/dev/null
     echo "  ✓ MySQL ads_market_summary / dws_stock_minute / dws_stock_day 已清空"'

# ---- 4. 清空 HDFS ----
echo "  清空 HDFS..."
source /etc/profile
hdfs dfs -rm -r -f /stock/checkpoint /stock/ods /stock/dwd /stock/dws 2>/dev/null
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
