package com.stock.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * 股票行情快照数据模型
 *
 * 从 Kafka 接收 11 个字段 + 消费端计算 2 个字段 = 共 13 个字段
 * 忽略 Kafka 中的 bid / ask 字段
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== Kafka 接收字段 ====================

    /** 股票代码，sh/sz 前缀，如 sh600519 */
    @JsonProperty("code")
    private String code;

    /** 股票名称，如 贵州茅台 */
    @JsonProperty("name")
    private String name;

    /** 最新成交价（元） */
    @JsonProperty("price")
    private double price;

    /** 今开盘（元） */
    @JsonProperty("open")
    private double open;

    /** 今日最高价（元） */
    @JsonProperty("high")
    private double high;

    /** 今日最低价（元） */
    @JsonProperty("low")
    private double low;

    /** 昨日收盘价（元） */
    @JsonProperty("prev_close")
    private double prevClose;

    /** 成交量（股） */
    @JsonProperty("volume")
    private double volume;

    /** 成交额（元） */
    @JsonProperty("amount")
    private double amount;

    /** API 返回的行情日期，如 20260629 */
    @JsonProperty("trade_date")
    private String tradeDate;

    /** API 返回的行情时间，如 150004 */
    @JsonProperty("trade_time")
    private String tradeTime;

    // ==================== 消费端计算字段 ====================

    /** 涨跌额（元）= price - prev_close */
    private double changeAmt;

    /** 涨跌幅（%）= change_amt / prev_close × 100 */
    private double changePct;

    // ==================== 构造方法 ====================

    public StockQuote() {
    }

    // ==================== 计算涨跌额和涨跌幅 ====================

    /**
     * 基于 price 和 prevClose 计算 changeAmt 和 changePct
     * 应在 JSON 反序列化后立即调用
     */
    public void calcChange() {
        if (prevClose != 0) {
            this.changeAmt = price - prevClose;
            this.changePct = (changeAmt / prevClose) * 100;
        }
    }

    // ==================== Getter / Setter ====================

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public double getOpen() {
        return open;
    }

    public void setOpen(double open) {
        this.open = open;
    }

    public double getHigh() {
        return high;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    public double getLow() {
        return low;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public double getPrevClose() {
        return prevClose;
    }

    public void setPrevClose(double prevClose) {
        this.prevClose = prevClose;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(String tradeDate) {
        this.tradeDate = tradeDate;
    }

    public String getTradeTime() {
        return tradeTime;
    }

    public void setTradeTime(String tradeTime) {
        this.tradeTime = tradeTime;
    }

    public double getChangeAmt() {
        return changeAmt;
    }

    public void setChangeAmt(double changeAmt) {
        this.changeAmt = changeAmt;
    }

    public double getChangePct() {
        return changePct;
    }

    public void setChangePct(double changePct) {
        this.changePct = changePct;
    }

    @Override
    public String toString() {
        return "StockQuote{" +
                "code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", price=" + price +
                ", changeAmt=" + String.format("%.2f", changeAmt) +
                ", changePct=" + String.format("%.2f", changePct) + "%" +
                '}';
    }
}
