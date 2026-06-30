-- ============================================================
-- stock-bigdata-platform 数据库初始化脚本
-- 模块: cluster
-- 说明: 创建 stock_ads 展示库及全部表结构
-- 版本: v1.0
-- 日期: 2026-06-30
-- ============================================================

-- ==================== 第一步：建库 ====================
CREATE DATABASE IF NOT EXISTS stock_ads
    DEFAULT CHARACTER SET utf8
    DEFAULT COLLATE utf8_general_ci;

USE stock_ads;

-- ==================== 第二步：建表 ====================

-- -----------------------------------------------------------
-- 1. dim_stock — 股票基础维表
-- 数据来源：采集器首轮运行后从 Kafka 消费 code+name，批量 INSERT IGNORE
-- market 从 code 前缀推导：sh→沪市 / sz→深市 / bj→北交所
-- industry 需手动维护或从公开数据源导入
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS dim_stock (
    code        VARCHAR(20)  PRIMARY KEY         COMMENT '股票代码，如 sh600519',
    name        VARCHAR(50)  NOT NULL            COMMENT '股票名称，如 贵州茅台',
    market      VARCHAR(10)  NOT NULL            COMMENT '市场: sh(沪市) / sz(深市) / bj(北交所)',
    industry    VARCHAR(50)                      COMMENT '行业（新浪API不提供，需手动维护）',
    INDEX idx_industry (industry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='股票基础维表——一次性初始化，按需更新';

-- -----------------------------------------------------------
-- 2. dws_stock_day — 日级行情汇总
-- 数据来源：Spark SQL 离线任务每日收盘后写入
-- 计算逻辑：按 code + DATE(event_time) 分组，取 OHLCV
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS dws_stock_day (
    id          BIGINT        PRIMARY KEY AUTO_INCREMENT,
    code        VARCHAR(20)   NOT NULL            COMMENT '股票代码',
    trade_date  DATE          NOT NULL            COMMENT '交易日',
    open        DECIMAL(12,4)                     COMMENT '开盘价（当日首笔快照的 price）',
    high        DECIMAL(12,4)                     COMMENT '最高价（当日所有快照 price 的 MAX）',
    low         DECIMAL(12,4)                     COMMENT '最低价（当日所有快照 price 的 MIN）',
    close       DECIMAL(12,4)                     COMMENT '收盘价（当日末笔快照的 price）',
    volume      BIGINT                            COMMENT '成交量，手（当日末笔快照的累计 volume）',
    amount      DECIMAL(20,4)                     COMMENT '成交额，万元（当日末笔快照的累计 amount）',
    change_pct  DECIMAL(10,4)                     COMMENT '涨跌幅 %（当日末笔快照的 change_pct）',
    UNIQUE KEY uk_code_date (code, trade_date),
    INDEX idx_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='日级行情汇总——Spark SQL 离线任务每日写入';

-- -----------------------------------------------------------
-- 3. dws_stock_minute — 分钟级行情聚合
-- 数据来源：Spark SQL 每 5 分钟更新一次
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS dws_stock_minute (
    id          BIGINT        PRIMARY KEY AUTO_INCREMENT,
    code        VARCHAR(20)   NOT NULL            COMMENT '股票代码',
    minute_time DATETIME      NOT NULL            COMMENT '分钟时间戳，精确到分钟',
    open        DECIMAL(12,4)                     COMMENT '该分钟首笔 price',
    high        DECIMAL(12,4)                     COMMENT '该分钟最高 price',
    low         DECIMAL(12,4)                     COMMENT '该分钟最低 price',
    close       DECIMAL(12,4)                     COMMENT '该分钟末笔 price',
    volume      BIGINT                            COMMENT '该分钟成交量（手）',
    amount      DECIMAL(20,4)                     COMMENT '该分钟成交额（万元）',
    UNIQUE KEY uk_code_minute (code, minute_time),
    INDEX idx_minute_time (minute_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='分钟级行情聚合——Spark SQL 每 5 分钟更新';

-- -----------------------------------------------------------
-- 4. ads_market_summary — 市场概览
-- 数据来源：Spark Streaming 实时写入 Redis，Spark SQL 离线写入 MySQL 兜底
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ads_market_summary (
    id              BIGINT        PRIMARY KEY AUTO_INCREMENT,
    stat_time       DATETIME      NOT NULL           COMMENT '统计时间',
    total_stocks    INT           NOT NULL DEFAULT 0 COMMENT '有效股票总数',
    up_count        INT           NOT NULL DEFAULT 0 COMMENT '上涨家数（change_pct > 0）',
    down_count      INT           NOT NULL DEFAULT 0 COMMENT '下跌家数（change_pct < 0）',
    flat_count      INT           NOT NULL DEFAULT 0 COMMENT '平盘家数（change_pct = 0）',
    avg_change_pct  DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '平均涨跌幅 %',
    total_volume    BIGINT                         COMMENT '全市场总成交量（手）',
    total_amount    DECIMAL(24,4)                  COMMENT '全市场总成交额（万元）',
    UNIQUE KEY uk_stat_time (stat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='市场概览——实时 Redis 为主，MySQL 兜底';

-- -----------------------------------------------------------
-- 5. ads_stock_rank — 榜单快照
-- 数据来源：Spark 每 5 分钟写入一批
-- rank_type: up / down / amount / quant
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ads_stock_rank (
    id          BIGINT        PRIMARY KEY AUTO_INCREMENT,
    rank_type   VARCHAR(20)   NOT NULL            COMMENT '榜单类型: up / down / amount / quant',
    code        VARCHAR(20)   NOT NULL            COMMENT '股票代码',
    name        VARCHAR(50)                       COMMENT '股票名称',
    rank_no     INT           NOT NULL            COMMENT '排名',
    score       DECIMAL(12,4) NOT NULL            COMMENT '排序分值: change_pct 或 amount 或 quant_score',
    stat_time   DATETIME      NOT NULL            COMMENT '统计批次时间',
    INDEX idx_rank_type_time (rank_type, stat_time),
    INDEX idx_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='榜单快照——Spark 每 5 分钟写入一批';

-- -----------------------------------------------------------
-- 6. ads_quant_score — 量化评分
-- 数据来源：Spark SQL 离线任务每 5 分钟 / 每日计算
-- 因子（无用户模块，共 4 因子）：
--   momentum（动量）、volume_factor（量能）
--   volatility（波动率）、relative_strength（相对强度）
-- 默认权重（quant-weight.properties）：
--   w1=0.35  w2=0.30  w3=0.20  w4=0.15
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ads_quant_score (
    id                BIGINT        PRIMARY KEY AUTO_INCREMENT,
    code              VARCHAR(20)   NOT NULL      COMMENT '股票代码',
    score_time        DATETIME      NOT NULL      COMMENT '评分计算时间',
    momentum          DECIMAL(10,4)               COMMENT '动量因子（归一化 0-100）',
    volume_factor     DECIMAL(10,4)               COMMENT '量能因子（归一化 0-100）',
    volatility        DECIMAL(10,4)               COMMENT '波动率因子（归一化 0-100，波动越高分数越低）',
    relative_strength DECIMAL(10,4)               COMMENT '相对强度因子（归一化 0-100）',
    quant_score       DECIMAL(10,4)               COMMENT '综合评分（4 因子加权求和）',
    UNIQUE KEY uk_code_time (code, score_time),
    INDEX idx_score_time (score_time),
    INDEX idx_quant_score (score_time, quant_score DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='量化评分——Spark SQL 离线计算，4 因子加权';

-- -----------------------------------------------------------
-- 7. ads_strategy_backtest — 策略回测
-- 数据来源：Spark SQL 手动或每日运行
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ads_strategy_backtest (
    id              BIGINT        PRIMARY KEY AUTO_INCREMENT,
    strategy_name   VARCHAR(50)   NOT NULL       COMMENT '策略名称: ma_cross(均线交叉) / momentum_topN(动量选股)',
    code            VARCHAR(20)   NOT NULL       COMMENT '股票代码；全市场回测时填 ALL',
    start_date      DATE          NOT NULL       COMMENT '回测起始日期',
    end_date        DATE          NOT NULL       COMMENT '回测截止日期',
    return_rate     DECIMAL(10,4)                COMMENT '累计收益率（如 0.15 代表 15%）',
    win_rate        DECIMAL(10,4)                COMMENT '胜率（盈利交易占比）',
    max_drawdown    DECIMAL(10,4)                COMMENT '最大回撤（如 -0.20 代表 -20%）',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_strategy_code (strategy_name, code),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='策略回测结果——Spark SQL 手动或每日运行';

-- -----------------------------------------------------------
-- 8. ads_data_quality — 数据质量监控
-- 数据来源：Spark SQL 每小时统计
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ads_data_quality (
    id              BIGINT        PRIMARY KEY AUTO_INCREMENT,
    stat_time       DATETIME      NOT NULL          COMMENT '统计时间',
    source          VARCHAR(20)   NOT NULL          COMMENT '数据来源: sina',
    total_count     INT           NOT NULL DEFAULT 0 COMMENT '总采集条数',
    success_count   INT           NOT NULL DEFAULT 0 COMMENT '成功条数',
    null_count      INT           NOT NULL DEFAULT 0 COMMENT '空值条数（price 为 NULL 或 0）',
    duplicate_count INT           NOT NULL DEFAULT 0 COMMENT '重复条数',
    delay_seconds   DECIMAL(10,2)                   COMMENT '平均延迟（event_time 与系统时间差值）',
    success_rate    DECIMAL(6,4)                    COMMENT '采集成功率 = success_count / total_count',
    UNIQUE KEY uk_time_source (stat_time, source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='数据质量监控——Spark SQL 每小时统计';

-- ==================== 完成 ====================
-- dim_stock 维表由采集器首轮运行后通过 INSERT IGNORE 自动灌入
-- 下一步：执行后验证
--   mysql> USE stock_ads; SHOW TABLES;
