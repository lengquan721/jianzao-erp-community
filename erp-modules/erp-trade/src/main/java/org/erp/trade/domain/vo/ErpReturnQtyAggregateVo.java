package org.erp.trade.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 已确认退货按原执行行聚合的数量投影。 */
@Data
public class ErpReturnQtyAggregateVo {

    private Long originalItemId;
    private BigDecimal returnedQty;
}
