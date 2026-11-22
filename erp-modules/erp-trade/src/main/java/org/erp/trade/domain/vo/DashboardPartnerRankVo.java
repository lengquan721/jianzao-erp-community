package org.erp.trade.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 往来单位本月排行 VO（工作台「本月往来明细」卡片）
 *
 * 按本月发生额倒序列出各往来单位，前端点某行再按单位查单据明细。
 */
@Data
public class DashboardPartnerRankVo {

    /**
     * 往来单位类型：CUSTOMER=客户，SUPPLIER=供货商
     */
    private String partyType;

    /**
     * 统计月份，yyyy-MM
     */
    private String month;

    /**
     * 排行条目，按金额倒序
     */
    private List<RankItem> items = new ArrayList<>();

    @Data
    public static class RankItem {
        /**
         * 往来单位 ID
         */
        private Long partyId;

        /**
         * 往来单位名称
         */
        private String partyName;

        /**
         * 往来单位编码
         */
        private String partyCode;

        /**
         * 本月发生额（客户=销售金额，供货商=采购金额）
         */
        private BigDecimal amount;

        /**
         * 本月单据数（客户=销售单数，供货商=采购单数）
         */
        private Long docCount;

        /**
         * 本月毛利（客户口径，供货商为 0）
         */
        private BigDecimal grossProfit;

        /**
         * 本月毛利率百分数（仅客户口径有值，供货商为 null）
         */
        private BigDecimal grossMargin;

        /**
         * 本月收款（客户口径；negative=1 退款按负数）
         */
        private BigDecimal receiptAmount;

        /**
         * 本月付款（供货商口径）
         */
        private BigDecimal paymentAmount;
    }
}
