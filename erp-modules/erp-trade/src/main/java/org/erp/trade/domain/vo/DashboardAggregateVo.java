package org.erp.trade.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 看板数据库聚合内部投影。不是明细 DTO：每行代表一个月份或一个往来单位。
 */
@Data
public class DashboardAggregateVo {

    private String month;
    private Long partyId;
    private BigDecimal amount;
    private BigDecimal cost;
    private BigDecimal grossProfit;
    private BigDecimal receiptAmount;
    private Long docCount;
}
