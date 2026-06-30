#!/bin/bash
# ============================================================
# Kafka 消费监控 — 查看 topic 消息量和消费积压
# 用法: 在 mid 上执行
#       bash kafka-monitor.sh [group]
#       默认 consumer group: stock_streaming_consumer
# ============================================================

export JAVA_HOME=/root/jdk1.8.0_171
KAFKA_HOME=${KAFKA_HOME:-/root/kafka}
BOOTSTRAP=${BOOTSTRAP:-localhost:9092}
GROUP=${1:-stock_streaming_consumer}

echo "=========================================="
echo "  Kafka 消费监控"
echo "  Broker: $BOOTSTRAP"
echo "  Group:  $GROUP"
echo "=========================================="

# topic 最新写入量
echo ""
printf "%-20s %5s %14s %14s %10s\n" "TOPIC" "PART" "CURRENT" "LOG-END" "LAG"
echo "---------------------------------------------------------------"

$KAFKA_HOME/bin/kafka-consumer-groups.sh \
    --bootstrap-server "$BOOTSTRAP" \
    --group "$GROUP" \
    --describe 2>/dev/null | while read line; do
    topic=$(echo "$line" | awk '{print $1}')
    part=$(echo "$line"  | awk '{print $2}')
    # 只处理数字分区行
    [[ "$part" =~ ^[0-9]+$ ]] || continue
    cur=$(echo "$line"   | awk '{print $3}')
    end=$(echo "$line"   | awk '{print $4}')
    lag=$(echo "$line"   | awk '{print $5}')

    if [ "$lag" = "-" ]; then
        printf "%-20s %5s %14s %14s %10s\n" "$topic" "$part" "$cur" "$end" "-"
    elif [ "$lag" -eq 0 ] 2>/dev/null; then
        printf "%-20s %5s %14s %14s \033[32m%10s\033[0m\n" "$topic" "$part" "$cur" "$end" "$lag"
    else
        printf "%-20s %5s %14s %14s \033[31m%10s\033[0m\n" "$topic" "$part" "$cur" "$end" "$lag"
    fi
done

echo ""
echo "=========================================="
echo "  汇总"
echo "=========================================="

# 汇总所有分区
TOTAL_CUR=0
TOTAL_END=0
TOTAL_LAG=0

while read -r topic part cur end lag rest; do
    # 只处理数字分区行
    [[ "$part" =~ ^[0-9]+$ ]] || continue
    [ "$cur" = "-" ] && cur=0
    [ "$end" = "-" ] && end=0
    [ "$lag" = "-" ] && lag=0
    TOTAL_CUR=$((TOTAL_CUR + cur))
    TOTAL_END=$((TOTAL_END + end))
    TOTAL_LAG=$((TOTAL_LAG + lag))
done < <($KAFKA_HOME/bin/kafka-consumer-groups.sh \
    --bootstrap-server "$BOOTSTRAP" \
    --group "$GROUP" \
    --describe 2>/dev/null)

echo "已消费:  $TOTAL_CUR"
echo "总消息:  $TOTAL_END"
if [ "$TOTAL_LAG" -eq 0 ]; then
    echo -e "积压:    \033[32m0 (实时)\033[0m"
else
    echo -e "积压:    \033[31m$TOTAL_LAG\033[0m"
fi
