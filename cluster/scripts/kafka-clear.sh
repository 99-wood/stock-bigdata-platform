#!/bin/bash
# ============================================================
# Kafka Topic 清空脚本
# 说明: 先删除 topic 再重建，数据全部清空
# 用法: 在 mid 上执行 bash kafka-clear.sh
#       或 ssh root@mid 'bash -s' < kafka-clear.sh
# ============================================================
set -e

export JAVA_HOME=/root/jdk1.8.0_171
KAFKA_HOME=${KAFKA_HOME:-/root/kafka}
ZKHOST=${ZKHOST:-localhost:2181}

TOPICS=("stock_quote_raw")

echo "=========================================="
echo "  Kafka Topic 清空"
echo "  ZK: $ZKHOST"
echo "=========================================="

for topic in "${TOPICS[@]}"; do
    echo ""
    echo "[CHECK] topic '$topic' ..."

    if ! $KAFKA_HOME/bin/kafka-topics.sh \
        --zookeeper "$ZKHOST" \
        --list 2>/dev/null | grep -qxF "$topic"; then
        echo "[WARN] topic '$topic' 不存在，跳过"
        continue
    fi

    echo "[DELETE] 删除 topic '$topic' ..."
    $KAFKA_HOME/bin/kafka-topics.sh \
        --zookeeper "$ZKHOST" \
        --delete \
        --topic "$topic"
    sleep 2

    # 等待删除生效
    while $KAFKA_HOME/bin/kafka-topics.sh \
        --zookeeper "$ZKHOST" \
        --list 2>/dev/null | grep -qxF "$topic"; do
        echo "  等待删除生效..."
        sleep 1
    done

    echo "[CREATE] 重建 topic '$topic' (3 partitions, 1 replica)"
    $KAFKA_HOME/bin/kafka-topics.sh \
        --zookeeper "$ZKHOST" \
        --create \
        --topic "$topic" \
        --partitions 3 \
        --replication-factor 1

    echo "[OK] topic '$topic' 已清空重建"
done

echo ""
echo "=========================================="
echo "  当前 topic 列表:"
echo "=========================================="
$KAFKA_HOME/bin/kafka-topics.sh \
    --zookeeper "$ZKHOST" \
    --list 2>/dev/null

echo ""
echo "清空完成。"
