# data_gen — 数据产生端

A 股行情采集器，支持双数据源，Docker 部署，输出标准化 JSON → Kafka。

---

## Web 控制台（推荐）

浏览器操作，支持选择数据源、上传 JSONL 文件、启停采集器。

```powershell
cd D:\stock-bigdata-platform\data_gen

# 构建 Web 镜像
docker build -f Dockerfile.web -t stock_collector_web:latest .

# 启动 Web 控制台 (仅本机可访问，端口 5050)
docker run -d --rm --name stock_collector_web ^
    -p 127.0.0.1:5050:5050 ^
    -v //var/run/docker.sock:/var/run/docker.sock ^
    -v "D:\stock-bigdata-platform\data_gen\jsonl:/app/jsonl" ^
    -e JSONL_HOST_DIR="D:\stock-bigdata-platform\data_gen\jsonl" ^
    -e REDIS_PASSWORD=1 ^
    stock_collector_web:latest

# 浏览器打开 http://127.0.0.1:5050
```

> 停止：`docker stop stock_collector_web`

---

## CLI 快速启动

```powershell
cd D:\stock-bigdata-platform\data_gen

# 构建镜像
docker build -t stock_collector:latest .

# 方式1: 新浪 API 实时采集 (默认 200 支, 30s 间隔)
docker run -d --rm --name stock_collector -e STOCK_LIMIT=200 stock_collector:latest

# 方式2: JSONL 文件回放 (287 轮 ~5000 支/轮, 1s 间隔, 约 5 分钟回放完毕)
docker run -d --rm --name stock_collector ^
    -e SOURCE_TYPE=jsonl ^
    -e INTERVAL=1 ^
    -v "D:\stock-bigdata-platform\data_gen\jsonl:/app/jsonl" ^
    stock_collector:latest

# 查看日志
docker logs -f stock_collector

# 停止
docker stop stock_collector
```

---

## 数据源切换

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `SOURCE_TYPE` | `sina` | `sina` = 新浪 HTTP API 实时拉取；`jsonl` = 本地 JSONL 文件回放 |
| `STOCK_LIMIT` | `200` | 采集股票数量，`0` = 全量 ~5400 支（仅 `sina` 模式有效） |
| `INTERVAL` | `30` | 每轮间隔（秒），`sina` 模式建议 ≥30，`jsonl` 模式可设为 1 |
| `JSONL_DIR` | `jsonl` | JSONL 文件目录路径（仅 `jsonl` 模式） |
| `KAFKA_SERVERS` | `192.168.137.210:9092` | Kafka Broker 地址 |
| `KAFKA_TOPIC` | `stock_quote_raw` | 目标 Topic |
| `REDIS_HOST` | `192.168.137.210` | Redis 地址（bid/ask/trade_date/trade_time 直写） |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | （必填，无默认值） | Redis 密码，缺失则 Redis 写入降级 |

---

## Kafka 消息格式

每支股票一条独立消息，JSON 格式，**key = 股票代码**，**value = 15 字段**（bid/ask 直写 Redis，不进 Kafka；trade_date/trade_time 同时进 Kafka 和 Redis）。

### 字段一览

| # | 字段 | 类型 | 含义 | 示例 |
|---|------|------|------|------|
| 1 | `code` | string | 股票代码，sh/sz 前缀 | `"sh600519"` |
| 2 | `name` | string | 股票名称 | `"贵州茅台"` |
| 3 | `price` | float | 最新成交价（元） | `1194.96` |
| 4 | `open` | float | 今开盘（元） | `1169.00` |
| 5 | `high` | float | 今日最高价（元） | `1215.00` |
| 6 | `low` | float | 今日最低价（元） | `1151.01` |
| 7 | `prev_close` | float | 昨日收盘价（元） | `1168.63` |
| 8 | `change_amt` | float | 涨跌额（元）= price − prev_close | `26.33` |
| 9 | `change_pct` | float | 涨跌幅（%）= change_amt / prev_close × 100 | `2.25` |
| 10 | `volume` | float | 成交量（**股**） | `6687812` |
| 11 | `amount` | float | 成交额（**元**） | `7949237761` |
| 12 | `trade_date` | string | API 返回的行情日期 | `"20260629"` |
| 13 | `trade_time` | string | API 返回的行情时间 | `"150004"` |
| 14 | `event_time` | string | 采集器推送时间 | `"2026-06-29 15:50:12"` |
| 15 | `source` | string | 数据来源标识 | `"sina"` 或 `"jsonl"` |

### Redis 直写字段（不进 Kafka）

**Key**: `stock:quote:{code}`，无过期，JSON 格式

| 分类 | 字段 | 类型 | 说明 |
|------|------|------|------|
| 基础 | `bid` | float | 买一价（元） |
| 基础 | `ask` | float | 卖一价（元） |
| 基础 | `trade_date` | string | 行情日期 |
| 基础 | `trade_time` | string | 行情时间 |
| 买盘 | `b1_v` ~ `b5_v` | string | 买一~买五量（手） |
| 买盘 | `b1_p` ~ `b5_p` | string | 买一~买五价（元） |
| 卖盘 | `s1_v` ~ `s5_v` | string | 卖一~卖五量（手） |
| 卖盘 | `s1_p` ~ `s5_p` | string | 卖一~卖五价（元） |
| 状态 | `status` | string | 状态码 |

> 共 25 个字段走 Redis Pipeline 批量直写。Redis 不可用时自动降级丢弃，不影响 Kafka 主链路。jsonl 模式下五档盘口字段默认空。

### 不同数据源的字段差异

| 字段 | `sina` 模式 | `jsonl` 模式 |
|------|:--:|:--:|
| code, name, price | ✅ API 返回 | ✅ 文件自带 |
| open, high, low, prev_close | ✅ API 返回 | ✅ 文件自带 |
| change_amt, change_pct | ✅ 实时计算 | ✅ 文件自带 |
| volume, amount | ✅ API 返回 | ✅ 文件自带 |
| trade_date, trade_time | ✅ API 返回 | ⚠️ 文件有则用，无则空串 |
| bid, ask | ✅ → Redis | ⚠️ → Redis（文件有则用，无则 0.0） |

> `sina` 模式为全字段。`jsonl` 模式视文件而定（例如 `20260629.jsonl` 无 bid/ask/trade_date/trade_time，`20260630.jsonl` 则有），缺失字段自动填默认值。bid/ask 无论哪种模式均直写 Redis，不进 Kafka。

### 完整示例

```json
{
  "code": "sh600519",
  "name": "贵州茅台",
  "price": 1194.96,
  "open": 1169.00,
  "high": 1215.00,
  "low": 1151.01,
  "prev_close": 1168.63,
  "change_amt": 26.33,
  "change_pct": 2.25,
  "volume": 6687812.0,
  "amount": 7949237761.0,
  "trade_date": "20260629",
  "trade_time": "150004",
  "event_time": "2026-06-29 15:50:12",
  "source": "sina"
}
```

同步写入 Redis：
```
SET stock:quote:sh600519 '{"bid":1171.57,"ask":1171.59,"trade_date":"2026-07-01","trade_time":"09:46:32","b1_v":"600","b1_p":"1171.57","b2_v":"100","b2_p":"1171.46",...}'
```

---

## 与下游消费端的字段对应

下游（B: Spark Streaming → Redis，C: Spark SQL → MySQL `stock_ads`）引用的字段：

| 下游用途 | 依赖字段 | 数据产生端 |
|---------|---------|:--:|
| 最新价 | price | ✅ |
| 涨跌幅 / 榜单排名 | change_pct | ✅ |
| 成交量 / 成交额排名 | volume, amount | ✅ |
| OHLC（日汇总开高低收） | open, high, low, price(=close) | ✅ |
| 市场总览（上涨/下跌/平盘） | change_pct | ✅ |
| 股票维表 | code, name | ✅ |

---

## 文件结构

```
data_gen/
├── Dockerfile                  ← Python 3.11 镜像 + 依赖安装
├── Dockerfile.web              ← Web 控制台镜像
├── requirements.txt            ← requests, kafka-python, redis, flask, docker
├── config.py                   ← Kafka / Redis / 数据源配置
├── stock_collector.py          ← 主入口，调度循环 → Kafka + Redis
├── web_ui.py                   ← Web 控制台（Flask）
├── valid_codes.txt             ← 有效 A 股代码列表
├── sources/
│   ├── __init__.py             ← 数据源工厂 create_source()
│   ├── base.py                 ← BaseSource 抽象基类
│   ├── sina_api.py             ← 新浪 API 源（HTTP 批量拉取）
│   └── jsonl_file.py           ← JSONL 文件源（历史回放）
├── jsonl/                      ← JSONL 数据文件（不提交 git）
│   └── 20260630.jsonl
└── scripts/
    ├── build.sh                 ← 构建镜像
    └── run.sh                   ← 快速启动
```

## 扩展新数据源

1. 新建 `sources/xxx.py`，继承 `sources/base.py` 中的 `BaseSource`
2. 实现 `name()`、`total_rounds()`、`fetch_round()` 三个方法
3. 在 `sources/__init__.py` 注册

```python
# sources/xxx.py
from sources.base import BaseSource

class NewSource(BaseSource):
    def name(self): return "xxx"
    def total_rounds(self): return 0
    def fetch_round(self) -> list[dict]: ...
```

---

## 数据量估算

| 模式 | 每轮 | 每轮数据量 | 场景 |
|------|------|-----------|------|
| sina (200 支) | ~150 支有效 | ~15 KB | 开发调试 |
| sina (全量) | ~5200 支有效 | ~500 KB | 生产 |
| jsonl | ~5500 支 | ~500 KB × 109 轮 = ~55 MB | 演示/联调 |
