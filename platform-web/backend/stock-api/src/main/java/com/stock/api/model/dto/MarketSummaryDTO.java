package com.stock.api.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MarketSummaryDTO {
    @JsonProperty("stat_time")
    private String statTime;
    @JsonProperty("total_stocks")
    private Integer totalStocks;
    @JsonProperty("up_count")
    private Integer upCount;
    @JsonProperty("down_count")
    private Integer downCount;
    @JsonProperty("flat_count")
    private Integer flatCount;
    @JsonProperty("avg_change_pct")
    private Double avgChangePct;
    @JsonProperty("total_volume")
    private Long totalVolume;
    @JsonProperty("total_amount")
    private Double totalAmount;
}
