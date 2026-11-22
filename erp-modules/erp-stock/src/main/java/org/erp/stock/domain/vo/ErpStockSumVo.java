package org.erp.stock.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存汇总 VO：按商品类型聚合的数量与金额（看板用）
 */
@Data
public class ErpStockSumVo {

    /**
     * 数量合计 Σ qty
     */
    private BigDecimal qty;

    /**
     * 金额合计 Σ qty × avg_cost
     */
    private BigDecimal amount;
}
