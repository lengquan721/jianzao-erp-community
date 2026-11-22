package org.erp.stock.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 单商品全仓数量合计，不是主仓快照。 */
@Data
public class ErpStockQuantityVo {
    private Long productId;
    private BigDecimal qty;
}
