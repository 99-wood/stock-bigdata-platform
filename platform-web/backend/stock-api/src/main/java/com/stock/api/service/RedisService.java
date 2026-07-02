package com.stock.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.api.model.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_MARKET_SUMMARY = "stock:market:summary";
    private static final String KEY_RANK_UP = "stock:rank:up";
    private static final String KEY_RANK_DOWN = "stock:rank:down";
    private static final String KEY_RANK_AMOUNT = "stock:rank:amount";
    private static final String KEY_RANK_QUANT = "stock:rank:quant";
    private static final String KEY_ALERT_LATEST = "stock:alert:latest";
    private static final String KEY_STOCK_LATEST_PREFIX = "stock:quote:";

    /**
     * Get market summary from Redis hash.
     */
    public MarketSummaryDTO getMarketSummary() {
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(KEY_MARKET_SUMMARY);
            if (entries == null || entries.isEmpty()) {
                log.warn("Market summary hash is empty");
                return null;
            }

            MarketSummaryDTO dto = new MarketSummaryDTO();
            String statTime = getStringValue(entries, "stat_time");
            if (statTime != null) dto.setStatTime(statTime);

            String totalStocks = getStringValue(entries, "total_stocks");
            if (totalStocks != null) dto.setTotalStocks(Integer.parseInt(totalStocks));

            String upCount = getStringValue(entries, "up_count");
            if (upCount != null) dto.setUpCount(Integer.parseInt(upCount));

            String downCount = getStringValue(entries, "down_count");
            if (downCount != null) dto.setDownCount(Integer.parseInt(downCount));

            String flatCount = getStringValue(entries, "flat_count");
            if (flatCount != null) dto.setFlatCount(Integer.parseInt(flatCount));

            String avgChangePct = getStringValue(entries, "avg_change_pct");
            if (avgChangePct != null) dto.setAvgChangePct(Double.parseDouble(avgChangePct));

            String totalVolume = getStringValue(entries, "total_volume");
            if (totalVolume != null) dto.setTotalVolume(Long.parseLong(totalVolume));

            String totalAmount = getStringValue(entries, "total_amount");
            if (totalAmount != null) dto.setTotalAmount(Double.parseDouble(totalAmount));

            return dto;
        } catch (Exception e) {
            log.error("Failed to get market summary from Redis", e);
            return null;
        }
    }

    /**
     * Get latest stock data for a single stock code.
     */
    public StockLatestDTO getStockLatest(String code) {
        try {
            String json = stringRedisTemplate.opsForValue().get(KEY_STOCK_LATEST_PREFIX + code);
            if (json == null || json.isEmpty()) {
                log.warn("Stock latest data not found for code: {}", code);
                return null;
            }
            StockLatestDTO dto = objectMapper.readValue(json, StockLatestDTO.class);
            dto.setCode(code);  // code 不在 JSON 内，从 Key 提取
            return dto;
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize stock latest JSON for code: {}", code, e);
            return null;
        } catch (Exception e) {
            log.error("Failed to get stock latest for code: {}", code, e);
            return null;
        }
    }

    /**
     * Get top N stocks by change_pct ascending (top gainers).
     */
    public List<RankItemDTO> getTopUp(int topN) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(KEY_RANK_UP, 0, topN - 1);
            return buildRankList(tuples);
        } catch (Exception e) {
            log.error("Failed to get top up rank", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get top N stocks by change_pct descending (top losers, most negative first).
     * Uses ZRANGE (ascending) since negative values are the biggest losers.
     */
    public List<RankItemDTO> getTopDown(int topN) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().rangeWithScores(KEY_RANK_DOWN, 0, topN - 1);
            return buildRankList(tuples);
        } catch (Exception e) {
            log.error("Failed to get top down rank", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get top N stocks by amount (turnover).
     */
    public List<RankItemDTO> getTopAmount(int topN) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(KEY_RANK_AMOUNT, 0, topN - 1);
            return buildRankList(tuples);
        } catch (Exception e) {
            log.error("Failed to get top amount rank", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get top N stocks by quant score.
     */
    public List<RankItemDTO> getTopQuant(int topN) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(KEY_RANK_QUANT, 0, topN - 1);
            return buildRankList(tuples);
        } catch (Exception e) {
            log.error("Failed to get top quant rank", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get latest N alerts from Redis list.
     */
    public List<AlertDTO> getLatestAlerts(int count) {
        try {
            List<String> jsonList = stringRedisTemplate.opsForList().range(KEY_ALERT_LATEST, 0, count - 1);
            if (jsonList == null || jsonList.isEmpty()) {
                log.warn("No alerts found in Redis");
                return Collections.emptyList();
            }

            List<AlertDTO> alerts = new ArrayList<>();
            for (String json : jsonList) {
                if (json == null || json.isEmpty()) continue;
                try {
                    AlertDTO alert = objectMapper.readValue(json, AlertDTO.class);
                    alerts.add(alert);
                } catch (JsonProcessingException e) {
                    log.error("Failed to deserialize alert JSON: {}", json, e);
                }
            }
            return alerts;
        } catch (Exception e) {
            log.error("Failed to get latest alerts", e);
            return Collections.emptyList();
        }
    }

    /**
     * Build a rank list from ZSet tuples: MGET stock JSONs, then zip with scores.
     */
    private List<RankItemDTO> buildRankList(Set<ZSetOperations.TypedTuple<String>> tuples) {
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }

        // Extract codes and scores
        List<String> codes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null) {
                codes.add(tuple.getValue());
                scores.add(tuple.getScore() != null ? tuple.getScore() : 0.0);
            }
        }

        if (codes.isEmpty()) {
            return Collections.emptyList();
        }

        // MGET all stock latest JSONs
        List<String> redisKeys = codes.stream()
                .map(c -> KEY_STOCK_LATEST_PREFIX + c)
                .collect(Collectors.toList());
        List<String> jsonList = stringRedisTemplate.opsForValue().multiGet(redisKeys);

        if (jsonList == null) {
            return Collections.emptyList();
        }

        // Zip codes, scores, and JSONs together
        List<RankItemDTO> result = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String json = (i < jsonList.size()) ? jsonList.get(i) : null;
            if (json == null || json.isEmpty()) {
                log.warn("Skipping rank item with missing stock data for code: {}", codes.get(i));
                continue;
            }
            try {
                StockLatestDTO stock = objectMapper.readValue(json, StockLatestDTO.class);
                RankItemDTO item = RankItemDTO.builder()
                        .code(codes.get(i))
                        .bid(stock.getBid())
                        .ask(stock.getAsk())
                        .tradeDate(stock.getTradeDate())
                        .tradeTime(stock.getTradeTime())
                        .score(scores.get(i))
                        .status(stock.getStatus())
                        .build();
                result.add(item);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize stock JSON for code: {}", codes.get(i), e);
            }
        }
        return result;
    }

    /**
     * Get all available stock codes with basic info.
     * Uses SCAN to iterate stock:quote:* keys, then MGET to fetch in batches.
     */
    public List<StockLatestDTO> getAllStocks() {
        try {
            // --- Step 1: SCAN for all stock:quote:* keys ---
            List<String> codes = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(KEY_STOCK_LATEST_PREFIX + "*")
                    .count(100)
                    .build();
            try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    String code = key.substring(KEY_STOCK_LATEST_PREFIX.length());
                    codes.add(code);
                }
            }
            log.info("SCAN stock:quote:* found {} codes: first 5 = {}",
                    codes.size(), codes.stream().limit(5).collect(Collectors.toList()));

            if (codes.isEmpty()) {
                // Fallback: try KEYS command to confirm the pattern
                Set<String> fallbackKeys = stringRedisTemplate.keys(KEY_STOCK_LATEST_PREFIX + "*");
                log.warn("SCAN returned empty, but KEYS stock:quote:* found {} keys. " +
                         "Redis total keys (KEYS *): {}",
                         fallbackKeys != null ? fallbackKeys.size() : 0,
                         stringRedisTemplate.keys("*") != null ? stringRedisTemplate.keys("*").size() : 0);
                return Collections.emptyList();
            }

            // --- Step 2: MGET in batches ---
            List<StockLatestDTO> stocks = new ArrayList<>();
            int successCount = 0, nullCount = 0, parseFailCount = 0;
            String firstRawJson = null;

            for (int i = 0; i < codes.size(); i += 100) {
                int end = Math.min(i + 100, codes.size());
                List<String> batchCodes = codes.subList(i, end);
                List<String> keys = batchCodes.stream()
                        .map(c -> KEY_STOCK_LATEST_PREFIX + c)
                        .collect(Collectors.toList());
                List<String> jsonList = stringRedisTemplate.opsForValue().multiGet(keys);

                if (jsonList == null) {
                    log.warn("MGET returned null for batch {}", i / 100);
                    continue;
                }

                for (int j = 0; j < jsonList.size(); j++) {
                    String json = jsonList.get(j);
                    if (json == null || json.isEmpty()) {
                        nullCount++;
                        log.debug("MGET returned null/empty for key: stock:quote:{}", batchCodes.get(j));
                        continue;
                    }
                    if (firstRawJson == null) {
                        firstRawJson = json;
                    }
                    try {
                        StockLatestDTO dto = objectMapper.readValue(json, StockLatestDTO.class);
                        dto.setCode(batchCodes.get(j));  // code 从 Key 提取，不在 JSON 内
                        stocks.add(dto);
                        successCount++;
                    } catch (JsonProcessingException e) {
                        parseFailCount++;
                        if (parseFailCount <= 3) {
                            log.error("JSON parse FAILED for code={}, raw={}",
                                    batchCodes.get(j),
                                    json.length() > 300 ? json.substring(0, 300) : json);
                        }
                    }
                }
            }

            log.info("getAllStocks result: scanned={}, success={}, nullVal={}, parseFail={}. First DTO code={}, tradeDate={}, bid={}",
                    codes.size(), successCount, nullCount, parseFailCount,
                    stocks.isEmpty() ? "N/A" : stocks.get(0).getCode(),
                    stocks.isEmpty() ? "N/A" : stocks.get(0).getTradeDate(),
                    stocks.isEmpty() ? "N/A" : stocks.get(0).getBid());
            return stocks;

        } catch (Exception e) {
            log.error("Failed to get all stocks: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Search stocks by code keyword (case-insensitive).
     * Name lookup is not supported — use MySQL dim_stock for name queries.
     * @param keyword search keyword (matches code, case-insensitive)
     * @return matching stocks sorted by code
     */
    public List<StockLatestDTO> searchStocks(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getAllStocks();
        }
        String lowerKeyword = keyword.trim().toLowerCase();
        List<StockLatestDTO> all = getAllStocks();
        return all.stream()
                .filter(s -> s.getCode() != null && s.getCode().toLowerCase().contains(lowerKeyword))
                .sorted(Comparator.comparing(StockLatestDTO::getCode))
                .collect(Collectors.toList());
    }

    /**
     * Get latest trade time by scanning any stock:quote:* key via SCAN.
     * Returns "yyyy-MM-dd HH:mm:ss" or null if no data available.
     */
    public String getLatestTradeTime() {
        try {
            // SCAN instead of KEYS to avoid blocking Redis
            ScanOptions options = ScanOptions.scanOptions()
                    .match(KEY_STOCK_LATEST_PREFIX + "*")
                    .count(1)
                    .build();
            String firstKey = null;
            try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
                if (cursor.hasNext()) {
                    firstKey = cursor.next();
                }
            }
            if (firstKey == null) return null;

            String json = stringRedisTemplate.opsForValue().get(firstKey);
            if (json == null) return null;
            StockLatestDTO stock = objectMapper.readValue(json, StockLatestDTO.class);
            if (stock.getTradeDate() != null && stock.getTradeTime() != null) {
                return stock.getTradeDate() + " " + stock.getTradeTime();
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to get latest trade time", e);
            return null;
        }
    }

    /**
     * Helper to extract a string value from a Redis hash entries map.
     */
    private String getStringValue(Map<Object, Object> entries, String key) {
        Object value = entries.get(key);
        if (value == null) return null;
        return value.toString();
    }
}
