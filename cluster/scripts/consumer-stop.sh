#!/bin/bash
# ============================================================
# Spark Streaming 消费端关闭（优雅停止，保证资源释放）
# 用法: bash consumer-stop.sh
# ============================================================

APP_NAME="QuoteStreamingJob"
GRACE_TIMEOUT=60   # 优雅停止最长等待秒数（需留足当前批次完成 + offset 提交的时间）

echo "=========================================="
echo "  Spark Streaming 消费端关闭"
echo "=========================================="

# ---- 1. 查找进程 ----
source /etc/profile
PID=$(ps aux | grep "SparkSubmit.*$APP_NAME" | grep -v grep | awk '{print $2}')

if [ -z "$PID" ]; then
    echo "[完成] 未找到 $APP_NAME 进程，无需关闭"
    exit 0
fi

echo ""
echo "  进程 PID: $PID"
echo "  优雅停止超时: ${GRACE_TIMEOUT}s"
echo ""

# ---- 2. 优雅停止: 写 shutdown marker ----
# 下个 batch 开始时 Streaming 检测到标记文件 → 主动调用 ssc.stop(true, true)
# 比 SIGTERM 更可靠: 会完成当前批次 + 提交 offset + 关闭 SparkContext
echo "  写入 shutdown marker: /tmp/stock-consumer-stop"
touch /tmp/stock-consumer-stop

# ---- 3. 等待进程退出 ----
WAITED=0
while [ $WAITED -lt $GRACE_TIMEOUT ]; do
    if ! kill -0 $PID 2>/dev/null; then
        echo "  ✓ 已优雅退出 (${WAITED}s)"
        break
    fi
    sleep 2
    WAITED=$((WAITED + 2))
    if [ $((WAITED % 10)) -eq 0 ]; then
        echo "  等待中... ${WAITED}s"
    fi
done

# ---- 4. 超时则强制关闭 ----
if kill -0 $PID 2>/dev/null; then
    echo ""
    echo "  ⚠ 优雅停止超时 (${GRACE_TIMEOUT}s), 强制执行 kill -9"
    kill -9 $PID
    sleep 1

    if kill -0 $PID 2>/dev/null; then
        echo "  ✗ 强制停止失败, 请手动处理 PID=$PID"
        exit 1
    fi
    echo "  ✓ 已强制停止"
fi

# ---- 3. 清理标记 ----
rm -f /tmp/stock-consumer-stop

echo ""

# ---- 5. 检查残留 Spark Executor ----
# 清理可能未退出的 CoarseGrainedExecutorBackend
for host in 192.168.137.202 192.168.137.203 192.168.137.204; do
    REMOTE_PIDS=$(ssh -o ConnectTimeout=3 $host "ps aux | grep 'CoarseGrainedExecutorBackend' | grep -v grep | awk '{print \$2}'" 2>/dev/null)
    if [ -n "$REMOTE_PIDS" ]; then
        echo "  清理 $host 残留 Executor: $REMOTE_PIDS"
        ssh $host "kill -9 $REMOTE_PIDS 2>/dev/null" &
    fi
done

wait

# ---- 6. 最终确认 ----
REMAIN=$(ps aux | grep "SparkSubmit.*$APP_NAME" | grep -v grep)
if [ -n "$REMAIN" ]; then
    echo "[警告] 仍有残留进程: $REMAIN"
else
    echo "[完成] $APP_NAME 已完全关闭"
fi
echo "=========================================="
