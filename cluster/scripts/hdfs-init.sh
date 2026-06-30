#!/bin/bash
# ============================================================
# HDFS 目录初始化
# 用法: 在 master0 上执行
#       bash cluster/scripts/hdfs-init.sh
# ============================================================
set -e

source /etc/profile

echo "=========================================="
echo "  HDFS 目录初始化"
echo "=========================================="

# 目录定义: 路径 权限 说明
declare -A DIRS
DIRS["/stock"]="755 根目录"
DIRS["/stock/ods/quote_raw"]="755 原始行情归档（保留 7 天）"
DIRS["/stock/dwd/quote_clean"]="755 清洗明细（保留 30 天）"
DIRS["/stock/dws/stock_minute"]="755 分钟聚合"
DIRS["/stock/dws/stock_day"]="755 日级汇总"
DIRS["/stock/checkpoint/streaming"]="755 Spark Streaming 断点恢复"

for path in "/stock" \
    "/stock/ods" "/stock/ods/quote_raw" \
    "/stock/dwd" "/stock/dwd/quote_clean" \
    "/stock/dws" "/stock/dws/stock_minute" "/stock/dws/stock_day" \
    "/stock/checkpoint" "/stock/checkpoint/streaming"; do

    if hdfs dfs -test -d "$path" 2>/dev/null; then
        echo "[SKIP] $path (已存在)"
    else
        echo "[CREATE] $path"
        hdfs dfs -mkdir -p "$path"
        echo "  OK"
    fi
done

echo ""
echo "=========================================="
echo "  当前 HDFS 目录结构:"
echo "=========================================="
hdfs dfs -ls -R /stock 2>/dev/null || echo "(空)"

echo ""
echo "初始化完成。"
