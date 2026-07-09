package com.stock.api.controller;

import com.stock.api.common.ApiResponse;
import com.stock.api.model.dto.MarketSummaryDTO;
import com.stock.api.service.HistoryService;
import com.stock.api.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final RedisService redisService;

    @Autowired(required = false)
    private HistoryService historyService;

    @GetMapping("/summary")
    public ApiResponse<MarketSummaryDTO> summary() {
        MarketSummaryDTO data = redisService.getMarketSummary();
        return ApiResponse.success(data);
    }

    @GetMapping("/summary-history")
    public ApiResponse<List<Map<String, Object>>> summaryHistory(
            @RequestParam(defaultValue = "60") int limit) {
        if (historyService == null) {
            return ApiResponse.error(503, "MySQL not available (activate mysql profile)");
        }
        return ApiResponse.success(historyService.getSummaryHistory(limit));
    }

    @GetMapping("/treemap")
    public ApiResponse<Map<String, List<Map<String, Object>>>> treemap() {
        return ApiResponse.success(redisService.getTreemap());
    }
}
