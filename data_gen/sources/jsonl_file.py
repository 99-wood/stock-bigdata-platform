"""
JSONL 文件数据源 — 从本地 JSONL 文件回放历史行情数据
"""
import json
import logging
import os

from sources.base import BaseSource
import config

log = logging.getLogger("jsonl")

JSONL_DIR = os.environ.get("JSONL_DIR", "jsonl")


class JsonlFileSource(BaseSource):

    def __init__(self):
        self._rounds = []
        self._cursor = 0
        self._load()

    def name(self) -> str:
        return "jsonl"

    def total_rounds(self) -> int:
        return len(self._rounds)

    def fetch_round(self) -> list[dict]:
        if self._cursor >= len(self._rounds):
            return []  # 回放完毕
        rows = self._rounds[self._cursor]
        self._cursor += 1
        return rows

    def _load(self):
        """加载 JSONL 目录下所有文件，按时间排序"""
        if not os.path.isdir(JSONL_DIR):
            log.warning(f"JSONL dir not found: {JSONL_DIR}")
            return

        files = sorted([
            f for f in os.listdir(JSONL_DIR) if f.endswith(".jsonl")
        ])
        if not files:
            log.warning(f"No .jsonl files in {JSONL_DIR}")
            return

        filter_date = config.JSONL_FILTER_DATE
        total_lines = 0
        skipped_lines = 0

        for fname in files:
            path = os.path.join(JSONL_DIR, fname)
            with open(path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    total_lines += 1

                    # 方案B：日期过滤时先做字符串预扫，跳过不匹配的行
                    if filter_date and filter_date not in line:
                        skipped_lines += 1
                        continue

                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    rows = self._extract_rows(obj)
                    if rows:
                        if filter_date:
                            rows = [r for r in rows if r.get("trade_date", "") == filter_date]
                        if rows:
                            self._rounds.append(rows)

        if filter_date:
            log.info(
                f"JSONL loaded: {len(files)} file(s), "
                f"pre-scanned {total_lines} lines, "
                f"skipped {skipped_lines} (no '{filter_date}'), "
                f"parsed {total_lines - skipped_lines}, "
                f"{len(self._rounds)} rounds, "
                f"{sum(len(r) for r in self._rounds):,} total records"
            )
        else:
            log.info(
                f"JSONL loaded: {len(files)} file(s), "
                f"{len(self._rounds)} rounds, "
                f"{sum(len(r) for r in self._rounds):,} total records"
            )

    @staticmethod
    def _extract_rows(obj):
        """
        从 JSONL 对象提取统一格式的股票列表
        支持两种格式:
          1. {"time": "...", "count": N, "data": [...]}  ← 云服务器 collector.py 输出
          2. 直接 {"collect_time": "...", "count": N, "data": [...]}  ← 兼容
        """
        data = obj.get("data")
        if not data:
            return None

        rows = []
        for item in data:
            row = {
                # Kafka 字段
                "code":       item.get("code", ""),
                "name":       item.get("name", ""),
                "price":      float(item.get("price", 0)),
                "open":       float(item.get("open", 0)),
                "high":       float(item.get("high", 0)),
                "low":        float(item.get("low", 0)),
                "prev_close": float(item.get("prev_close", 0)),
                "change_amt": float(item.get("change_amt", 0)),
                "change_pct": float(item.get("change_pct", 0)),
                "volume":     float(item.get("volume", 0)),
                "amount":     float(item.get("amount", 0)),
                "trade_date": str(item.get("trade_date", "")),
                "trade_time": str(item.get("trade_time", "")),
                # Redis 直写字段（jsonl 有则用，无则空）
                "bid":        float(item.get("bid", 0)),
                "ask":        float(item.get("ask", 0)),
                "b1_v":       str(item.get("b1_v", "")),
                "b1_p":       str(item.get("b1_p", "")),
                "b2_v":       str(item.get("b2_v", "")),
                "b2_p":       str(item.get("b2_p", "")),
                "b3_v":       str(item.get("b3_v", "")),
                "b3_p":       str(item.get("b3_p", "")),
                "b4_v":       str(item.get("b4_v", "")),
                "b4_p":       str(item.get("b4_p", "")),
                "b5_v":       str(item.get("b5_v", "")),
                "b5_p":       str(item.get("b5_p", "")),
                "s1_v":       str(item.get("s1_v", "")),
                "s1_p":       str(item.get("s1_p", "")),
                "s2_v":       str(item.get("s2_v", "")),
                "s2_p":       str(item.get("s2_p", "")),
                "s3_v":       str(item.get("s3_v", "")),
                "s3_p":       str(item.get("s3_p", "")),
                "s4_v":       str(item.get("s4_v", "")),
                "s4_p":       str(item.get("s4_p", "")),
                "s5_v":       str(item.get("s5_v", "")),
                "s5_p":       str(item.get("s5_p", "")),
                "status":     str(item.get("status", "")),
            }
            rows.append(row)
        return rows
