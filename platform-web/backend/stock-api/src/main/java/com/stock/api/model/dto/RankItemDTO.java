package com.stock.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RankItemDTO {
    private String code;
    private Double bid;
    private Double ask;
    private String tradeDate;
    private String tradeTime;
    private Double score;
    private String status;
}
