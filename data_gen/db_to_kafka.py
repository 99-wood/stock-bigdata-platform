"""
从 snapshots.db 读取历史数据 → 推送到 Kafka
用于无 API 依赖的开发调试场景，按采集轮次逐轮回放
"""
import json
import time
import logging
import sqlite3

from kafka import KafkaProducer

import config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("db2kafka")

DB_PATH = "snapshots.db"
ROUND_INTERVAL = 2  # 轮次间隔(秒)，回放时可比真实 30s 更快


def create_producer():
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


def main():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row

    # 获取所有采集轮次
    rounds = [
        row[0] for row in
        conn.execute("SELECT DISTINCT collect_time FROM snapshots ORDER BY collect_time").fetchall()
    ]
    log.info(f"DB: {DB_PATH}")
    log.info(f"Rounds: {len(rounds)}")
    log.info(f"From: {rounds[0]}  To: {rounds[-1]}")
    log.info(f"Interval: {ROUND_INTERVAL}s/round")
    log.info(f"Topic: {config.TOPIC}")

    producer = create_producer()

    # 跳过 id 列，其他字段推 Kafka
    skip_col = "id"
    columns = [c[1] for c in conn.execute("PRAGMA table_info(snapshots)").fetchall() if c[1] != skip_col]

    round_num = 0
    total_sent = 0

    try:
        for collect_time in rounds:
            round_start = time.time()

            rows = conn.execute(
                "SELECT * FROM snapshots WHERE collect_time = ?",
                (collect_time,)
            ).fetchall()

            futures = []
            for row in rows:
                msg = {c: row[c] for c in columns}
                msg["source"] = "sina"
                futures.append(producer.send(
                    config.TOPIC,
                    value=msg,
                    key=msg["code"].encode("utf-8"),
                ))
            producer.flush()
            ok = len(futures)

            total_sent += ok
            round_num += 1
            elapsed = time.time() - round_start

            log.info(
                f"Round {round_num:>4d}/{len(rounds)}  "
                f"time={collect_time}  "
                f"sent={ok}/{len(rows)}  "
                f"elapsed={elapsed:.1f}s  "
                f"total={total_sent:,}"
            )

            # 如果还有下一轮，等待
            if round_num < len(rounds):
                remaining = ROUND_INTERVAL - elapsed
                if remaining > 0:
                    time.sleep(remaining)

    except KeyboardInterrupt:
        log.info("Stopping...")
    finally:
        producer.flush()
        producer.close()
        conn.close()
        log.info(f"Stopped. Total sent: {total_sent:,}")


if __name__ == "__main__":
    main()
