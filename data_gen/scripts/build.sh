#!/bin/bash
# 构建 Docker 镜像
cd "$(dirname "$0")/.."
docker build -t stock_collector:latest .
echo "Image built: stock_collector:latest"
