package com.stock.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.api.model.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RedisService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /* MySQL fallback services — null when mysql profile is not active */
    @Autowired(required = false)
    private StockNameService stockNameService;
    @Autowired(required = false)
    private HistoryService historyService;

    public RedisService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    private static final String KEY_SYSTEM_STATUS = "stock:system:status";
    private static final String KEY_MARKET_SUMMARY = "stock:market:summary";
    private static final String KEY_RANK_UP = "stock:rank:up";
    private static final String KEY_RANK_DOWN = "stock:rank:down";
    private static final String KEY_RANK_AMOUNT = "stock:rank:amount";
    private static final String KEY_RANK_QUANT = "stock:rank:quant";
    private static final String KEY_ALERT_LATEST = "stock:alert:latest";
    private static final String KEY_QUOTE_PREFIX = "stock:quote:";
    private static final String KEY_OHLCV_CODES = "stock:quote:ohlcv:codes";
    private static final String KEY_OHLCV_PREFIX = "stock:quote:ohlcv:";
    private static final String KEY_MINUTE_WINDOWS = "stock:minute:windows";
    private static final String KEY_MINUTE_PREFIX = "stock:minute:";

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
     * Get stock detail: OHLCV (primary) + Level-2 depth (merged).
     * OHLCV JSON 自带 name，无需再从 MySQL 查询。
     */
    public StockLatestDTO getStockLatest(String code) {
        try {
            // 1. Read OHLCV JSON (primary: has name, price, open, high, low, volume, amount, change, change_pct)
            String ohlcvJson = stringRedisTemplate.opsForValue().get(KEY_OHLCV_PREFIX + code);
            StockLatestDTO dto;
            if (ohlcvJson != null && !ohlcvJson.isEmpty()) {
                dto = objectMapper.readValue(ohlcvJson, StockLatestDTO.class);
            } else {
                log.warn("OHLCV data not found for code: {}, trying Level-2 fallback", code);
                dto = new StockLatestDTO();
            }
            dto.setCode(code);

            // 2. Merge Level-2 depth (bid/ask/五档/status) from stock:quote:{code}
            String l2Json = stringRedisTemplate.opsForValue().get(KEY_QUOTE_PREFIX + code);
            if (l2Json != null && !l2Json.isEmpty()) {
                StockLatestDTO l2 = objectMapper.readValue(l2Json, StockLatestDTO.class);
                mergeLevel2(dto, l2);
            }

            // 3. MySQL name fallback (if OHLCV didn't have name)
            if (dto.getName() == null && stockNameService != null) {
                dto.setName(stockNameService.getName(code));
            }

            // 4. MySQL data fallback — Redis 归档/清库时从 dws_stock_day 补全
            if (dto.getPrice() == null && historyService != null) {
                List<Map<String, Object>> rows = historyService.getDailyHistory(code, 1);
                if (rows != null && !rows.isEmpty()) {
                    Map<String, Object> row = rows.get(0);
                    dto.setPrice(toDouble(row.get("open")));  // use open as current price fallback
                    dto.setOpen(toDouble(row.get("open")));
                    dto.setHigh(toDouble(row.get("high")));
                    dto.setLow(toDouble(row.get("low")));
                    dto.setPrevClose(toDouble(row.get("close")));
                    Object vol = row.get("volume"); if (vol != null) dto.setVolume(toLong(vol));
                    Object amt = row.get("amount"); if (amt != null) dto.setAmount(toLong(amt));
                    Object pct = row.get("change_pct"); if (pct != null) dto.setChangePct(toDouble(pct));
                    Object td = row.get("trade_date"); if (td != null) dto.setTradeDate(String.valueOf(td));
                    if (dto.getName() == null && stockNameService != null) dto.setName(stockNameService.getName(code));
                    log.debug("MySQL fallback for code={}", code);
                }
            }

            return dto;
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize stock JSON for code: {}", code, e);
            return null;
        } catch (Exception e) {
            log.error("Failed to get stock latest for code: {}", code, e);
            return null;
        }
    }

    /** Merge Level-2 fields into the DTO (non-null overwrite) */
    private void mergeLevel2(StockLatestDTO target, StockLatestDTO source) {
        if (source.getBid() != null) target.setBid(source.getBid());
        if (source.getAsk() != null) target.setAsk(source.getAsk());
        if (source.getB1v() != null) target.setB1v(source.getB1v());
        if (source.getB1p() != null) target.setB1p(source.getB1p());
        if (source.getB2v() != null) target.setB2v(source.getB2v());
        if (source.getB2p() != null) target.setB2p(source.getB2p());
        if (source.getB3v() != null) target.setB3v(source.getB3v());
        if (source.getB3p() != null) target.setB3p(source.getB3p());
        if (source.getB4v() != null) target.setB4v(source.getB4v());
        if (source.getB4p() != null) target.setB4p(source.getB4p());
        if (source.getB5v() != null) target.setB5v(source.getB5v());
        if (source.getB5p() != null) target.setB5p(source.getB5p());
        if (source.getS1v() != null) target.setS1v(source.getS1v());
        if (source.getS1p() != null) target.setS1p(source.getS1p());
        if (source.getS2v() != null) target.setS2v(source.getS2v());
        if (source.getS2p() != null) target.setS2p(source.getS2p());
        if (source.getS3v() != null) target.setS3v(source.getS3v());
        if (source.getS3p() != null) target.setS3p(source.getS3p());
        if (source.getS4v() != null) target.setS4v(source.getS4v());
        if (source.getS4p() != null) target.setS4p(source.getS4p());
        if (source.getS5v() != null) target.setS5v(source.getS5v());
        if (source.getS5p() != null) target.setS5p(source.getS5p());
        if (source.getStatus() != null) target.setStatus(source.getStatus());
        if (source.getTradeDate() != null && target.getTradeDate() == null) target.setTradeDate(source.getTradeDate());
        if (source.getTradeTime() != null && target.getTradeTime() == null) target.setTradeTime(source.getTradeTime());
    }

    /**
     * Get top N stocks by change_pct ascending (top gainers).
     * Score IS change_pct.
     */
    public List<RankItemDTO> getTopUp(int topN) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(KEY_RANK_UP, 0, topN - 1);
            return buildRankList(tuples, true);
        } catch (Exception e) {
            log.error("Failed to get top up rank", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get top N stocks by change_pct descending (top losers, most negative first).
     * Uses ZRANGE (ascending) since negative values are the biggest losers.
     * Score IS change_pct.
     */
    /** 跌幅榜 = stock:rank:up 中 score 最低（最负）的 topN */
    public List<RankItemDTO> getTopDown(int topN) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().rangeWithScores(KEY_RANK_UP, 0, topN - 1);
            return buildRankList(tuples, true);
        } catch (Exception e) {
            log.error("Failed to get top down rank", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get top N stocks by amount (turnover).
     * Score is amount, NOT change_pct.
     */
    public List<RankItemDTO> getTopAmount(int topN) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(KEY_RANK_AMOUNT, 0, topN - 1);
            return buildRankList(tuples, false);
        } catch (Exception e) {
            log.error("Failed to get top amount rank", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get top N stocks by quant score.
     * Score is quant, NOT change_pct.
     */
    public List<RankItemDTO> getTopQuant(int topN) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(KEY_RANK_QUANT, 0, topN - 1);
            return buildRankList(tuples, false);
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
    private List<RankItemDTO> buildRankList(Set<ZSetOperations.TypedTuple<String>> tuples, boolean scoreIsChangePct) {
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

        // MGET OHLCV + Level-2 in parallel
        List<String> ohlcvKeys = codes.stream().map(c -> KEY_OHLCV_PREFIX + c).collect(Collectors.toList());
        List<String> l2Keys = codes.stream().map(c -> KEY_QUOTE_PREFIX + c).collect(Collectors.toList());
        List<String> ohlcvList = stringRedisTemplate.opsForValue().multiGet(ohlcvKeys);
        List<String> l2List = stringRedisTemplate.opsForValue().multiGet(l2Keys);

        if (ohlcvList == null) return Collections.emptyList();

        // Zip codes, scores, OHLCV + Level-2 together
        List<RankItemDTO> result = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String ohlcvJson = (i < ohlcvList.size()) ? ohlcvList.get(i) : null;
            if (ohlcvJson == null || ohlcvJson.isEmpty()) {
                log.warn("Skipping rank item: no OHLCV for {}", codes.get(i));
                continue;
            }
            try {
                StockLatestDTO stock = objectMapper.readValue(ohlcvJson, StockLatestDTO.class);
                // Merge Level-2 bid/ask/status
                String l2Json = (l2List != null && i < l2List.size()) ? l2List.get(i) : null;
                Double bid = null, ask = null;
                String status = null;
                if (l2Json != null && !l2Json.isEmpty()) {
                    StockLatestDTO l2 = objectMapper.readValue(l2Json, StockLatestDTO.class);
                    bid = l2.getBid(); ask = l2.getAsk(); status = l2.getStatus();
                }
                RankItemDTO item = RankItemDTO.builder()
                        .code(codes.get(i))
                        .name(stock.getName())
                        .price(stock.getPrice())
                        .bid(bid)
                        .ask(ask)
                        .tradeDate(stock.getTradeDate())
                        .tradeTime(stock.getTradeTime())
                        .score(scores.get(i))
                        .changePct(stock.getChangePct())
                        .status(status)
                        .build();
                result.add(item);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize stock JSON for code: {}", codes.get(i), e);
            }
        }
        return result;
    }

    /**
     * Get all stocks via SMEMBERS stock:quote:ohlcv:codes + batch GET OHLCV JSON.
     * OHLCV JSON 自带 name，无需 SCAN 或 MySQL 慢查询。
     */
    public List<StockLatestDTO> getAllStocks() {
        try {
            // --- Step 1: SMEMBERS stock:quote:ohlcv:codes (替代 SCAN，无阻塞) ---
            Set<String> codes = stringRedisTemplate.opsForSet().members(KEY_OHLCV_CODES);
            if (codes == null || codes.isEmpty()) {
                log.warn("stock:quote:ohlcv:codes is empty");
                return Collections.emptyList();
            }
            List<String> codeList = new ArrayList<>(codes);
            log.info("SMEMBERS ohlcv:codes found {} codes, first 5 = {}",
                    codeList.size(), codeList.stream().limit(5).collect(Collectors.toList()));

            // --- Step 2: Batch GET stock:quote:ohlcv:{code} ---
            List<StockLatestDTO> stocks = new ArrayList<>();
            int successCount = 0, nullCount = 0, parseFailCount = 0;

            for (int i = 0; i < codeList.size(); i += 100) {
                int end = Math.min(i + 100, codeList.size());
                List<String> batchCodes = codeList.subList(i, end);
                List<String> keys = batchCodes.stream()
                        .map(c -> KEY_OHLCV_PREFIX + c)
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
                        continue;
                    }
                    try {
                        StockLatestDTO dto = objectMapper.readValue(json, StockLatestDTO.class);
                        dto.setCode(batchCodes.get(j));
                        stocks.add(dto);
                        successCount++;
                    } catch (JsonProcessingException e) {
                        parseFailCount++;
                        if (parseFailCount <= 3) {
                            log.error("JSON parse FAILED for code={}", batchCodes.get(j));
                        }
                    }
                }
            }

            log.info("getAllStocks: total={}, success={}, nullVal={}, parseFail={}. First: code={}, name={}, price={}",
                    codeList.size(), successCount, nullCount, parseFailCount,
                    stocks.isEmpty() ? "N/A" : stocks.get(0).getCode(),
                    stocks.isEmpty() ? "N/A" : stocks.get(0).getName(),
                    stocks.isEmpty() ? "N/A" : stocks.get(0).getPrice());

            // MySQL name fallback (OHLCV 通常已有 name，此为兜底)
            if (stockNameService != null) {
                for (StockLatestDTO dto : stocks) {
                    if (dto.getName() == null && dto.getCode() != null) {
                        String name = stockNameService.getName(dto.getCode());
                        if (name != null) dto.setName(name);
                    }
                }
            }

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

    /** 内存缓存：全量 spark 数据 + 时间轴 + 缓存时间戳，30s 过期 */
    private volatile Map<String, List<Double>> sparkCache;
    private volatile List<String> sparkTimes;
    private volatile long sparkCacheTime;

    /** Batch sparkline: returns { "times": [...], "data": { code: [...] } }. */
    public Map<String, Object> getSparkBatch(List<String> codes) {
        // 1. 命中缓存：直接返回
        Map<String, List<Double>> cached = sparkCache;
        List<String> cachedTimes = sparkTimes;
        if (cached != null && cachedTimes != null
                && System.currentTimeMillis() - sparkCacheTime < 30_000) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("times", cachedTimes);
            Map<String, List<Double>> data = new LinkedHashMap<>();
            for (String code : codes) {
                List<Double> v = cached.get(code);
                if (v != null) data.put(code, v);
            }
            result.put("data", data);
            return result;
        }

        // 2. 缓存未命中：从 minute hashes 重建
        computeSparkFromMinutes();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("times", sparkTimes);
        Map<String, List<Double>> data = new LinkedHashMap<>();
        for (String code : codes) {
            List<Double> v = sparkCache.get(code);
            if (v != null) data.put(code, v);
        }
        result.put("data", data);
        return result;
    }

    private void computeSparkFromMinutes() {
        sparkCache = new LinkedHashMap<>();
        sparkTimes = new ArrayList<>();
        try {
            Set<String> windows = stringRedisTemplate.opsForSet().members(KEY_MINUTE_WINDOWS);
            if (windows == null || windows.isEmpty()) return;
            String latestDate = windows.stream()
                    .map(w -> w.substring(0, 10)).max(Comparator.naturalOrder()).orElse(null);
            if (latestDate == null) return;
            List<String> sortedWindows = windows.stream()
                    .filter(w -> w.startsWith(latestDate)).sorted().collect(Collectors.toList());
            if (sortedWindows.isEmpty()) return;

            // 压缩时间轴：午休 11:30→13:00 压缩，仅压缩下午时段 (>=210)
            // 实际: 9:30=0  11:30=120  13:00=210  15:00=330
            // 压缩后: 9:30=0  11:30=120  13:00=135  15:00=255
            final int AFTERNOON_START = 210, LUNCH_COMPRESS = 75;
            sparkTimes = sortedWindows.stream()
                    .map(w -> {
                        String t = w.substring(11, 16);
                        int h = Integer.parseInt(t.substring(0, 2));
                        int m = Integer.parseInt(t.substring(3, 5));
                        int mins = (h - 9) * 60 + (m - 30);
                        if (mins < 0) mins = 0;
                        if (mins >= AFTERNOON_START) mins -= LUNCH_COMPRESS;
                        return String.valueOf(mins);
                    }).collect(Collectors.toList());

            Set<String> allCodes = stringRedisTemplate.opsForSet().members(KEY_OHLCV_CODES);
            if (allCodes == null || allCodes.isEmpty()) return;

            List<String> codeOrder = new ArrayList<>();
            List<Object> pipeResults = stringRedisTemplate.executePipelined(
                new org.springframework.data.redis.core.SessionCallback<Object>() {
                    public <K, V> Object execute(org.springframework.data.redis.core.RedisOperations<K, V> ops) {
                        for (String code : allCodes) {
                            for (String w : sortedWindows) {
                                ops.opsForHash().get((K) (KEY_MINUTE_PREFIX + code + ":" + w), "close");
                                codeOrder.add(code);
                            }
                        }
                        return null;
                    }
                });

            for (int i = 0; i < codeOrder.size() && i < pipeResults.size(); i++) {
                Object v = pipeResults.get(i);
                if (v != null) {
                    sparkCache.computeIfAbsent(codeOrder.get(i), k -> new ArrayList<>())
                         .add(Double.parseDouble(v.toString()));
                }
            }
            // 截断：某些代码可能因 pipeline 重复获取多于窗口数的数据
            final int winCount = sortedWindows.size();
            sparkCache.replaceAll((code, list) -> {
                if (list.size() > winCount) {
                    log.warn("Spark cache truncate: {} has {} closes for {} windows", code, list.size(), winCount);
                    return new ArrayList<>(list.subList(0, winCount));
                }
                return list;
            });
            sparkCacheTime = System.currentTimeMillis();
            log.info("Spark cache rebuilt: {} stocks × {} windows = {} results",
                    allCodes.size(), sortedWindows.size(), sparkCache.size());
        } catch (Exception e) {
            log.warn("Spark compute failed: {}", e.getMessage());
        }
    }

    /**
     * Get today's minute K-line for a stock.
     * Steps: SMEMBERS windows → filter today → sort → HGETALL each → delta volume.
     */
    public List<Map<String, Object>> getStockMinutes(String code, String date) {
        try {
            // 1. Get all windows, optionally filter by date, sort ascending
            Set<String> allWindows = stringRedisTemplate.opsForSet().members(KEY_MINUTE_WINDOWS);
            if (allWindows == null || allWindows.isEmpty()) {
                log.warn("No minute windows found in Redis");
                return Collections.emptyList();
            }

            List<String> windows;
            if (date != null && !date.isEmpty()) {
                windows = allWindows.stream()
                        .filter(w -> w.startsWith(date))
                        .sorted()
                        .collect(Collectors.toList());
            } else {
                windows = allWindows.stream().sorted().collect(Collectors.toList());
            }

            log.info("Minute windows: date={}, total={}, filtered={}, first 5 windows: {}",
                    date, allWindows.size(), windows.size(),
                    allWindows.stream().limit(5).collect(Collectors.toList()));

            if (windows.isEmpty()) return Collections.emptyList();

            // 2. Pipeline HGETALL using opsForHash for correct deserialization
            List<Object> rawResults = stringRedisTemplate.executePipelined(
                    new org.springframework.data.redis.core.SessionCallback<Object>() {
                        @Override
                        public <K, V> Object execute(org.springframework.data.redis.core.RedisOperations<K, V> ops) {
                            for (String w : windows) {
                                String key = KEY_MINUTE_PREFIX + code + ":" + w;
                                ops.opsForHash().entries((K) key);
                            }
                            return null;
                        }
                    });

            // 3. Parse results, compute delta volume
            List<Map<String, Object>> result = new ArrayList<>();
            long prevLastVol = -1;

            for (int i = 0; i < windows.size(); i++) {
                Object raw = rawResults.get(i);
                if (!(raw instanceof Map)) continue;

                @SuppressWarnings("unchecked")
                Map<Object, Object> rawMap = (Map<Object, Object>) raw;
                if (rawMap.isEmpty()) continue;

                Map<String, String> fields = new HashMap<>();
                for (Map.Entry<Object, Object> e : rawMap.entrySet()) {
                    fields.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }

                String time = windows.get(i).substring(11); // "HH:mm:00"
                double open = Double.parseDouble(fields.getOrDefault("open", "0"));
                double high = Double.parseDouble(fields.getOrDefault("high", "0"));
                double low = Double.parseDouble(fields.getOrDefault("low", "0"));
                double close = Double.parseDouble(fields.getOrDefault("close", "0"));
                long lastVol = Long.parseLong(fields.getOrDefault("last_vol", "0"));

                long vol = (prevLastVol < 0) ? 0 : (lastVol - prevLastVol);
                prevLastVol = lastVol;

                Map<String, Object> candle = new LinkedHashMap<>();
                candle.put("time", time);
                candle.put("open", open);
                candle.put("high", high);
                candle.put("low", low);
                candle.put("close", close);
                candle.put("vol", Math.max(0, vol));
                result.add(candle);
            }

            log.info("Stock minutes for code={}: windows={}, candles={}", code, windows.size(), result.size());
            return result;
        } catch (Exception e) {
            log.error("Failed to get stock minutes for code: {}", code, e);
            return Collections.emptyList();
        }
    }

    /**
     * Get latest trade time by sampling one code from OHLCV Set.
     * Returns "yyyy-MM-dd HH:mm:ss" or null if no data available.
     */
    public String getLatestTradeTime() {
        try {
            // SRANDMEMBER: random sample from OHLCV codes, O(1) non-blocking
            String code = stringRedisTemplate.opsForSet().randomMember(KEY_OHLCV_CODES);
            if (code == null) return null;

            String json = stringRedisTemplate.opsForValue().get(KEY_OHLCV_PREFIX + code);
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
    /** Treemap: top 20 gainers + top 20 losers by amount. */
    public Map<String, List<Map<String, Object>>> getTreemap() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("up", new ArrayList<>());
        result.put("down", new ArrayList<>());
        try {
            Set<String> codes = stringRedisTemplate.opsForSet().members(KEY_OHLCV_CODES);
            if (codes == null || codes.isEmpty()) return result;

            List<String> codeList = new ArrayList<>(codes);
            List<String> keys = codeList.stream().map(c -> KEY_OHLCV_PREFIX + c).collect(Collectors.toList());
            List<String> jsonList = stringRedisTemplate.opsForValue().multiGet(keys);
            if (jsonList == null) return result;

            List<StockLatestDTO> all = new ArrayList<>();
            for (int i = 0; i < jsonList.size(); i++) {
                if (jsonList.get(i) == null) continue;
                try {
                    StockLatestDTO dto = objectMapper.readValue(jsonList.get(i), StockLatestDTO.class);
                    dto.setCode(codeList.get(i));
                    all.add(dto);
                } catch (Exception e) { /* skip */ }
            }

            // Separate gainers / losers, sort each by amount desc
            List<StockLatestDTO> gainers = all.stream()
                .filter(s -> s.getChangePct() != null && s.getChangePct() > 0)
                .sorted((a, b) -> Long.compare(
                    b.getAmount() != null ? b.getAmount() : 0L,
                    a.getAmount() != null ? a.getAmount() : 0L))
                .limit(20).collect(Collectors.toList());

            List<StockLatestDTO> losers = all.stream()
                .filter(s -> s.getChangePct() != null && s.getChangePct() < 0)
                .sorted((a, b) -> Long.compare(
                    b.getAmount() != null ? b.getAmount() : 0L,
                    a.getAmount() != null ? a.getAmount() : 0L))
                .limit(20).collect(Collectors.toList());

            for (StockLatestDTO s : gainers) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", s.getCode());
                item.put("name", s.getName() != null ? s.getName() : s.getCode());
                item.put("changePct", Math.round(s.getChangePct() * 100.0) / 100.0);
                item.put("amount", s.getAmount() != null ? s.getAmount() : 0L);
                result.get("up").add(item);
            }
            for (StockLatestDTO s : losers) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", s.getCode());
                item.put("name", s.getName() != null ? s.getName() : s.getCode());
                item.put("changePct", Math.round(s.getChangePct() * 100.0) / 100.0);
                item.put("amount", s.getAmount() != null ? s.getAmount() : 0L);
                result.get("down").add(item);
            }
        } catch (Exception e) {
            log.warn("Treemap failed: {}", e.getMessage());
        }
        return result;
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return null; }
    }
    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private String getStringValue(Map<Object, Object> entries, String key) {
        Object value = entries.get(key);
        if (value == null) return null;
        return value.toString();
    }

    private Integer getIntValue(Map<Object, Object> entries, String key) {
        Object value = entries.get(key);
        if (value == null) return null;
        try { return Integer.parseInt(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Long getLongValue(Map<Object, Object> entries, String key) {
        Object value = entries.get(key);
        if (value == null) return null;
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Get system runtime status from stock:system:status Hash.
     * Returns null if the key doesn't exist (consumer not running).
     */
    public SystemStatusDTO getSystemStatus() {
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(KEY_SYSTEM_STATUS);
            if (entries == null || entries.isEmpty()) return null;

            return SystemStatusDTO.builder()
                .status(getStringValue(entries, "status"))
                .mode(getStringValue(entries, "mode"))
                .targetDate(getStringValue(entries, "target_date"))
                .startedAt(getStringValue(entries, "started_at"))
                .uptimeSeconds(getIntValue(entries, "uptime_seconds"))
                .heartbeatAt(getStringValue(entries, "heartbeat_at"))

                .featureOds(getStringValue(entries, "feature_ods"))
                .featureDwd(getStringValue(entries, "feature_dwd"))
                .featureRank(getStringValue(entries, "feature_rank"))
                .featureMarket(getStringValue(entries, "feature_market"))
                .featureMinute(getStringValue(entries, "feature_minute"))
                .featureFlushMinute(getStringValue(entries, "feature_flush_minute"))
                .featureFlushDaily(getStringValue(entries, "feature_flush_daily"))
                .featureFlushdb(getStringValue(entries, "feature_flushdb"))

                .currentDate(getStringValue(entries, "current_date"))
                .redisKeys(getIntValue(entries, "redis_keys"))
                .minuteWindows(getIntValue(entries, "minute_windows"))
                .ohlcvCodes(getIntValue(entries, "ohlcv_codes"))
                .rankUpCount(getIntValue(entries, "rank_up_count"))
                .rankAmountCount(getIntValue(entries, "rank_amount_count"))
                .batchCount(getLongValue(entries, "batch_count"))
                .batchMs(getIntValue(entries, "batch_ms"))

                .consumerLag(getLongValue(entries, "consumer_lag"))
                .consumerPct(getIntValue(entries, "consumer_pct"))

                .flushWindowsDone(getIntValue(entries, "flush_windows_done"))
                .flushWindowsTotal(getIntValue(entries, "flush_windows_total"))
                .flushRows(getIntValue(entries, "flush_rows"))
                .flushElapsedSec(getIntValue(entries, "flush_elapsed_sec"))
                .lastFlushAt(getStringValue(entries, "last_flush_at"))
                .lastFlushType(getStringValue(entries, "last_flush_type"))

                .lastError(getStringValue(entries, "last_error"))
                .lastErrorAt(getStringValue(entries, "last_error_at"))
                .updatedAt(getStringValue(entries, "updated_at"))
                .build();
        } catch (Exception e) {
            log.warn("Failed to get system status: {}", e.getMessage());
            return null;
        }
    }
}
