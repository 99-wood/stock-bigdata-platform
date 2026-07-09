package com.stock.common;

import redis.clients.jedis.Jedis;

/**
 * Jedis 连接工厂 —— 统一 Redis 连接创建逻辑
 */
public final class RedisUtil {

    /** 连接超时（毫秒） */
    private static final int TIMEOUT_MS = 3000;

    private RedisUtil() {}

    /** 创建 Jedis 连接（调用方负责 close） */
    public static Jedis newJedis() {
        Jedis jedis = new Jedis(Config.redisHost(), Config.redisPort(), TIMEOUT_MS);
        String pwd = Config.redisPassword();
        if (pwd != null && !pwd.isEmpty()) {
            jedis.auth(pwd);
        }
        return jedis;
    }
}
