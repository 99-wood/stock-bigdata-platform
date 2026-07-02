package com.stock.api.controller;

import com.stock.api.common.ApiResponse;
import com.stock.api.model.dto.MarketSummaryDTO;
import com.stock.api.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final RedisService redisService;

    @GetMapping("/summary")
    public ApiResponse<MarketSummaryDTO> summary() {
        MarketSummaryDTO data = redisService.getMarketSummary();
        return ApiResponse.success(data);
    }
}
