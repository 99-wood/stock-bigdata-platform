#!/bin/bash
# ============================================================
# 集群服务状态检查
# 用法: 在 master0 或本地执行
#       bash cluster/scripts/cluster-status.sh
# ============================================================

# 远程主机（从 master0 视角，mid 需要 SSH）
# 如果在 master0 上执行，本机服务直接检查不 SSH
MID_HOST=${MID_HOST:-mid}
THIS_HOST=$(hostname 2>/dev/null || echo "")

# ---------- 工具函数 ----------
# 本地执行或 SSH 到远程执行
run() {
    local host=$1
    local cmd=$2
    if [ "$host" = "localhost" ] || [ "$THIS_HOST" = "$host" ]; then
        bash -c "$cmd" 2>&1
    else
        ssh -o ConnectTimeout=3 "root@$host" "$cmd" 2>&1
    fi
}

check() {
    local host=$1
    local desc=$2
    local cmd=$3
    local result
    result=$(run "$host" "$cmd")
    if [ $? -eq 0 ] && [ -n "$result" ]; then
        echo -e "  \033[32m●\033[0m $desc"
    else
        echo -e "  \033[31m○\033[0m $desc  (stopped)"
    fi
}

echo "=========================================="
echo "  集群服务状态"
echo "=========================================="

echo ""
echo "--- mid (中间件) ---"
check "$MID_HOST" "Zookeeper  (2181)" 'echo stat | nc -w 1 localhost 2181 2>/dev/null | head -1'
check "$MID_HOST" "Kafka      (9092)" 'export JAVA_HOME=/root/jdk1.8.0_171 && ss -tlnp | grep -q 9092 && echo ok'
check "$MID_HOST" "Redis      (6379)" 'redis-cli -a " " PING 2>/dev/null | grep -q PONG && echo ok'
check "$MID_HOST" "MySQL      (3306)" 'mysqladmin -uroot -p" " ping 2>/dev/null | grep -q alive && echo ok'

echo ""
echo "--- master0 (主节点) ---"
check "localhost" "NameNode        (50070)" 'source /etc/profile && ss -tlnp | grep -q 50070 && echo ok'
check "localhost" "SecondaryNameNode (50090)" 'source /etc/profile && ss -tlnp | grep -q 50090 && echo ok'
check "localhost" "YARN ResourceMgr (8088)"  'source /etc/profile && ss -tlnp | grep -q 8088 && echo ok'
check "localhost" "Spark Master     (8080)"  'source /etc/profile && ss -tlnp | grep -q 8080 && echo ok'

echo ""
echo "--- slaves (DataNode / NodeManager / Spark Worker) ---"
for host in slave1 slave2 slave3; do
    echo -n "  $host: "
    dn=$(run "$host"  'source /etc/profile && ss -tlnp | grep -q 50075 && echo DataNode' 2>/dev/null)
    nm=$(run "$host"  'source /etc/profile && ss -tlnp | grep -q 8042 && echo NodeManager' 2>/dev/null)
    sp=$(run "$host"  'source /etc/profile && ss -tlnp | grep -q 8081 && echo SparkWorker' 2>/dev/null)
    if [ -n "$dn" ] || [ -n "$nm" ] || [ -n "$sp" ]; then
        echo -e "\033[32m●\033[0m ${dn:-} ${nm:-} ${sp:-}"
    else
        echo -e "\033[31m○\033[0m (no service)"
    fi
done

echo ""
echo "=========================================="
echo "  节点连通性"
echo "=========================================="
for host in master0 slave1 slave2 slave3 mid; do
    if [ "$host" = "$THIS_HOST" ]; then
        echo -e "  \033[32m●\033[0m $host (本机)"
    elif run "$host" 'hostname' >/dev/null 2>&1; then
        echo -e "  \033[32m●\033[0m $host"
    else
        echo -e "  \033[31m○\033[0m $host (unreachable)"
    fi
done
