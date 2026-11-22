package org.erp.trade.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 工作台汇总 VO（DOC/12 §10、DOC/18）
 *
 * 全部口径只统计已确认（CONFIRMED/ACTIVE），撤回与作废不参与。
 */
@Data
public class DashboardSummaryVo {

    /**
     * 本月销售额（biz_sale CONFIRMED 按月 total_amount 合计）
     */
    private BigDecimal monthSales;

    /**
     * 未收账款（fin_ar_ap AR 非 VOID 的 Σ(amount − settled_amount)）
     */
    private BigDecimal unreceived;

    /**
     * 未付账款（fin_ar_ap AP 非 VOID 的 Σ(amount − settled_amount)）
     */
    private BigDecimal unpaid;

    /**
     * 成品库存数量（st_stock join md_product.type=PRODUCT Σ qty）
     */
    private BigDecimal finishedStockQty;

    /**
     * 成品库存金额（Σ qty × avg_cost）
     */
    private BigDecimal finishedStockAmount;

    /**
     * 原料库存数量（st_stock join md_product.type=MATERIAL Σ qty）
     */
    private BigDecimal materialStockQty;

    /**
     * 本月采购额（biz_purchase CONFIRMED 按月 total_amount 合计）
     */
    private BigDecimal monthPurchaseAmount;

    /**
     * 本月收款（fin_receipt CONFIRMED 按月带符号合计；negative=1 退款按 -amount）
     */
    private BigDecimal monthReceiptAmount;

    /**
     * 本月毛利（Σ biz_sale.gross_profit，CONFIRMED，本月）
     */
    private BigDecimal monthGrossProfit;

    /**
     * 本月销售毛利率 = Σgross_profit / Σtotal_amount × 100（2 位小数 HALF_UP）；
     * 本月无销售时为 null（不可计算）
     */
    private BigDecimal monthGrossMargin;

    /**
     * 待办：草稿单据数（销售/采购/收付款 DRAFT 合计）
     */
    private DraftTodos draftTodos = new DraftTodos();

    /**
     * 最近单据（销售/采购/收付款确认或创建时间倒序，前 10 条）
     */
    private List<RecentDoc> recentDocs = new ArrayList<>();

    @Data
    public static class DraftTodos {
        private Long saleDrafts;
        private Long purchaseDrafts;
        private Long receiptDrafts;
        private Long paymentDrafts;

        public Long total() {
            return nvl(saleDrafts) + nvl(purchaseDrafts)
                + nvl(receiptDrafts) + nvl(paymentDrafts);
        }

        private Long nvl(Long v) {
            return v == null ? 0L : v;
        }
    }

    @Data
    public static class RecentDoc {
        /**
         * SALE/PURCHASE/RECEIPT/PAYMENT
         */
        private String type;
        private String no;
        private Date bizDate;
        private BigDecimal amount;
        /**
         * 单据状态码：DRAFT/CONFIRMED/VOID（移动端展示真实状态，禁止前端自行兜底成已完成）
         */
        private String status;
        /**
         * 单据状态文案（后端单一来源 StatusText，前端优先直接展示）
         */
        private String statusText;
    }
}
