package com.stock.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for stock:system:status Hash — system runtime status.
 * Fields align with Redis Schema v2.5 §3.9.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemStatusDTO {

    // ---- Runtime ----
    private String status;        // starting / running / flushing / idle / error
    private String mode;          // normal / replay
    private String targetDate;    // target date or "all"
    private String startedAt;
    private Integer uptimeSeconds;
    private String heartbeatAt;

    // ---- Feature flags ----
    private String featureOds;
    private String featureDwd;
    private String featureRank;
    private String featureMarket;
    private String featureMinute;
    private String featureFlushMinute;
    private String featureFlushDaily;
    private String featureFlushdb;

    // ---- Data stats ----
    private String currentDate;
    private Integer redisKeys;
    private Integer minuteWindows;
    private Integer ohlcvCodes;
    private Integer rankUpCount;
    private Integer rankAmountCount;
    private Long batchCount;
    private Integer batchMs;

    // ---- Consumer ----
    private Long consumerLag;
    private Integer consumerPct;

    // ---- Flush ----
    private Integer flushWindowsDone;
    private Integer flushWindowsTotal;
    private Integer flushRows;
    private Integer flushElapsedSec;
    private String lastFlushAt;
    private String lastFlushType;

    // ---- Error ----
    private String lastError;
    private String lastErrorAt;

    // ---- Meta ----
    private String updatedAt;
}
