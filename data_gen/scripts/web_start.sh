#!/bin/bash
# 启动 Web 控制台
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

echo "检查镜像..."
IMAGE="stock_collector_web:latest"
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "镜像不存在，正在构建..."
    docker build -f Dockerfile.web -t "$IMAGE" . || exit 1
fi

echo "启动 Web 控制台..."
docker run -d --rm --name stock_collector_web \
    -p 5050:5050 \
    -v //var/run/docker.sock:/var/run/docker.sock \
    -v "$(pwd)/jsonl:/app/jsonl" \
    -e "JSONL_HOST_DIR=$(pwd)/jsonl" \
    -e REDIS_PASSWORD="${REDIS_PASSWORD:-1}" \
    "$IMAGE"

echo "Web 控制台: http://localhost:5050"
echo "停止: docker stop stock_collector_web"
