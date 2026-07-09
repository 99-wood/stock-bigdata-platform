package com.stock.common;

/**
 * Redis key 常量 —— 所有模块统一引用，避免字符串散落
 */
public final class RedisKeys {

    private RedisKeys() {}

    /** 个股 OHLCV 快照 (String, JSON) */
    public static final String OHLCV_PREFIX = "stock:quote:ohlcv:";

    /** OHLCV 代码集合 (Set) —— Lua 脚本自动维护，替代 KEYS */
    public static final String OHLCV_CODES = "stock:quote:ohlcv:codes";

    /** 市场概览 (Hash) */
    public static final String MARKET_SUMMARY = "stock:market:summary";

    /** 分钟 OHLCV 前缀 (Hash per code:window) */
    public static final String MINUTE_PREFIX = "stock:minute:";

    /** 所有 5 分钟窗口集合 (Set) */
    public static final String MINUTE_WINDOWS = "stock:minute:windows";

    /** 某窗口下的股票代码集合 (Set): 后缀 + window */
    public static final String MINUTE_CODES_PREFIX = "stock:minute:codes:";

    /** 涨幅榜 ZSet (score=change_pct) */
    public static final String RANK_UP     = "stock:rank:up";

    /** 成交额榜 ZSet (score=amount) */
    public static final String RANK_AMOUNT = "stock:rank:amount";

    // ---- 辅助方法 ----

    /** stock:quote:ohlcv:{code} */
    public static String ohlcvKey(String code) {
        return OHLCV_PREFIX + code;
    }

    /** stock:minute:{code}:{window} */
    public static String minuteKey(String code, String window) {
        return MINUTE_PREFIX + code + ":" + window;
    }

    /** stock:minute:codes:{window} */
    public static String minuteCodesKey(String window) {
        return MINUTE_CODES_PREFIX + window;
    }
}
