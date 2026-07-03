package com.stock.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 配置管理 —— 从 application.properties 加载
 *
 * 首次调用 load() 时读取 classpath 下的 application.properties，
 * 之后通过 getXxx() 方法获取配置值。
 */
public final class Config {

    private static final Properties PROPS = new Properties();
    private static volatile boolean loaded = false;

    private Config() {
    }

    /**
     * 加载配置文件（只会执行一次）
     */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try (InputStream in = Config.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application.properties 未找到，请从 application.properties.example 复制并填写真实配置");
            }
            PROPS.load(in);
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("配置文件加载失败", e);
        }
    }

    private static String get(String key) {
        if (!loaded) {
            load();
        }
        return PROPS.getProperty(key);
    }

    private static int getInt(String key) {
        String value = get(key);
        if (value == null) {
            throw new IllegalStateException("Missing required config: " + key);
        }
        return Integer.parseInt(value);
    }

    // ==================== Kafka ====================
    public static String kafkaBootstrapServers() { return get("kafka.bootstrap.servers"); }
    public static String kafkaTopic() { return get("kafka.topic"); }
    public static String kafkaGroupId() { return get("kafka.group.id"); }

    // ==================== HDFS ====================
    public static String hdfsUri() { return get("hdfs.uri"); }
    public static String hdfsOdsPath() { return get("hdfs.ods.path"); }
    public static String hdfsDwdPath() { return get("hdfs.dwd.path"); }
    public static String hdfsCheckpointPath() { return get("hdfs.checkpoint.path"); }

    // ==================== MySQL ====================
    public static String mysqlUrl() { return get("mysql.url"); }
    public static String mysqlUser() { return get("mysql.user"); }
    public static String mysqlPassword() { return get("mysql.password"); }

    // ==================== Redis ====================
    public static String redisHost() { return get("redis.host"); }
    public static int redisPort() { return getInt("redis.port"); }
    public static String redisPassword() { return get("redis.password"); }

    // ==================== Streaming ====================
    public static int batchDurationSeconds() { return getInt("streaming.batch.duration.seconds"); }
    public static int minuteWindowSeconds() { return getInt("streaming.minute.window.seconds"); }

    // ==================== Redis Pipeline ====================
    public static boolean redisPipelineEnabled() { return Boolean.parseBoolean(get("redis.pipeline.enabled")); }
}
