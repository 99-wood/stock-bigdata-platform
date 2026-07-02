#!/bin/bash
# ============================================================
# Kafka Topic 清空脚本
# 说明: 停 Kafka → 删 ZK 元数据 → 清磁盘日志 → 启 Kafka → 重建 topic
# 用法: 在 mid 上执行 bash kafka-clear.sh
#       或 ssh root@mid 'bash -s' < kafka-clear.sh
# ============================================================
set -e

export JAVA_HOME=/root/jdk1.8.0_171
KAFKA_HOME=${KAFKA_HOME:-/root/kafka}
ZKHOST=${ZKHOST:-localhost:2181}

TOPICS=("stock_quote_raw")
PARTITIONS=3
REPLICATION=1

echo "=========================================="
echo "  Kafka Topic 清空"
echo "  ZK: $ZKHOST"
echo "=========================================="

# 1. 停 Kafka（否则磁盘日志文件被锁，删不掉）
echo "[STOP] 停止 Kafka..."
$KAFKA_HOME/bin/kafka-server-stop.sh 2>/dev/null
sleep 3

for topic in "${TOPICS[@]}"; do
    echo ""
    echo "--- 处理 topic '$topic' ---"

    # 2. 删除 ZK 中的 topic 元数据
    echo "[ZK] 删除 ZK 元数据 /brokers/topics/$topic"
    $KAFKA_HOME/bin/zookeeper-shell.sh "$ZKHOST" \
        rmr /brokers/topics/"$topic" 2>/dev/null || true
    # 同时删 config 路径（某些 Kafka 版本在此存放 topic 配置）
    $KAFKA_HOME/bin/zookeeper-shell.sh "$ZKHOST" \
        rmr /config/topics/"$topic" 2>/dev/null || true

    # 3. 删除磁盘上的日志文件
    echo "[DISK] 清理 $KAFKA_HOME/kafka-logs/$topic-*"
    rm -rf "$KAFKA_HOME"/kafka-logs/"$topic"-*
    echo "[DISK] 已清理"
done

# 4. 启动 Kafka
echo ""
echo "[START] 启动 Kafka..."
$KAFKA_HOME/bin/kafka-server-start.sh -daemon $KAFKA_HOME/config/server.properties
sleep 5
echo "[START] Kafka 已启动"

# 5. 删除并重建 topic
for topic in "${TOPICS[@]}"; do
    echo ""
    echo "[DELETE] 删除 topic '$topic'..."
    $KAFKA_HOME/bin/kafka-topics.sh \
        --zookeeper "$ZKHOST" \
        --delete \
        --topic "$topic" 2>/dev/null || true
    sleep 2

    echo "[CREATE] 重建 topic '$topic' (partitions=$PARTITIONS, replication=$REPLICATION)"
    $KAFKA_HOME/bin/kafka-topics.sh \
        --zookeeper "$ZKHOST" \
        --create \
        --if-not-exists \
        --topic "$topic" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION"
    echo "[OK] topic '$topic' 已清空重建"
done

echo ""
echo "=========================================="
echo "  验证:"
echo "=========================================="
$KAFKA_HOME/bin/kafka-topics.sh \
    --zookeeper "$ZKHOST" \
    --describe 2>/dev/null | grep -E "stock_quote_raw|Topic:"

echo ""
$KAFKA_HOME/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 \
    --topic stock_quote_raw \
    --time -1 2>/dev/null

echo ""
echo "清空完成。"
