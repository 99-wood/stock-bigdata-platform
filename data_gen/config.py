"""
数据产生端 — 共享配置
Docker 容器 → mid (192.168.137.210) Kafka
"""
import os

# 数据源: "sina" = 新浪 API 实时采集, "jsonl" = 本地 JSONL 文件回放
SOURCE_TYPE = os.environ.get("SOURCE_TYPE", "sina")

# JSONL 文件目录（仅 jsonl 模式使用）
JSONL_DIR = os.environ.get("JSONL_DIR", "jsonl")

# Kafka 连接 (mid)
KAFKA_SERVERS = os.environ.get("KAFKA_SERVERS", "192.168.137.210:9092").split(",")
TOPIC = os.environ.get("KAFKA_TOPIC", "stock_quote_raw")

# Redis (bid/ask 直写，绕过 Kafka)
REDIS_HOST = os.environ.get("REDIS_HOST", "192.168.137.210")
REDIS_PORT = int(os.environ.get("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.environ.get("REDIS_PASSWORD", "")  # 必须通过环境变量注入
REDIS_BIDASK_TTL = int(os.environ.get("REDIS_BIDASK_TTL", "0"))  # 0=永不过期

# Zookeeper (Kafka 底层依赖，生产者一般不直接连)
ZK_CONNECT = os.environ.get("ZK_CONNECT", "192.168.137.210:2181")

# 采集参数
BATCH_SIZE = int(os.environ.get("BATCH_SIZE", "800"))
INTERVAL = float(os.environ.get("INTERVAL", "30"))
MAX_RETRIES = int(os.environ.get("MAX_RETRIES", "3"))
REQUEST_TIMEOUT = int(os.environ.get("REQUEST_TIMEOUT", "30"))

# 交易时段限制: 只在周一~周五 8:30-16:00 采集，非交易时段休眠 5 分钟
TRADING_HOURS_ONLY = os.environ.get("TRADING_HOURS_ONLY", "1") == "1"

# 第一阶段股票数量 (可通过环境变量覆盖)
# 0 表示全量
STOCK_LIMIT = int(os.environ.get("STOCK_LIMIT", "200"))

# 每轮采集数量：滚动窗口大小（sina 模式下每轮采集多少支）
ROLLING_SIZE = int(os.environ.get("ROLLING_SIZE", "200"))

# A 股代码列表：优先从 valid_codes.txt 加载，回退到代码段生成
VALID_CODES_FILE = os.environ.get("VALID_CODES_FILE", "valid_codes.txt")
if os.path.exists(VALID_CODES_FILE):
    with open(VALID_CODES_FILE, "r", encoding="utf-8") as f:
        STOCK_CODES = [line.strip() for line in f if line.strip()]
else:
    STOCK_CODES = []
    STOCK_CODES += [f"sh{i}" for i in range(600000, 606000)]   # 沪主板
    STOCK_CODES += [f"sh{i}" for i in range(688000, 690000)]   # 科创板
    STOCK_CODES += [f"sz{i:06d}" for i in range(1, 4000)]      # 深主板
    STOCK_CODES += [f"sz{i}" for i in range(300000, 302000)]   # 创业板

if STOCK_LIMIT > 0:
    STOCK_CODES = STOCK_CODES[:STOCK_LIMIT]

SINA_URL = "http://hq.sinajs.cn/list="
