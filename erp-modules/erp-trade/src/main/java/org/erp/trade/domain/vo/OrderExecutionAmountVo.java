package org.erp.trade.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单执行金额聚合：确认执行单金额为正，确认退货单金额为负。
 */
@Data
public class OrderExecutionAmountVo {

    private Long orderId;
    private BigDecimal amount;
}
