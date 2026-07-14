package com.stock.api.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTO matching stock:quote:{code} JSON — v1 Level-2 + v2 OHLCV extension.
 * Jackson FAIL_ON_UNKNOWN_PROPERTIES=false ensured forward-compatible.
 */
@Data
public class StockLatestDTO {

    // 从 Redis Key 中提取的股票代码
    private String code;

    // 股票名称 → 来自 stock:quote:ohlcv:{code} JSON 中的 name 字段
    private String name;

    // ---- v1 Level-2 五档 ----
    private Double bid;
    private Double ask;
    @JsonProperty("trade_date")
    private String tradeDate;
    @JsonProperty("trade_time")
    private String tradeTime;

    @JsonProperty("b1_v") private String b1v;   @JsonProperty("b1_p") private String b1p;
    @JsonProperty("b2_v") private String b2v;   @JsonProperty("b2_p") private String b2p;
    @JsonProperty("b3_v") private String b3v;   @JsonProperty("b3_p") private String b3p;
    @JsonProperty("b4_v") private String b4v;   @JsonProperty("b4_p") private String b4p;
    @JsonProperty("b5_v") private String b5v;   @JsonProperty("b5_p") private String b5p;

    @JsonProperty("s1_v") private String s1v;   @JsonProperty("s1_p") private String s1p;
    @JsonProperty("s2_v") private String s2v;   @JsonProperty("s2_p") private String s2p;
    @JsonProperty("s3_v") private String s3v;   @JsonProperty("s3_p") private String s3p;
    @JsonProperty("s4_v") private String s4v;   @JsonProperty("s4_p") private String s4p;
    @JsonProperty("s5_v") private String s5v;   @JsonProperty("s5_p") private String s5p;

    private String status;

    // ---- v2 OHLCV 扩展（待写入方追加，到达后自动反序列化） ----
    private Double price;            // 当前成交价
    private Double open;             // 开盘价
    private Double high;             // 最高价
    private Double low;              // 最低价
    @JsonProperty("pre_close")
    private Double prevClose;        // 昨收价
    private Long volume;             // 成交量（手）
    private Long amount;             // 成交额（万元）
    @JsonProperty("change")
    private Double changeAmt;        // 涨跌额
    @JsonProperty("change_pct")
    private Double changePct;        // 涨跌幅 %
}
