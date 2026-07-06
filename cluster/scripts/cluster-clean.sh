#!/bin/bash
# ============================================================
# 集群数据归零脚本
# 用法: bash cluster-clean.sh
# 说明: 清空 Redis OHLCV + 市场汇总、MySQL ads_market_summary、HDFS /stock/*
#       执行前请确保 consumer 和 data_gen 已停止
# ============================================================

echo "=========================================="
echo "  集群数据归零"
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

# ---- 2. 清空 Redis ----
echo "  清空 Redis..."
ssh mid \
    'redis-cli -a 1 DEL stock:market:summary 2>/dev/null
     for k in $(redis-cli -a 1 KEYS "stock:quote:ohlcv:*" 2>/dev/null); do
         redis-cli -a 1 DEL "$k" 2>/dev/null
     done
     echo "  ✓ Redis 已清空"'

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
hdfs dfs -rm -r /stock/checkpoint /stock/ods /stock/dwd /stock/dws 2>/dev/null
echo "  ✓ HDFS /stock/* 已清空"

echo ""
echo "=========================================="
echo "  [完成] Redis / MySQL / HDFS 已归零"
echo "=========================================="
