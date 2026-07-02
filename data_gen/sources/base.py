"""
数据源基类 — 所有数据源必须实现 fetch_round()
返回格式: List[dict], 每个 dict 是统一 16 字段记录
"""
from abc import ABC, abstractmethod


class BaseSource(ABC):
    """数据源基类"""

    @abstractmethod
    def name(self) -> str:
        """数据源标识: 'sina' 或 'jsonl'"""
        ...

    @abstractmethod
    def total_rounds(self) -> int:
        """总轮数 (实时 API 返回 0 表示无限循环)"""
        ...

    @abstractmethod
    def fetch_round(self) -> list[dict]:
        """获取一轮数据，返回股票记录列表，采集完毕返回空列表"""
        ...


# 统一字段说明 (16 字段)
# code       : str  股票代码
# name       : str  股票名称
# price      : float 最新价
# open       : float 今开
# high       : float 最高
# low        : float 最低
# prev_close : float 昨收
# change_amt : float 涨跌额
# change_pct : float 涨跌幅(%)
# volume     : float 成交量(股)
# amount     : float 成交额(元)
# bid        : float 竞买价 (sina 有, jsonl 无)
# ask        : float 竞卖价 (sina 有, jsonl 无)
# trade_date : str  行情日期 (sina 有, jsonl 无)
# trade_time : str  行情时间 (sina 有, jsonl 无)
# event_time : str  采集时间 (由 collector 统一设置)
# source     : str  数据来源 (由 collector 统一设置)
