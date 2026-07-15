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

    /** Get anomaly stocks: amplitude / volume_spike / limit_up_down. */
    public List<Map<String, Object>> getAnomaly(String type, int limit) {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            // Get latest trade date
            String latestDate = jdbc.queryForObject(
                "SELECT MAX(trade_date) FROM dws_stock_day", String.class);
            if (latestDate == null) return Collections.emptyList();

            switch (type) {
                case "amplitude":
                    return jdbc.queryForList(
                        "SELECT d.code, s.name, d.close AS price, d.change_pct, d.volume, d.amount, " +
                        "ROUND((d.high - d.low) / NULLIF(d.open, 0) * 100, 2) AS anomaly_val " +
                        "FROM dws_stock_day d LEFT JOIN dim_stock s ON d.code = s.code " +
                        "WHERE d.trade_date = ? ORDER BY anomaly_val DESC LIMIT ?",
                        latestDate, limit);
                case "volume_spike":
                    return jdbc.queryForList(
                        "SELECT a.code, s.name, a.close AS price, a.change_pct, " +
                        "a.volume AS vol_today, b.volume AS vol_yesterday, " +
                        "ROUND(a.volume / NULLIF(b.volume, 0), 2) AS anomaly_val " +
                        "FROM dws_stock_day a " +
                        "LEFT JOIN dws_stock_day b ON a.code = b.code AND b.trade_date = DATE_SUB(?, INTERVAL 1 DAY) " +
                        "LEFT JOIN dim_stock s ON a.code = s.code " +
                        "WHERE a.trade_date = ? AND b.volume > 0 " +
                        "ORDER BY anomaly_val DESC LIMIT ?",
                        latestDate, latestDate, limit);
                case "limit_up_down":
                    return jdbc.queryForList(
                        "SELECT d.code, s.name, d.close AS price, d.change_pct AS anomaly_val, " +
                        "d.volume, d.amount " +
                        "FROM dws_stock_day d LEFT JOIN dim_stock s ON d.code = s.code " +
                        "WHERE d.trade_date = ? " +
                        // 排除新股（C/N 前缀无涨跌停限制）
                        "AND s.name NOT LIKE 'C%' AND s.name NOT LIKE 'N%' AND ( " +
                        // 科创板+创业板: ±20%
                        "  ((d.code LIKE 'sh688%' OR d.code LIKE 'sz300%' OR d.code LIKE 'sz301%') " +
                        "   AND (d.change_pct >= 19.9 OR d.change_pct <= -19.9)) OR " +
                        // 主板: ±10%
                        "  (d.code NOT LIKE 'sh688%' AND d.code NOT LIKE 'sz300%' AND d.code NOT LIKE 'sz301%' " +
                        "   AND (d.change_pct >= 9.9 OR d.change_pct <= -9.9)) " +
                        ") ORDER BY ABS(d.change_pct) DESC LIMIT ?",
                        latestDate, limit);
                default:
                    return Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("Failed anomaly query type={}: {}", type, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get market summary history for trend chart. */
    public List<Map<String, Object>> getSummaryHistory(int limit) {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            return jdbc.queryForList(
                "SELECT stat_time, avg_change_pct, up_count, down_count, flat_count, total_stocks " +
                "FROM ads_market_summary ORDER BY stat_time ASC LIMIT ?", limit);
        } catch (Exception e) {
            log.warn("Failed summary history: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get the latest date that has minute data for a code. */
    public String getLatestMinuteDate(String code) {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            return jdbc.queryForObject(
                "SELECT DATE_FORMAT(MAX(minute_time), '%Y-%m-%d') FROM dws_stock_minute WHERE code = ?",
                String.class, code);
        } catch (Exception e) { return null; }
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
