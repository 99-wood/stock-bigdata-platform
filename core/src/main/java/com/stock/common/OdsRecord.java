package com.stock.common;

import java.io.Serializable;

/**
 * ODS 层原始归档记录 —— Kafka 原始 JSON + 分区日期
 */
public class OdsRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tradeDate;
    private String rawJson;

    public OdsRecord() {}

    public OdsRecord(String tradeDate, String rawJson) {
        this.tradeDate = tradeDate;
        this.rawJson = rawJson;
    }

    public String getTradeDate() { return tradeDate; }
    public void setTradeDate(String tradeDate) { this.tradeDate = tradeDate; }
    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
}
