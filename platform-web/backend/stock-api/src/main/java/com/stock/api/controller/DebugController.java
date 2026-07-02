package com.stock.api.controller;

import com.stock.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@Slf4j
@Profile("dev")
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class DebugController {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Diagnostic: scan sample keys and show raw values.
     * GET /api/debug/redis-keys?pattern=*&sampleSize=20
     */
    @GetMapping("/redis-keys")
    public ApiResponse<Map<String, Object>> redisKeys(
            @RequestParam(defaultValue = "*") String pattern,
            @RequestParam(defaultValue = "20") int sampleSize) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // 1. Test connection
            String pong = stringRedisTemplate.getRequiredConnectionFactory()
                    .getConnection().ping();
            result.put("connected", pong != null);

            // 2. Total key count via KEYS (not for production, but fine for debug)
            Set<String> allKeys = stringRedisTemplate.keys("*");
            result.put("totalKeys", allKeys != null ? allKeys.size() : 0);

            // 2. Scan keys with given pattern
            List<String> sampleKeys = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(100)
                    .build();
            try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
                int count = 0;
                while (cursor.hasNext() && count < sampleSize) {
                    sampleKeys.add(cursor.next());
                    count++;
                }
            }
            result.put("pattern", pattern);
            result.put("sampleCount", sampleKeys.size());
            result.put("sampleKeys", sampleKeys);

            // 3. For each sample key, show type and preview
            List<Map<String, Object>> keyDetails = new ArrayList<>();
            for (String key : sampleKeys) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("key", key);
                try {
                    org.springframework.data.redis.connection.DataType type =
                            stringRedisTemplate.type(key);
                    String typeCode = type != null ? type.code() : "unknown";
                    detail.put("type", typeCode);

                    if ("string".equals(typeCode)) {
                        String value = stringRedisTemplate.opsForValue().get(key);
                        if (value != null) {
                            detail.put("valuePreview", value.length() > 200
                                    ? value.substring(0, 200) + "..." : value);
                        }
                    } else if ("hash".equals(typeCode)) {
                        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
                        detail.put("fieldCount", entries.size());
                        detail.put("sampleFields", entries.entrySet().stream()
                                .limit(10)
                                .map(e -> Map.of("field", String.valueOf(e.getKey()),
                                        "value", String.valueOf(e.getValue())))
                                .toList());
                    } else if ("zset".equals(typeCode)) {
                        Long size = stringRedisTemplate.opsForZSet().size(key);
                        detail.put("memberCount", size);
                    } else if ("list".equals(typeCode)) {
                        Long size = stringRedisTemplate.opsForList().size(key);
                        detail.put("length", size);
                    }
                } catch (Exception e) {
                    detail.put("error", e.getMessage());
                }
                keyDetails.add(detail);
            }
            result.put("keyDetails", keyDetails);

            // 4. Also try common patterns the app uses
            Map<String, Object> appPatterns = new LinkedHashMap<>();
            String[] knownPatterns = {
                "stock:latest:*", "stock:rank:*", "stock:market:*",
                "stock:alert:*", "stock:hot:*", "stock:*"
            };
            for (String p : knownPatterns) {
                int hits = countKeys(p);
                appPatterns.put(p + " (hits)", hits);
            }
            result.put("appPatterns", appPatterns);

        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Debug redis-keys failed", e);
        }

        return ApiResponse.success(result);
    }

    private int countKeys(String pattern) {
        try {
            int count = 0;
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    cursor.next();
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }
}
