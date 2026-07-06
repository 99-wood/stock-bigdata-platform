#!/bin/bash
# ============================================================
# Kafka 消费监控 — 查看 topic 消息量和消费积压
# 用法: 在 master0 上执行
#       bash kafka-monitor.sh [group]
#       默认从 /root/stock-app/application.properties 读取 kafka.group.id
#       Kafka 命令通过 SSH 在 mid(mid) 上执行
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KAFKA_HOST="mid"
KAFKA_HOME="/root/kafka"
BOOTSTRAP="mid:9092"

# 从配置读取默认 group
if [ -n "$1" ]; then
    GROUP="$1"
elif [ -f "/root/stock-app/application.properties" ]; then
    GROUP=$(grep 'kafka.group.id' /root/stock-app/application.properties | cut -d'=' -f2)
else
    GROUP="stock_streaming_consumer_v2"
fi

echo "=========================================="
echo "  Kafka 消费监控"
echo "  Broker: $BOOTSTRAP"
echo "  Group:  $GROUP"
echo "=========================================="

# 在 mid 上执行 kafka-consumer-groups
RAW=$(ssh -o ConnectTimeout=5 "$KAFKA_HOST" \
    "export JAVA_HOME=/root/jdk1.8.0_171 && $KAFKA_HOME/bin/kafka-consumer-groups.sh \
     --bootstrap-server $BOOTSTRAP --group $GROUP --describe 2>/dev/null")

if [ -z "$RAW" ]; then
    echo "[错误] 无法获取消费组信息, 请检查 Kafka 是否正常运行"
    exit 1
fi

echo ""
printf "%-20s %5s %14s %14s %10s\n" "TOPIC" "PART" "CURRENT" "LOG-END" "LAG"
echo "---------------------------------------------------------------"

# 累加器
TOTAL_CUR=0 TOTAL_END=0 TOTAL_LAG=0

echo "$RAW" | while read line; do
    topic=$(echo "$line" | awk '{print $1}')
    part=$(echo "$line"  | awk '{print $2}')
    [[ "$part" =~ ^[0-9]+$ ]] || continue

    cur=$(echo "$line" | awk '{print $3}')
    end=$(echo "$line" | awk '{print $4}')
    lag=$(echo "$line" | awk '{print $5}')

    if [ "$lag" = "-" ]; then
        printf "%-20s %5s %14s %14s %10s\n" "$topic" "$part" "$cur" "$end" "-"
    elif [ "$lag" -eq 0 ] 2>/dev/null; then
        printf "%-20s %5s %14s %14s \033[32m%10s\033[0m\n" "$topic" "$part" "$cur" "$end" "$lag"
    else
        printf "%-20s %5s %14s %14s \033[31m%10s\033[0m\n" "$topic" "$part" "$cur" "$end" "$lag"
    fi
done

# 汇总（需重新获取避免 subshell 中变量丢失）
echo ""
echo "=========================================="
echo "  汇总"
echo "=========================================="

echo "$RAW" | while read -r topic part cur end lag rest; do
    [[ "$part" =~ ^[0-9]+$ ]] || continue
    [ "$cur" = "-" ] && cur=0
    [ "$end" = "-" ] && end=0
    [ "$lag" = "-" ] && lag=0
    echo "$((cur)) $((end)) $((lag))"
done | awk '{c+=$1; e+=$2; l+=$3} END {
    printf "已消费:  %s\n总消息:  %s\n", c, e
    if (l == 0) printf "积压:    \033[32m0 (实时)\033[0m\n"
    else        printf "积压:    \033[31m%s\033[0m\n", l
}'
