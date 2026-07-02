package com.stock.api.controller;

import com.stock.api.common.ApiResponse;
import com.stock.api.model.dto.RankItemDTO;
import com.stock.api.model.dto.StockLatestDTO;
import com.stock.api.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockController {

    private final RedisService redisService;

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

    @GetMapping("/{code}")
    public ApiResponse<StockLatestDTO> stockDetail(@PathVariable String code) {
        StockLatestDTO data = redisService.getStockLatest(code);
        if (data == null) {
            return ApiResponse.error(404, "股票不存在: " + code);
        }
        return ApiResponse.success(data);
    }
}
