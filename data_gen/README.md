# data_gen — 数据产生端

A 股行情采集器，支持双数据源（实时 + 历史），Docker 部署，输出标准化 JSON → Kafka / MySQL。

---

## Web 控制台（推荐）

浏览器操作，支持选择数据源、上传 JSONL 文件、启停采集器。

```powershell
cd D:\stock-bigdata-platform\data_gen

# 一键启动（自动检查/构建镜像）
.\scripts\web_start.ps1
```

浏览器打开 `http://localhost:5050`，停止：`docker stop stock_collector_web`。

> `.sh` 脚本仅适用于 WSL 内已安装 Docker 的环境。Windows 原生 Docker Desktop 请使用 `.ps1` 脚本。

---

## CLI 快速启动

### 实时采集（sina / jsonl）

```powershell
cd D:\stock-bigdata-platform\data_gen

# 构建镜像
docker build -t stock_collector:latest .

# 方式1: 新浪 API 实时采集 (默认 200 支, 30s 间隔)
docker run -d --rm --name stock_collector -e STOCK_LIMIT=200 stock_collector:latest

# 方式2: JSONL 文件回放 (1s 间隔, 约 5 分钟回放完毕)
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

### 历史数据采集（akshare → MySQL）

一次性拉取全量 A 股历史日线，写入 `dws_stock_day` 表。支持断点续传。

```powershell
cd D:\stock-bigdata-platform\data_gen

# 宿主机直接运行（推荐）
pip install akshare pymysql
python historical_collector.py

# 或 Docker 运行
docker build -f Dockerfile.historical -t stock_historical:latest .
docker run -d --name stock_historical ^
    -e HIST_START_DATE=20240101 ^
    -e HIST_END_DATE=20260701 ^
    -v "D:\stock-bigdata-platform\data_gen\history_progress:/app/history_progress" ^
    stock_historical:latest
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `HIST_START_DATE` | `20240101` | 历史数据起始日期 |
| `HIST_END_DATE` | 当天 | 历史数据截止日期 |
| `HIST_INTERVAL` | `0.1` | API 调用间隔（秒） |
| `HIST_MAX_RETRIES` | `3` | 单支股票最大重试次数 |
| `MYSQL_HOST` / `PORT` / `USER` / `PASSWORD` / `DB` | 见代码默认值 | MySQL 连接配置 |

> 代码从 MySQL `dim_stock` 表读取有效股票代码，非 `valid_codes.txt`。进度保存在 `progress.txt`，中断后重启自动续传。

---

## 数据源切换

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `SOURCE_TYPE` | `sina` | `sina` = 新浪 HTTP API 实时拉取；`jsonl` = 本地 JSONL 文件回放 |
| `STOCK_LIMIT` | `200` | 采集股票数量，`0` = 全量 ~5400 支（仅 `sina` 模式有效） |
| `INTERVAL` | `30` | 每轮间隔（秒），`sina` 模式建议 ≥30，`jsonl` 模式可设为 1 |
| `JSONL_DIR` | `jsonl` | JSONL 文件目录路径（仅 `jsonl` 模式） |
| `JSONL_FILTER_DATE` | 空 | 日期过滤 `YYYY-MM-DD`，仅推送匹配日期的数据（`jsonl` 模式） |
| `SINA_FILTER_TODAY` | `1` | 启用后仅推送当天日期的实时数据（`sina` 模式） |
| `KAFKA_SERVERS` | `192.168.137.210:9092` | Kafka Broker 地址 |
| `KAFKA_TOPIC` | `stock_quote_raw` | 目标 Topic |
| `REDIS_HOST` | `192.168.137.210` | Redis 地址（bid/ask/trade_date/trade_time 直写） |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 必填 | Redis 密码，缺失则 Redis 写入降级 |

---

## 文件结构

```
data_gen/
├── .gitignore
├── Dockerfile                  ← 实时采集器镜像
├── Dockerfile.web              ← Web 控制台镜像
├── Dockerfile.historical       ← 历史采集器镜像
├── requirements.txt            ← Python 依赖
├── config.py                   ← Kafka / Redis / 数据源配置
├── stock_collector.py          ← 实时采集器入口 → Kafka + Redis
├── historical_collector.py     ← 历史采集器入口 → MySQL
├── web_ui.py                   ← Web 控制台（Flask）
├── valid_codes.txt             ← 有效 A 股代码列表（实时采集用）
├── sources/
│   ├── __init__.py             ← 数据源工厂 create_source()
│   ├── base.py                 ← BaseSource 抽象基类
│   ├── sina_api.py             ← 新浪 API 源（HTTP 批量拉取）
│   └── jsonl_file.py           ← JSONL 文件源（历史回放）
├── jsonl/                      ← JSONL 数据文件（不提交 git）
└── scripts/
    ├── web_start.ps1           ← Web 控制台启动 (Windows PowerShell)
    ├── web_start.sh            ← Web 控制台启动 (WSL/Linux)
    ├── build.sh                ← 构建镜像
    └── run.sh                  ← 快速启动
```

---

## Kafka 消息格式

每支股票一条独立消息，JSON 格式，**key = 股票代码**，**value = 15 字段**。

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

### Redis 直写字段（不进 Kafka）

**Key**: `stock:quote:{code}`，无过期，JSON 格式，共 25 字段（bid/ask + 五档盘口 + status + trade_date/trade_time）。

---

## 扩展新数据源

1. 新建 `sources/xxx.py`，继承 `sources/base.py` 中的 `BaseSource`
2. 实现 `name()`、`total_rounds()`、`fetch_round()` 三个方法
3. 在 `sources/__init__.py` 注册

```python
from sources.base import BaseSource

class NewSource(BaseSource):
    def name(self): return "xxx"
    def total_rounds(self): return 0
    def fetch_round(self) -> list[dict]: ...
```

---

## 数据量估算

| 模式 | 每轮 | 数据量 | 场景 |
|------|------|-----------|------|
| sina (200 支) | ~150 支有效 | ~15 KB | 开发调试 |
| sina (全量) | ~5200 支有效 | ~500 KB | 生产 |
| jsonl | ~5500 支 | ~500 KB × 109 轮 = ~55 MB | 演示/联调 |
| historical | 5221 支 × ~600 天 | ~285 万行 | 历史数据初始化 |
