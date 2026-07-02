package com.stock.api.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AlertDTO {
    @JsonProperty("alert_type")
    private String alertType;
    private String code;
    private String name;
    @JsonProperty("curr_value")
    private Double currValue;
    private Double threshold;
    @JsonProperty("event_time")
    private String eventTime;
}
