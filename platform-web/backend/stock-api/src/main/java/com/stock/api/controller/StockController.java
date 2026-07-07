package com.stock.api.controller;

import com.stock.api.common.ApiResponse;
import com.stock.api.model.dto.RankItemDTO;
import com.stock.api.model.dto.StockLatestDTO;
import com.stock.api.service.HistoryService;
import com.stock.api.service.RedisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
    public ApiResponse<Map<String, List<Double>>> sparkBatch(
            @RequestBody List<String> codes) {
        return ApiResponse.success(redisService.getSparkBatch(codes));
    }

    @GetMapping("/{code}/minutes")
    public ApiResponse<List<Map<String, Object>>> stockMinutes(
            @PathVariable String code,
            @RequestParam(required = false) String date) {
        return ApiResponse.success(redisService.getStockMinutes(code, date));
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
