package org.erp.stock.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库存流水视图对象
 */
@Data
public class ErpStockLogVo {

    private Long id;

    /**
     * OPENING/PURCHASE/PROD_IN/PROD_OUT/SALE/OTHER_OUT/OTHER_IN/CHECK/SALE_RETURN/PURCHASE_RETURN/QUICK_IN/VOID
     */
    private String bizType;

    private String bizNo;

    private Long bizId;

    private Long productId;

    private Long warehouseId;

    private BigDecimal qty;

    private BigDecimal unitCost;

    private BigDecimal amount;

    private String partnerType;

    private Long partnerId;

    private String partnerName;

    private BigDecimal balanceAfter;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bizDate;

    private Integer seq;
}
