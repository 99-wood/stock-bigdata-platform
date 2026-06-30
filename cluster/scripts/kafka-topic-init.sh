#!/bin/bash
# ============================================================
# Kafka Topic 初始化脚本
# 模块: cluster
# 说明: 在 mid 节点上创建项目所需的 Kafka topic
# 用法: ssh root@mid 'bash -s' < kafka-topic-init.sh
#       或在 mid 上直接执行: bash kafka-topic-init.sh
# ============================================================

set -e

# ---------- 环境变量 ----------
export JAVA_HOME=/root/jdk1.8.0_171
KAFKA_HOME=${KAFKA_HOME:-/root/kafka}
ZKHOST=${ZKHOST:-localhost:2181}

# ---------- topic 定义 ----------
#   name: topic 名称
#   partitions: 分区数（并行消费能力）
#   replication: 副本数（实训单机 Kafka，固定 1）
declare -A TOPICS=(
    [stock_quote_raw]="3:1"
)

echo "=========================================="
echo "  Kafka Topic 初始化"
echo "  ZK: $ZKHOST"
echo "=========================================="

for topic in "${!TOPICS[@]}"; do
    IFS=':' read -r partitions replication <<< "${TOPICS[$topic]}"

    # 检查是否已存在
    if $KAFKA_HOME/bin/kafka-topics.sh \
        --zookeeper "$ZKHOST" \
        --list 2>/dev/null | grep -qxF "$topic"; then
        echo "[SKIP] topic '$topic' 已存在"
        continue
    fi

    echo "[CREATE] topic '$topic' (partitions=$partitions, replication=$replication)"
    $KAFKA_HOME/bin/kafka-topics.sh \
        --zookeeper "$ZKHOST" \
        --create \
        --topic "$topic" \
        --partitions "$partitions" \
        --replication-factor "$replication"

    echo "[OK] topic '$topic' 创建成功"
done

echo ""
echo "=========================================="
echo "  当前所有 topic:"
echo "=========================================="
$KAFKA_HOME/bin/kafka-topics.sh \
    --zookeeper "$ZKHOST" \
    --list 2>/dev/null

echo ""
echo "初始化完成。"
