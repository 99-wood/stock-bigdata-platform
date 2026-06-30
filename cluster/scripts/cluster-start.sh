#!/bin/bash
# ============================================================
# 集群服务启动（按依赖顺序）
# 用法: 在 master0 上执行
#       bash cluster/scripts/cluster-start.sh
# 前置: master0 已配好 SSH 免密到 mid
# ============================================================
set -e

THIS_HOST=$(hostname)
MID_HOST=${MID_HOST:-mid}

# 在目标主机上执行命令（本机直接执行，远程 SSH）
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
echo "  集群服务启动"
echo "=========================================="

# --- mid: 中间件（如果 SSH 可达） ---
echo ""
if do_cmd "$MID_HOST" 'hostname' >/dev/null 2>&1; then
    echo "[mid] Zookeeper..."
    do_cmd "$MID_HOST" '/root/kafka/bin/zookeeper-server-start.sh -daemon /root/kafka/config/zookeeper.properties'
    echo "  OK"

    echo "[mid] Kafka..."
    do_cmd "$MID_HOST" 'export JAVA_HOME=/root/jdk1.8.0_171 && /root/kafka/bin/kafka-server-start.sh -daemon /root/kafka/config/server.properties'
    echo "  OK"

    echo "[mid] Redis..."
    do_cmd "$MID_HOST" 'systemctl start redis-server'
    echo "  OK"

    echo "[mid] MySQL..."
    do_cmd "$MID_HOST" 'systemctl start mysql'
    echo "  OK"
else
    echo "[mid] 不可达，跳过中间件启动（请手动确认 mid 服务状态）"
fi

# --- master0: HDFS ---
echo ""
echo "[master0] HDFS..."
source /etc/profile
start-dfs.sh
echo "  OK"

# --- master0: YARN ---
echo "[master0] YARN..."
start-yarn.sh
echo "  OK"

# --- master0: Spark ---
echo "[master0] Spark..."
$SPARK_HOME/sbin/start-all.sh
echo "  OK"

echo ""
echo "=========================================="
echo "  全部服务启动完毕"
echo "=========================================="
