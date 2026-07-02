package com.stock.streaming;

import com.stock.common.Config;
import com.stock.common.StockQuote;
import org.apache.spark.api.java.JavaRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.util.*;

/**
 * 全市场状态追踪器（基于 Redis 持久化）
 *
 * 每 batch 接收部分股票行情，更新 Redis 中的全局状态：
 *   stock:state:{code} → JSON {code, name, price, changePct, volume, amount, ...}
 *
 * 状态持久在 Redis 中：
 *   - 重启: 从 Redis 直接恢复，不依赖 checkpoint
 *   - 每天零点: 自动重置，误差归零
 *   - 收敛: 随着 batch 累积，逐步覆盖全市场
 *
 * 基于状态快照实时计算：
 *   - market_summary（上涨家数/下跌家数/平盘家数）
 *   - rank:up / rank:down / rank:amount（排行榜）
 */
public final class MarketStateTracker {

    private static final Logger LOG = LoggerFactory.getLogger(MarketStateTracker.class);

    private static final String STATE_KEY_PREFIX = "stock:state:";
    private static final String KEY_MARKET_SUMMARY = "stock:market:summary";
    private static final String KEY_RANK_UP = "stock:rank:up";
    private static final String KEY_RANK_DOWN = "stock:rank:down";
    private static final String KEY_RANK_AMOUNT = "stock:rank:amount";
    private static final int TOP_N = 100;

    // 当前统计周期的日期 (yyyyMMdd)，用于每日重置
    private static String currentDay = "";

    private MarketStateTracker() {
    }

    /**
     * 每 batch 调用一次：更新 Redis 状态 → 基于全量状态重算排行榜和市场概览
     */
    public static void updateAndPublish(JavaRDD<StockQuote> parsedRDD) {
        List<StockQuote> batch = parsedRDD.collect();
        if (batch.isEmpty()) return;

        try (Jedis jedis = new Jedis(Config.redisHost(), Config.redisPort(), 3000)) {
            jedis.auth(Config.redisPassword());

            // 每日重置
            String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new Date());
            if (!today.equals(currentDay)) {
                resetState(jedis);
                currentDay = today;
                LOG.info("新交易日 {}, 状态已重置", today);
            }

            // 1. 增量更新 Redis 状态（Pipeline 批量写，减少 RTT）
            Pipeline pipe = jedis.pipelined();
            for (StockQuote q : batch) {
                String json = String.format(
                        "{\"code\":\"%s\",\"name\":\"%s\",\"price\":%.2f,\"changePct\":%.4f," +
                        "\"volume\":%.0f,\"amount\":%.2f,\"tradeDate\":\"%s\",\"tradeTime\":\"%s\"}",
                        q.getCode(), q.getName(), q.getPrice(), q.getChangePct(),
                        q.getVolume(), q.getAmount(), q.getTradeDate(), q.getTradeTime()
                );
                pipe.set(STATE_KEY_PREFIX + q.getCode(), json);
            }
            pipe.sync();

            // 2. 从 Redis 读取全量状态，计算排行榜 + 市场概览
            Set<String> keys = jedis.keys(STATE_KEY_PREFIX + "*");
            if (keys.isEmpty()) return;

            List<String> jsonList = jedis.mget(keys.toArray(new String[0]));
            long total = 0, up = 0, down = 0, flat = 0;
            double sumPct = 0, sumVol = 0, sumAmt = 0;

            // 排行榜: TreeMap 按 score 排序
            TreeMap<Double, Set<String>> upMap = new TreeMap<>(Comparator.reverseOrder());
            TreeMap<Double, Set<String>> downMap = new TreeMap<>();
            TreeMap<Double, Set<String>> amtMap = new TreeMap<>(Comparator.reverseOrder());

            for (String json : jsonList) {
                if (json == null) continue;
                String code = extract(json, "code");
                double changePct = Double.parseDouble(extract(json, "changePct"));
                double volume = Double.parseDouble(extract(json, "volume"));
                double amount = Double.parseDouble(extract(json, "amount"));

                total++;
                sumPct += changePct;
                sumVol += volume;
                sumAmt += amount;
                if (changePct > 0) up++;
                else if (changePct < 0) down++;
                else flat++;

                // 排行榜分组
                upMap.computeIfAbsent(changePct, k -> new HashSet<>()).add(code);
                downMap.computeIfAbsent(changePct, k -> new HashSet<>()).add(code);
                amtMap.computeIfAbsent(amount / 10000.0, k -> new HashSet<>()).add(code);
            }

            // 3. 写市场概览 (Hash)
            jedis.hset(KEY_MARKET_SUMMARY, "stat_time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            jedis.hset(KEY_MARKET_SUMMARY, "total_stocks", String.valueOf(total));
            jedis.hset(KEY_MARKET_SUMMARY, "up_count", String.valueOf(up));
            jedis.hset(KEY_MARKET_SUMMARY, "down_count", String.valueOf(down));
            jedis.hset(KEY_MARKET_SUMMARY, "flat_count", String.valueOf(flat));
            jedis.hset(KEY_MARKET_SUMMARY, "avg_change_pct", String.format("%.4f", total > 0 ? sumPct / total : 0));
            jedis.hset(KEY_MARKET_SUMMARY, "total_volume", String.valueOf((long) sumVol));
            jedis.hset(KEY_MARKET_SUMMARY, "total_amount", String.format("%.4f", sumAmt / 10000.0));

            // 4. 写三个排行榜 (ZSET)
            writeRankZSet(jedis, KEY_RANK_UP, upMap, TOP_N);
            writeRankZSet(jedis, KEY_RANK_DOWN, downMap, TOP_N);
            writeRankZSet(jedis, KEY_RANK_AMOUNT, amtMap, TOP_N);
            LOG.warn("MarketStateTracker: total={}, up={}, down={}, flat={}", total, up, down, flat);

        } catch (Exception e) {
            LOG.warn("MarketStateTracker 更新失败: {}", e.getMessage());
        }
    }

    private static void writeRankZSet(Jedis jedis, String key, TreeMap<Double, Set<String>> sorted, int limit) {
        jedis.del(key);
        int count = 0;
        for (Map.Entry<Double, Set<String>> entry : sorted.entrySet()) {
            for (String code : entry.getValue()) {
                if (count >= limit) break;
                jedis.zadd(key, entry.getKey(), code);
                count++;
            }
            if (count >= limit) break;
        }
    }

    private static String extract(String json, String key) {
        int start = json.indexOf("\"" + key + "\":") + key.length() + 3;
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        String val = json.substring(start, end).replace("\"", "").trim();
        return val;
    }

    private static void resetState(Jedis jedis) {
        Set<String> oldKeys = jedis.keys(STATE_KEY_PREFIX + "*");
        if (!oldKeys.isEmpty()) {
            jedis.del(oldKeys.toArray(new String[0]));
        }
        jedis.del(KEY_MARKET_SUMMARY, KEY_RANK_UP, KEY_RANK_DOWN, KEY_RANK_AMOUNT);
    }
}
