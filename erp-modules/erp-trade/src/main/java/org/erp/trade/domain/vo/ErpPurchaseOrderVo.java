package org.erp.trade.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 采购订单视图对象（DOC/31 §5.5.1、§10.2）
 *
 * <p>列表额外回传执行进度四列（订单量/已执行/已关闭/未交），详情回传明细与执行记录。
 */
@Data
public class ErpPurchaseOrderVo {

    private Long id;

    private String no;

    private Long supplierId;

    private String supplierName;

    private Date bizDate;

    /**
     * 派生交期：最早未交行交期，recalc 覆盖写
     */
    private Date dueDate;

    private String status;

    /**
     * 状态中文文案（org.erp.common.enums.StatusText.bill）
     */
    private String statusText;

    private String execStatus;

    /**
     * 执行进度中文文案（org.erp.common.enums.StatusText.exec）
     */
    private String execStatusText;

    private String supplierOrderNo;

    private Long buyerId;

    private String buyerName;

    private String contactName;

    private String contactPhone;

    private Integer allowDelayDays;

    private BigDecimal overTolPct;

    private String taxType;

    private BigDecimal taxRate;

    /**
     * 订单金额（不含运杂费）=Σ行 amount
     */
    private BigDecimal goodsAmount;

    private String remark;

    private Date confirmedAt;

    private Date withdrawnAt;

    private Date voidedAt;

    private Date createTime;

    /**
     * 订单量合计（列表列，详情同样回填）
     */
    private BigDecimal totalQty;

    /**
     * 已执行量合计（净额）
     */
    private BigDecimal executedQty;

    /**
     * 已关闭量合计
     */
    private BigDecimal closedQty;

    /**
     * 未交量合计 = Σ(qty − executed − closed)，负数截断为 0
     */
    private BigDecimal remainQty;

    /**
     * 已执行金额（确认入库正数、确认退货负数，含执行单运杂费）；列表与详情均直接返回。
     */
    private BigDecimal executedAmount;

    private List<ItemVo> items;

    /**
     * 订单行视图。
     */
    @Data
    public static class ItemVo {

        private Long id;

        private Integer seq;

        private Long productId;

        private String productCode;

        private String productName;

        private String spec;

        private String unit;

        /**
         * 商品数量精度 0~3，前端据此设置数量输入精度
         */
        private Integer qtyScale;

        private BigDecimal qty;

        private BigDecimal price;

        private BigDecimal amount;

        private String taxType;

        private BigDecimal taxRate;

        private Date dueDate;

        private Date promisedDate;

        private BigDecimal executedQty;

        private BigDecimal closedQty;

        private Integer closedFlag;

        private String closeReason;

        /**
         * 未交量 = max(0, qty − executed − closed)，不落库
         */
        private BigDecimal remain;

        private String remark;
    }

    /**
     * 执行记录（执行单页签 2）：入库单/退货单各一行。
     */
    @Data
    public static class ExecRecordVo {

        private Long id;

        private String no;

        /**
         * RECEIPT=入库，RETURN=退货
         */
        private String type;

        /**
         * 类型中文文案：入库/退货
         */
        private String typeText;

        private Date bizDate;

        /**
         * 该单挂到本订单行的数量合计
         */
        private BigDecimal qty;

        /** 确认入库金额为正、确认退货金额为负。 */
        private BigDecimal amount;

        private String status;

        private String statusText;
    }
}
