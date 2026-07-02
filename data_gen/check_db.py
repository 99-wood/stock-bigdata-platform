import sqlite3, json

conn = sqlite3.connect(r"D:\大数据实训\data_gen\snapshots.db")

# 一条完整数据
cols = ['id','code','name','open','prev_close','price','high','low','bid','ask','volume','amount','trade_date','trade_time','collect_time']
row = conn.execute("SELECT * FROM snapshots WHERE code='sh600519' ORDER BY id DESC LIMIT 1").fetchone()
d = dict(zip(cols, row))
print(json.dumps(d, ensure_ascii=False, indent=2))

# 统计
print()
print(f"股票数: {conn.execute('SELECT COUNT(DISTINCT code) FROM snapshots').fetchone()[0]}")
print(f"总行数: {conn.execute('SELECT COUNT(*) FROM snapshots').fetchone()[0]}")

r = conn.execute("SELECT MIN(collect_time), MAX(collect_time) FROM snapshots WHERE price>0").fetchone()
print(f"时间范围: {r[0]} ~ {r[1]}")

valid = conn.execute("SELECT COUNT(DISTINCT code) FROM snapshots WHERE price>0").fetchone()[0]
invalid = conn.execute("SELECT COUNT(DISTINCT code) FROM snapshots WHERE price=0").fetchone()[0]
print(f"有效(price>0): {valid}  无效(price=0): {invalid}")

latest = conn.execute("SELECT MAX(collect_time) FROM snapshots").fetchone()[0]
cnt = conn.execute("SELECT COUNT(*) FROM snapshots WHERE collect_time=?", (latest,)).fetchone()[0]
print(f"最新一轮({latest}): {cnt} 支")

# 时间分布
print()
print("时间分布 (每30分钟):")
for row in conn.execute("SELECT substr(collect_time,1,15)||'0' as ts, COUNT(*) as cnt FROM snapshots GROUP BY ts ORDER BY ts").fetchall():
    print(f"  {row[0]}  {row[1]:>8} rows")

conn.close()
