package com.stock.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Lua 脚本加载 + SHA 缓存管理
 *
 * 脚本从 classpath 加载一次（static），SHA 按 Jedis 连接执行 SCRIPT LOAD。
 * 各 Executor JVM 独立加载各自的 SHA。Redis 重启导致 NOSCRIPT 时调用 resetAll()。
 */
public final class LuaScriptManager {

    private static final Logger LOG = LoggerFactory.getLogger(LuaScriptManager.class);

    private static volatile String ohlcvScript;
    private static volatile String ohlcvSha;
    private static volatile String minuteScript;
    private static volatile String minuteSha;

    private LuaScriptManager() {}

    // ============================================================
    // OHLCV Lua
    // ============================================================

    private static synchronized void loadOhlcvScript() {
        if (ohlcvScript != null) return;
        ohlcvScript = readResource("redis_ohlcv_upsert.lua");
        LOG.info("OHLCV Lua 加载成功, {} 字节", ohlcvScript.length());
    }

    /** 获取 OHLCV Lua SHA（首次调用时 SCRIPT LOAD） */
    public static String ensureOhlcvSha(Jedis jedis) {
        if (ohlcvSha != null) return ohlcvSha;
        synchronized (LuaScriptManager.class) {
            if (ohlcvSha != null) return ohlcvSha;
            loadOhlcvScript();
            ohlcvSha = jedis.scriptLoad(ohlcvScript);
            LOG.info("OHLCV Lua SCRIPT LOAD 成功, SHA={}", ohlcvSha);
            return ohlcvSha;
        }
    }

    // ============================================================
    // 分钟 Lua
    // ============================================================

    private static synchronized void loadMinuteScript() {
        if (minuteScript != null) return;
        minuteScript = readResource("redis_minute_upsert.lua");
        LOG.info("分钟 Lua 加载成功, {} 字节", minuteScript.length());
    }

    /** 获取分钟 Lua SHA（首次调用时 SCRIPT LOAD） */
    public static String ensureMinuteSha(Jedis jedis) {
        if (minuteSha != null) return minuteSha;
        synchronized (LuaScriptManager.class) {
            if (minuteSha != null) return minuteSha;
            loadMinuteScript();
            minuteSha = jedis.scriptLoad(minuteScript);
            LOG.info("分钟 Lua SCRIPT LOAD 成功, SHA={}", minuteSha);
            return minuteSha;
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    /** Redis 重启后脚本丢失时调用，下次 ensureXxxSha 会重新 SCRIPT LOAD */
    public static void resetAll() {
        ohlcvSha = null;
        minuteSha = null;
        LOG.warn("Lua SHA 已重置, 下次调用重新 SCRIPT LOAD");
    }

    private static String readResource(String name) {
        try (InputStream in = LuaScriptManager.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (in == null)
                throw new IllegalStateException(name + " 未找到");
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Lua 脚本加载失败: " + name, e);
        }
    }
}
