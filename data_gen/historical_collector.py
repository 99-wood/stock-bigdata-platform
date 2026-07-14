"""
历史数据采集器 — akshare stock_zh_a_daily → MySQL 直写
从 dim_stock 读取有效股票代码，REPLACE INTO dws_stock_day
"""
import os
import sys
import time
import logging
from datetime import datetime

import akshare as ak
import pymysql

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("historical")

# --- 配置（环境变量） ---

MYSQL_HOST = os.environ.get("MYSQL_HOST", "192.168.137.210")
MYSQL_PORT = int(os.environ.get("MYSQL_PORT", "3306"))
MYSQL_USER = os.environ.get("MYSQL_USER", "stock_admin")
MYSQL_PASSWORD = os.environ.get("MYSQL_PASSWORD", "stock2026")
MYSQL_DB = os.environ.get("MYSQL_DB", "stock_ads")

START_DATE = os.environ.get("HIST_START_DATE", "20240101")
END_DATE = os.environ.get("HIST_END_DATE", datetime.now().strftime("%Y%m%d"))
INTERVAL = float(os.environ.get("HIST_INTERVAL", "0.1"))
MAX_RETRIES = int(os.environ.get("HIST_MAX_RETRIES", "3"))

PROGRESS_FILE = os.environ.get("PROGRESS_FILE", "progress.txt")

SQL = (
    "REPLACE INTO dws_stock_day "
    "(code, trade_date, open, high, low, close, volume, amount, change_pct) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)"
)


def load_codes():
    """从 MySQL dim_stock 表读取有效股票代码"""
    conn = pymysql.connect(
        host=MYSQL_HOST, port=MYSQL_PORT,
        user=MYSQL_USER, password=MYSQL_PASSWORD,
        database=MYSQL_DB, charset="utf8",
    )
    try:
        with conn.cursor() as c:
            c.execute("SELECT code FROM dim_stock ORDER BY code")
            codes = [row[0] for row in c.fetchall()]
    finally:
        conn.close()
    if not codes:
        log.error("dim_stock 表为空，请先运行实时采集器初始化维表")
        sys.exit(1)
    return codes


def load_progress():
    """读取已完成代码集合"""
    if not os.path.exists(PROGRESS_FILE):
        return set()
    with open(PROGRESS_FILE, "r", encoding="utf-8") as f:
        return set(line.strip() for line in f if line.strip())


def save_progress(code):
    """追加一条已完成的代码"""
    with open(PROGRESS_FILE, "a", encoding="utf-8") as f:
        f.write(code + "\n")


def fetch_one(code):
    """获取一支股票的日线（含重试）
    返回:
        DataFrame — 成功（可能为空 DataFrame，表示该股票无数据）
        None      — API 全部重试失败
    """
    for attempt in range(MAX_RETRIES):
        try:
            return ak.stock_zh_a_daily(
                symbol=code,
                start_date=START_DATE,
                end_date=END_DATE,
                adjust="",
            )
        except Exception as e:
            if attempt < MAX_RETRIES - 1:
                log.warning(f"{code}: 第{attempt+1}次失败 — {e}，1s 后重试...")
                time.sleep(1)
    return None


def write_to_mysql(conn, code, df):
    """将一支股票的日线写入 dws_stock_day，自动计算涨跌幅。
    失败抛异常，由调用方决定是否重试。"""
    rows = 0
    prev_close = None
    with conn.cursor() as cursor:
        for _, row in df.iterrows():
            close = float(row.get("close", 0) or 0)
            if prev_close and prev_close != 0:
                change_pct = round((close - prev_close) / prev_close * 100, 2)
            else:
                change_pct = 0.0
            prev_close = close

            cursor.execute(SQL, (
                code,
                str(row["date"])[:10],
                float(row.get("open", 0) or 0),
                float(row.get("high", 0) or 0),
                float(row.get("low", 0) or 0),
                close,
                int(float(row.get("volume", 0) or 0)) // 100,
                float(row.get("amount", 0) or 0) / 10000,
                change_pct,
            ))
            rows += 1
    conn.commit()
    return rows


def main():
    codes = load_codes()
    done = load_progress()
    pending = [c for c in codes if c not in done]

    log.info(f"有效代码: {len(codes)}  已完成: {len(done)}  待处理: {len(pending)}")
    log.info(f"日期范围: {START_DATE} ~ {END_DATE}")
    log.info(f"API 间隔: {INTERVAL}s")
    log.info(f"预估耗时: {len(pending) * INTERVAL / 60:.0f}min")
    log.info(f"MySQL: {MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DB}")

    if not pending:
        log.info("全部完成，退出")
        return

    conn = pymysql.connect(
        host=MYSQL_HOST, port=MYSQL_PORT,
        user=MYSQL_USER, password=MYSQL_PASSWORD,
        database=MYSQL_DB, charset="utf8",
    )

    try:
        total_rows = 0
        start_time = time.time()

        for i, code in enumerate(pending, 1):
            t0 = time.time()
            df = fetch_one(code)
            elapsed = time.time() - t0

            if df is None:
                # API 全部重试失败 → 不写进度，重启后重试
                log.warning(f"[{len(done)+i}/{len(codes)} {(len(done)+i)/len(codes)*100:.1f}%] {code}  API失败，不记录进度")
            elif df.empty:
                # 该股票无历史数据 → 写进度跳过
                save_progress(code)
                done_count = len(done) + i
                pct = done_count / len(codes) * 100
                log.info(f"[{done_count}/{len(codes)} {pct:.1f}%] {code}  无数据，跳过")
            else:
                try:
                    rows = write_to_mysql(conn, code, df)
                except Exception as e:
                    log.warning(f"[{len(done)+i}/{len(codes)} {(len(done)+i)/len(codes)*100:.1f}%] {code}  MySQL写入失败 — {e}，不记录进度")
                else:
                    total_rows += rows
                    save_progress(code)
                    done_count = len(done) + i
                    pct = done_count / len(codes) * 100
                    eta = (len(pending) - i) * INTERVAL / 60
                    log.info(
                        f"[{done_count}/{len(codes)} {pct:.1f}%] "
                        f"{code}  {len(df)}天→{rows}行  "
                        f"ETA={eta:.0f}min  累计={total_rows:,}"
                    )

            remaining = INTERVAL - elapsed
            if remaining > 0:
                time.sleep(remaining)

    except KeyboardInterrupt:
        log.info("中断，进度已保存")
    finally:
        conn.close()
        elapsed_total = (time.time() - start_time) / 60
        log.info(f"完成，用时{elapsed_total:.0f}min，共写入 {total_rows:,} 行")


if __name__ == "__main__":
    main()
