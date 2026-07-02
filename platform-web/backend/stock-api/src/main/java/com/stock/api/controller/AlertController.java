package com.stock.api.controller;

import com.stock.api.common.ApiResponse;
import com.stock.api.model.dto.AlertDTO;
import com.stock.api.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final RedisService redisService;

    @GetMapping("/latest")
    public ApiResponse<List<AlertDTO>> latest(@RequestParam(defaultValue = "50") int count) {
        return ApiResponse.success(redisService.getLatestAlerts(count));
    }
}
