package com.stock.api.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stock name lookup — cached from MySQL dim_stock via plain JDBC.
 * Only active with spring profile "mysql".
 */
@Slf4j
@Service
@Profile("mysql")
public class StockNameService {

    @Autowired
    private DataSource dataSource;

    private volatile Map<String, String> nameCache = Collections.emptyMap();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    @Scheduled(fixedDelay = 300_000) // 5 min
    public void refreshCache() {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT code, name FROM dim_stock");
            Map<String, String> map = new ConcurrentHashMap<>();
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                String name = (String) row.get("name");
                if (code != null && name != null) {
                    map.put(code, name);
                }
            }
            nameCache = map;
            log.info("StockNameService cache refreshed: {} entries", map.size());
        } catch (Exception e) {
            log.warn("Failed to refresh dim_stock cache: {}", e.getMessage());
        }
    }

    public String getName(String code) {
        if (code == null) return null;
        return nameCache.get(code);
    }

    public int getCacheSize() {
        return nameCache.size();
    }
}
