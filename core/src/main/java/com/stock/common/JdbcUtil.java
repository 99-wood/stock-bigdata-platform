package com.stock.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * JDBC 工具 —— 统一驱动加载 + 连接创建
 */
public final class JdbcUtil {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcUtil.class);

    static {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(
                    "MySQL JDBC 驱动 (mysql-connector-java-5.1.47.jar) 未在 classpath 上");
        }
    }

    private JdbcUtil() {}

    /** 创建 MySQL 连接（调用方负责 close） */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                Config.mysqlUrl(), Config.mysqlUser(), Config.mysqlPassword());
    }
}
