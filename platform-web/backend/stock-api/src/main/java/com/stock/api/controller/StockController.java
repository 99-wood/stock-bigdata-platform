package com.stock.api.controller;

import com.stock.api.common.ApiResponse;
import com.stock.api.model.dto.RankItemDTO;
import com.stock.api.model.dto.StockLatestDTO;
import com.stock.api.service.HistoryService;
import com.stock.api.service.RedisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final RedisService redisService;

    @Autowired(required = false)
    private HistoryService historyService;

    public StockController(RedisService redisService) {
        this.redisService = redisService;
    }

    @GetMapping("/top-up")
    public ApiResponse<List<RankItemDTO>> topUp(@RequestParam(defaultValue = "20") int count) {
        return ApiResponse.success(redisService.getTopUp(count));
    }

    @GetMapping("/top-down")
    public ApiResponse<List<RankItemDTO>> topDown(@RequestParam(defaultValue = "20") int count) {
        return ApiResponse.success(redisService.getTopDown(count));
    }

    @GetMapping("/top-amount")
    public ApiResponse<List<RankItemDTO>> topAmount(@RequestParam(defaultValue = "20") int count) {
        return ApiResponse.success(redisService.getTopAmount(count));
    }

    @GetMapping("/top-quant")
    public ApiResponse<List<RankItemDTO>> topQuant(@RequestParam(defaultValue = "20") int count) {
        return ApiResponse.success(redisService.getTopQuant(count));
    }

    @GetMapping
    public ApiResponse<List<StockLatestDTO>> listStocks(
            @RequestParam(required = false) String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            return ApiResponse.success(redisService.searchStocks(keyword));
        }
        return ApiResponse.success(redisService.getAllStocks());
    }

    @GetMapping("/anomaly")
    public ApiResponse<List<Map<String, Object>>> anomaly(
            @RequestParam(defaultValue = "amplitude") String type,
            @RequestParam(defaultValue = "10") int limit) {
        if (historyService == null) {
            return ApiResponse.error(503, "MySQL history not available (activate mysql profile)");
        }
        return ApiResponse.success(historyService.getAnomaly(type, limit));
    }

    @GetMapping("/{code}/history")
    public ApiResponse<List<Map<String, Object>>> stockHistory(
            @PathVariable String code,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "60") int limit) {
        if (historyService == null) {
            return ApiResponse.error(503, "MySQL history not available (activate mysql profile)");
        }
        if ("minute".equals(period) && date != null) {
            return ApiResponse.success(historyService.getMinuteHistory(code, date));
        }
        return ApiResponse.success(historyService.getDailyHistory(code, limit));
    }

    @PostMapping("/spark-batch")
    public ApiResponse<Map<String, Object>> sparkBatch(
            @RequestBody List<String> codes) {
        return ApiResponse.success(redisService.getSparkBatch(codes));
    }

    @GetMapping("/{code}/minutes")
    public ApiResponse<List<Map<String, Object>>> stockMinutes(
            @PathVariable String code,
            @RequestParam(required = false) String date) {
        // 1. Redis 实时分钟数据（当前）
        List<Map<String, Object>> redisData = redisService.getStockMinutes(code, date);
        // 2. 无 MySQL 则直接返回
        if (historyService == null) return ApiResponse.success(redisData);
        // 3. 拼接 MySQL 历史分钟数据（作为同一日的连续数据，追加在前）
        String queryDate = (date != null && !date.isEmpty()) ? date : historyService.getLatestMinuteDate(code);
        if (queryDate != null) {
            List<Map<String, Object>> mysqlData = historyService.getMinuteHistory(code, queryDate);
            redisData = appendHistory(redisData, mysqlData);
        }
        return ApiResponse.success(redisData);
    }

    /** MySQL 历史数据追加到 Redis 数据前面（同一天连续展示），按时间去重 */
    private List<Map<String, Object>> appendHistory(
            List<Map<String, Object>> redisData,
            List<Map<String, Object>> mysqlData) {
        if (mysqlData == null || mysqlData.isEmpty()) return redisData;
        if (redisData == null) redisData = new ArrayList<>();
        // 用 time(HH:mm) 去重，Redis 数据优先
        java.util.Set<String> times = new java.util.HashSet<>();
        for (Map<String, Object> r : redisData) {
            times.add(String.valueOf(r.getOrDefault("time", "")));
        }
        // Redis 在前，MySQL 历史数据追加在后（按时间连续展示）
        List<Map<String, Object>> result = new ArrayList<>(redisData);
        for (Map<String, Object> row : mysqlData) {
            String t = String.valueOf(row.getOrDefault("minute_time", ""));
            String time = t.length() >= 16 ? t.substring(11, 16) : t;
            if (!times.contains(time)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", time);
                m.put("open", row.get("open"));
                m.put("high", row.get("high"));
                m.put("low", row.get("low"));
                m.put("close", row.get("close"));
                m.put("vol", row.getOrDefault("volume", 0));
                result.add(m);
            }
        }
        return result;
    }

    @GetMapping("/{code}")
    public ApiResponse<StockLatestDTO> stockDetail(@PathVariable String code) {
        StockLatestDTO data = redisService.getStockLatest(code);
        if (data == null) {
            return ApiResponse.error(404, "股票不存在: " + code);
        }
        return ApiResponse.success(data);
    }
}
