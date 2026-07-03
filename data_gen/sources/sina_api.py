"""
新浪 API 数据源 — 实时从新浪财经拉取 A 股行情
"""
import time
import logging

import requests
import urllib3

from sources.base import BaseSource
import config

urllib3.disable_warnings()
log = logging.getLogger("sina")


class SinaApiSource(BaseSource):

    def __init__(self):
        self._cursor = 0
        self._total = len(config.STOCK_CODES)

    def name(self) -> str:
        return "sina"

    def total_rounds(self) -> int:
        return 0  # 无限循环

    def fetch_round(self) -> list[dict]:
        # 滚动窗口：每次取 ROLLING_SIZE 支，到尾部后回绕
        end = self._cursor + config.ROLLING_SIZE
        if end <= self._total:
            window = config.STOCK_CODES[self._cursor:end]
        else:
            # 跨尾部 + 回头部
            window = config.STOCK_CODES[self._cursor:] + config.STOCK_CODES[:end - self._total]

        self._cursor = end % self._total

        # 将窗口拆成 HTTP 批次
        batches = [
            window[i:i + config.BATCH_SIZE]
            for i in range(0, len(window), config.BATCH_SIZE)
        ]

        all_rows = []
        for batch_idx, batch in enumerate(batches):
            rows = self._fetch_batch(batch)
            all_rows.extend(rows)
            if batch_idx < len(batches) - 1:
                time.sleep(0.1)

        return all_rows

    def _fetch_batch(self, codes):
        """拉取一批股票，失败重试"""
        url = config.SINA_URL + ",".join(codes)
        for attempt in range(config.MAX_RETRIES):
            try:
                r = requests.get(
                    url,
                    timeout=config.REQUEST_TIMEOUT,
                    headers={"Referer": "https://finance.sina.com.cn"},
                )
                r.encoding = "gbk"
                rows = []
                for line in r.text.strip().split("\n"):
                    parsed = self._parse_line(line)
                    if parsed:
                        rows.append(parsed)
                return rows
            except Exception as e:
                if attempt < config.MAX_RETRIES - 1:
                    time.sleep(2)
                else:
                    log.warning(f"Sina batch failed: {e}")
        return []

    @staticmethod
    def _parse_line(line):
        """解析新浪单行 → 全字段 dict（Kafka 15 字段 + Redis 五档盘口等）"""
        if "=" not in line:
            return None
        code_part, value_part = line.split("=", 1)
        code = code_part.replace("var hq_str_", "").strip()
        data = value_part.strip('";\n ').split(",")
        if len(data) < 32 or not data[0]:
            return None
        try:
            price  = float(data[3]) if data[3] else 0
            prev   = float(data[2]) if data[2] else 0
            open_  = float(data[1]) if data[1] else 0
            high   = float(data[4]) if data[4] else 0
            low    = float(data[5]) if data[5] else 0
            bid    = float(data[6]) if data[6] else 0
            ask    = float(data[7]) if data[7] else 0
            volume = float(data[8]) if data[8] else 0
            amount = float(data[9]) if data[9] else 0

            # 计算涨跌
            change_amt = round(price - prev, 3) if prev > 0 else 0
            change_pct = round((price - prev) / prev * 100, 2) if prev > 0 else 0

            return {
                # --- Kafka 15 字段 ---
                "code":       code,
                "name":       data[0],
                "price":      price,
                "open":       open_,
                "high":       high,
                "low":        low,
                "prev_close": prev,
                "change_amt": change_amt,
                "change_pct": change_pct,
                "volume":     volume,
                "amount":     amount,
                "trade_date": data[30] if len(data) > 30 else "",
                "trade_time": data[31] if len(data) > 31 else "",
                # --- Redis 直写（五档盘口 + bid/ask） ---
                "bid":        bid,
                "ask":        ask,
                "b1_v":       data[10] if len(data) > 10 else "0",
                "b1_p":       data[11] if len(data) > 11 else "0",
                "b2_v":       data[12] if len(data) > 12 else "0",
                "b2_p":       data[13] if len(data) > 13 else "0",
                "b3_v":       data[14] if len(data) > 14 else "0",
                "b3_p":       data[15] if len(data) > 15 else "0",
                "b4_v":       data[16] if len(data) > 16 else "0",
                "b4_p":       data[17] if len(data) > 17 else "0",
                "b5_v":       data[18] if len(data) > 18 else "0",
                "b5_p":       data[19] if len(data) > 19 else "0",
                "s1_v":       data[20] if len(data) > 20 else "0",
                "s1_p":       data[21] if len(data) > 21 else "0",
                "s2_v":       data[22] if len(data) > 22 else "0",
                "s2_p":       data[23] if len(data) > 23 else "0",
                "s3_v":       data[24] if len(data) > 24 else "0",
                "s3_p":       data[25] if len(data) > 25 else "0",
                "s4_v":       data[26] if len(data) > 26 else "0",
                "s4_p":       data[27] if len(data) > 27 else "0",
                "s5_v":       data[28] if len(data) > 28 else "0",
                "s5_p":       data[29] if len(data) > 29 else "0",
                "status":     data[32] if len(data) > 32 else "",
            }
        except (ValueError, IndexError):
            return None
