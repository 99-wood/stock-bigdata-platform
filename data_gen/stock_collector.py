"""
数据产生端 — 股票行情采集器
支持两种数据源:
  - sina:  新浪 API 实时采集
  - jsonl: 本地 JSONL 文件回放
通过环境变量 SOURCE_TYPE 切换，默认 sina
"""
import json
import time
import logging
import sys
from datetime import datetime

import redis
import urllib3
from kafka import KafkaProducer

import config
from sources import create_source

urllib3.disable_warnings()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("collector")


def create_redis_client():
    """创建 Redis 客户端（bid/ask 直写）"""
    try:
        r = redis.Redis(
            host=config.REDIS_HOST,
            port=config.REDIS_PORT,
            password=config.REDIS_PASSWORD,
            socket_connect_timeout=5,
            decode_responses=True,
        )
        r.ping()
        log.info(f"Redis connected: {config.REDIS_HOST}:{config.REDIS_PORT}")
        return r
    except Exception as e:
        log.warning(f"Redis unavailable ({e}), bid/ask will be discarded")
        return None


def create_kafka_producer():
    """创建 Kafka 生产者"""
    for attempt in range(config.MAX_RETRIES):
        try:
            producer = KafkaProducer(
                bootstrap_servers=config.KAFKA_SERVERS,
                api_version=(2, 1, 0),
                max_block_ms=10000,
                request_timeout_ms=10000,
                value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
            )
            log.info(f"Kafka connected: {config.KAFKA_SERVERS}")
            return producer
        except Exception as e:
            if attempt < config.MAX_RETRIES - 1:
                log.warning(f"Kafka connect retry {attempt + 1}: {e}")
                time.sleep(3)
            else:
                raise


# Redis 独占字段（不进 Kafka）
REDIS_ONLY_FIELDS = [
    "bid", "ask",
    "b1_v", "b1_p", "b2_v", "b2_p", "b3_v", "b3_p", "b4_v", "b4_p", "b5_v", "b5_p",
    "s1_v", "s1_p", "s2_v", "s2_p", "s3_v", "s3_p", "s4_v", "s4_p", "s5_v", "s5_p",
    "status",
]

# Redis + Kafka 共享字段（两边都写，不从 row 中 pop）
SHARED_FIELDS = ["trade_date", "trade_time"]


def send_to_kafka(producer, redis_client, rows):
    """推送一轮数据：15 字段 → Kafka，盘口+bid/ask 等 → Redis"""
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    pipe = redis_client.pipeline() if redis_client else None

    for row in rows:
        row["event_time"] = now
        row["source"] = config.SOURCE_TYPE

        code = row.get("code", "")

        # Redis 独占字段（pop 掉不进 Kafka）
        redis_data = {}
        for field in REDIS_ONLY_FIELDS:
            redis_data[field] = row.pop(field, "")
        # 共享字段（复制，不 pop）
        for field in SHARED_FIELDS:
            redis_data[field] = row.get(field, "")

        if pipe:
            key = f"stock:quote:{code}"
            val = json.dumps(redis_data, ensure_ascii=False)
            if config.REDIS_BIDASK_TTL > 0:
                pipe.setex(key, config.REDIS_BIDASK_TTL, val)
            else:
                pipe.set(key, val)

        # 15 字段推 Kafka（包含 trade_date, trade_time）
        producer.send(config.TOPIC, value=row, key=code.encode("utf-8"))

    producer.flush()
    if pipe:
        try:
            pipe.execute()
        except Exception as e:
            log.warning(f"Redis pipeline failed: {e}")

    return len(rows)


def is_trading_time():
    """周一~周五 8:30-16:00"""
    now = datetime.now()
    if now.weekday() >= 5:
        return False
    t = now.hour * 60 + now.minute
    return 8 * 60 + 30 <= t <= 16 * 60


def run_sina(source, producer, redis_client):
    """新浪 API 模式：无限循环采集"""
    log.info("Source: sina (real-time API)")
    log.info(f"Stocks: {len(config.STOCK_CODES)}  Batch: {config.BATCH_SIZE}")
    log.info(f"Interval: {config.INTERVAL}s")
    log.info(f"Trading hours only: {config.TRADING_HOURS_ONLY}")

    round_num = 0
    total_sent = 0

    try:
        while True:
            # 非交易时段休眠
            if config.TRADING_HOURS_ONLY and not is_trading_time():
                now = datetime.now()
                log.info(f"[{now:%H:%M:%S}] 非交易时段，休眠 5 分钟...")
                time.sleep(300)
                continue

            round_start = time.time()
            rows = source.fetch_round()

            if rows:
                sent = send_to_kafka(producer, redis_client, rows)
                total_sent += sent
                round_num += 1
                elapsed = time.time() - round_start
                progress = (source._cursor / len(config.STOCK_CODES)) * 100 if config.STOCK_CODES else 0
                log.info(
                    f"Round {round_num:>5d}  "
                    f"pos={source._cursor}/{len(config.STOCK_CODES)}  "
                    f"parsed={len(rows):>5d}  "
                    f"sent={sent}  "
                    f"elapsed={elapsed:.1f}s  "
                    f"total={total_sent:,}"
                )
            else:
                round_num += 1
                log.warning(f"Round {round_num:>5d}  no data")

            remaining = config.INTERVAL - (time.time() - round_start)
            if remaining > 0:
                time.sleep(remaining)

    except KeyboardInterrupt:
        log.info("Stopping...")
    finally:
        producer.flush()
        producer.close()
        log.info(f"Stopped. Total sent: {total_sent:,}")


def run_jsonl(source, producer, redis_client):
    """JSONL 模式：按轮次回放，完毕自动退出"""
    total_rounds = source.total_rounds()
    log.info(f"Source: jsonl (file replay)")
    log.info(f"Rounds: {total_rounds}")
    log.info(f"Interval: {config.INTERVAL}s")

    round_num = 0
    total_sent = 0

    try:
        while True:
            round_start = time.time()
            rows = source.fetch_round()
            if not rows:
                log.info(f"All {total_rounds} rounds replayed. Done.")
                break

            sent = send_to_kafka(producer, redis_client, rows)
            total_sent += sent
            round_num += 1
            elapsed = time.time() - round_start

            log.info(
                f"Round {round_num:>4d}/{total_rounds}  "
                f"records={len(rows):>5d}  "
                f"elapsed={elapsed:.1f}s  "
                f"total={total_sent:,}"
            )

            remaining = config.INTERVAL - elapsed
            if remaining > 0:
                time.sleep(remaining)

    except KeyboardInterrupt:
        log.info("Stopping...")
    finally:
        producer.flush()
        producer.close()
        log.info(f"Stopped. Total sent: {total_sent:,}")


def main():
    source = create_source(config.SOURCE_TYPE)

    header = f"  stock-bigdata-platform — 数据产生端"
    log.info("=" * 50)
    log.info(header)
    log.info(f"  Kafka: {config.KAFKA_SERVERS}  Topic: {config.TOPIC}")
    log.info(f"  Source: {config.SOURCE_TYPE}")
    log.info("=" * 50)

    producer = create_kafka_producer()
    redis_client = create_redis_client()

    if config.SOURCE_TYPE == "jsonl":
        run_jsonl(source, producer, redis_client)
    else:
        run_sina(source, producer, redis_client)


if __name__ == "__main__":
    main()
