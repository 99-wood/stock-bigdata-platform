package com.stock.api.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Sina Level-2 五档行情 DTO — 匹配 stock:quote:{code} 实际 JSON 格式.
 */
@Data
public class StockLatestDTO {

    // 从 Redis Key 中提取的股票代码
    private String code;

    // ---- 基础行情 ----
    private Double bid;                         // 买一价
    private Double ask;                         // 卖一价
    @JsonProperty("trade_date")
    private String tradeDate;                   // 交易日期 yyyy-MM-dd
    @JsonProperty("trade_time")
    private String tradeTime;                   // 交易时间 HH:mm:ss

    // ---- 买五档 (bid 1-5) ----
    @JsonProperty("b1_v")
    private String b1v;
    @JsonProperty("b1_p")
    private String b1p;
    @JsonProperty("b2_v")
    private String b2v;
    @JsonProperty("b2_p")
    private String b2p;
    @JsonProperty("b3_v")
    private String b3v;
    @JsonProperty("b3_p")
    private String b3p;
    @JsonProperty("b4_v")
    private String b4v;
    @JsonProperty("b4_p")
    private String b4p;
    @JsonProperty("b5_v")
    private String b5v;
    @JsonProperty("b5_p")
    private String b5p;

    // ---- 卖五档 (ask 1-5) ----
    @JsonProperty("s1_v")
    private String s1v;
    @JsonProperty("s1_p")
    private String s1p;
    @JsonProperty("s2_v")
    private String s2v;
    @JsonProperty("s2_p")
    private String s2p;
    @JsonProperty("s3_v")
    private String s3v;
    @JsonProperty("s3_p")
    private String s3p;
    @JsonProperty("s4_v")
    private String s4v;
    @JsonProperty("s4_p")
    private String s4p;
    @JsonProperty("s5_v")
    private String s5v;
    @JsonProperty("s5_p")
    private String s5p;

    // ---- 状态 ----
    private String status;                      // 00=正常
}
