package com.stock.api.websocket;

import com.stock.api.model.dto.MarketSummaryDTO;
import com.stock.api.model.dto.RankItemDTO;
import com.stock.api.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketPushScheduler {

    private final RedisService redisService;
    private final SimpMessagingTemplate messagingTemplate;

    @Scheduled(fixedDelayString = "${platform.push.interval:5000}")
    public void pushMarketData() {
        try {
            // Push market summary
            MarketSummaryDTO summary = redisService.getMarketSummary();
            if (summary != null) {
                messagingTemplate.convertAndSend("/topic/market", summary);
            }

            // Push rank lists (top 20)
            List<RankItemDTO> topUp = redisService.getTopUp(20);
            messagingTemplate.convertAndSend("/topic/rank/up", topUp);

            List<RankItemDTO> topDown = redisService.getTopDown(20);
            messagingTemplate.convertAndSend("/topic/rank/down", topDown);

            List<RankItemDTO> topAmount = redisService.getTopAmount(20);
            messagingTemplate.convertAndSend("/topic/rank/amount", topAmount);

            // Push latest trade time
            String tradeTime = redisService.getLatestTradeTime();
            if (tradeTime != null) {
                messagingTemplate.convertAndSend("/topic/time", tradeTime);
            }

            log.debug("WebSocket push completed: market summary + 3 rank lists");
        } catch (Exception e) {
            log.error("WebSocket push failed", e);
        }
    }
}
