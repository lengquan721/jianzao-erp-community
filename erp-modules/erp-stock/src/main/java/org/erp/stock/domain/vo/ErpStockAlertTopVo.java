package org.erp.stock.domain.vo;

import java.math.BigDecimal;
import java.util.List;

/** 预警总数 + 每种预警的 TopN，不代替完整预警列表。 */
public record ErpStockAlertTopVo(long lowCount, long highCount, List<Item> lowTop, List<Item> highTop) {
    public record Item(Long productId, String productName, BigDecimal totalQty, BigDecimal gap) {
    }
}
