package org.erp.trade.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 往来单位当月明细 VO（工作台「本月往来明细」卡片）
 *
 * 口径：只统计 CONFIRMED 单据，bizDate 落在自然月 [月初 00:00, 下月初 00:00)；
 * CUSTOMER 取销售单与收款单，SUPPLIER 取采购单与付款单。
 */
@Data
public class DashboardPartnerMonthVo {

    /**
     * 往来单位类型：CUSTOMER=客户，SUPPLIER=供货商
     */
    private String partyType;

    /**
     * 往来单位 ID
     */
    private Long partyId;

    /**
     * 往来单位名称
     */
    private String partyName;

    /**
     * 统计月份，yyyy-MM
     */
    private String month;

    /**
     * 本月单据数（客户=销售单数，供货商=采购单数）
     */
    private Long docCount;

    /**
     * 本月销售金额（客户口径，采购单据为 0）
     */
    private BigDecimal salesAmount;

    /**
     * 本月销售毛利（客户口径，采购单据为 0）
     */
    private BigDecimal grossProfit;

    /**
     * 本月销售毛利率百分数（客户口径，无销售时为 null）
     */
    private BigDecimal grossMargin;

    /**
     * 本月采购金额（含运杂费，供货商口径，客户为 0）
     */
    private BigDecimal purchaseAmount;

    /**
     * 本月收款（客户口径；negative=1 退款按负数）
     */
    private BigDecimal receiptAmount;

    /**
     * 本月付款（供货商口径）
     */
    private BigDecimal paymentAmount;

    /**
     * 本月单据明细（含收付款单，按业务日期倒序）
     */
    private List<Doc> docs = new ArrayList<>();

    @Data
    public static class Doc {
        /**
         * SALE/PURCHASE/RECEIPT/PAYMENT
         */
        private String type;

        private String no;

        private Date bizDate;

        /**
         * 单据金额：销售单=销售金额，采购单=含运杂费合计，收付款单=收付金额
         */
        private BigDecimal amount;

        /**
         * 销售单毛利，其余类型为 null
         */
        private BigDecimal grossProfit;

        private String remark;
    }
}
