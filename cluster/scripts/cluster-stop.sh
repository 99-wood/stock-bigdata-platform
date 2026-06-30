#!/bin/bash
# ============================================================
# 集群服务停止（按依赖倒序）
# 用法: 在 master0 上执行
#       bash cluster/scripts/cluster-stop.sh
# ============================================================
set -e

THIS_HOST=$(hostname)
MID_HOST=${MID_HOST:-mid}

do_cmd() {
    local host=$1
    local cmd=$2
    if [ "$host" = "$THIS_HOST" ]; then
        bash -c "$cmd"
    else
        ssh "root@$host" "$cmd"
    fi
}

echo "=========================================="
echo "  集群服务停止"
echo "=========================================="

# --- master0: Spark ---
echo ""
echo "[master0] Spark..."
source /etc/profile
$SPARK_HOME/sbin/stop-all.sh
echo "  OK"

# --- master0: YARN ---
echo "[master0] YARN..."
stop-yarn.sh
echo "  OK"

# --- master0: HDFS ---
echo "[master0] HDFS..."
stop-dfs.sh
echo "  OK"

# --- mid: 中间件 ---
echo ""
if do_cmd "$MID_HOST" 'hostname' >/dev/null 2>&1; then
    echo "[mid] Redis..."
    do_cmd "$MID_HOST" 'systemctl stop redis-server'
    echo "  OK"

    echo "[mid] MySQL..."
    do_cmd "$MID_HOST" 'systemctl stop mysql'
    echo "  OK"

    echo "[mid] Kafka..."
    do_cmd "$MID_HOST" 'export JAVA_HOME=/root/jdk1.8.0_171 && /root/kafka/bin/kafka-server-stop.sh'
    echo "  OK"

    echo "[mid] Zookeeper..."
    do_cmd "$MID_HOST" '/root/kafka/bin/zookeeper-server-stop.sh'
    echo "  OK"
else
    echo "[mid] 不可达，跳过中间件停止（请手动操作 mid 节点）"
fi

echo ""
echo "=========================================="
echo "  全部服务已停止"
echo "=========================================="
