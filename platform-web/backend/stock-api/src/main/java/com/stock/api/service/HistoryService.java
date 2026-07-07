package com.stock.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;

/**
 * Historical K-line service — MySQL on-demand, paged.
 * Only active with spring profile "mysql".
 */
@Slf4j
@Service
@Profile("mysql")
public class HistoryService {

    @Autowired
    private DataSource dataSource;

    /** Get daily K-line, most recent N days. */
    public List<Map<String, Object>> getDailyHistory(String code, int limit) {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            return jdbc.queryForList(
                "SELECT trade_date, open, high, low, close, volume, amount, change_pct " +
                "FROM dws_stock_day WHERE code = ? ORDER BY trade_date DESC LIMIT ?",
                code, Math.min(limit, 365));
        } catch (Exception e) {
            log.warn("Failed to query daily history for {}: {}", code, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Sparkline: last N days close prices for batch of codes. */
    public Map<String, List<Map<String, Object>>> getSparkBatch(List<String> codes, int days) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) return result;
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            for (String code : codes) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT trade_date, close FROM dws_stock_day " +
                    "WHERE code = ? ORDER BY trade_date ASC LIMIT ?", code, days);
                if (!rows.isEmpty()) result.put(code, rows);
            }
        } catch (Exception e) {
            log.warn("Failed spark batch: {}", e.getMessage());
        }
        return result;
    }

    /** Get minute K-line for a specific date. */
    public List<Map<String, Object>> getMinuteHistory(String code, String date) {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            return jdbc.queryForList(
                "SELECT minute_time, open, high, low, close, volume, amount " +
                "FROM dws_stock_minute WHERE code = ? AND DATE(minute_time) = ? " +
                "ORDER BY minute_time ASC",
                code, date);
        } catch (Exception e) {
            log.warn("Failed to query minute history for {} date {}: {}", code, date, e.getMessage());
            return Collections.emptyList();
        }
    }
}
