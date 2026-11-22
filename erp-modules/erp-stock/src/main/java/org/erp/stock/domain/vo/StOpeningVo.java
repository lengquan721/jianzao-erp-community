package org.erp.stock.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 期初库存 VO
 */
@Data
public class StOpeningVo {

    private Long id;

    private Long productId;

    private String productCode;

    private String productName;

    private Long warehouseId;

    private String warehouseCode;

    private String warehouseName;

    private BigDecimal qty;

    /**
     * 数量精度 0~3（DOC/26 §10），前端据此设置数量输入精度
     */
    private Integer qtyScale;

    private BigDecimal unitCost;

    private String status;

    /**
     * 状态中文文案（后端回填，见 org.erp.common.enums.StatusText）
     */
    private String statusText;

    private Date createTime;
}
