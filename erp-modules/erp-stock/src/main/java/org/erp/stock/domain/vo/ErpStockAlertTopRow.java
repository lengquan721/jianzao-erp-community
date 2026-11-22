package org.erp.stock.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/** SQL 投影：窗口计数是不受 TopN 截断的本类型总数。 */
@Data
public class ErpStockAlertTopRow {
    private Long productId;
    private String productName;
    private BigDecimal totalQty;
    private BigDecimal gap;
    private String alertType;
    private long alertCount;
}
