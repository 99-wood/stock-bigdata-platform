#!/bin/bash
# 启动数据采集容器
# 可选环境变量:
#   STOCK_LIMIT=200   采集股票数量 (0=全量)
#   INTERVAL=30       采集间隔(秒)
docker run --rm --name stock_collector \
    -e STOCK_LIMIT="${STOCK_LIMIT:-200}" \
    -e INTERVAL="${INTERVAL:-30}" \
    stock_collector:latest
