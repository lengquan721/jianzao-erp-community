package org.erp.trade.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作台趋势 VO（近 12 个月曲线，DOC/18 §4）
 */
@Data
public class DashboardTrendVo {

    /**
     * 月份序列（从早到晚），yyyy-MM
     */
    private List<String> months = new ArrayList<>();

    /**
     * 销售额曲线（CONFIRMED biz_sale 按月合计）
     */
    private List<BigDecimal> sales = new ArrayList<>();

    /**
     * 综合成本曲线（fin_cost_ledger ACTIVE 三类合计）
     */
    private List<BigDecimal> cost = new ArrayList<>();

    /**
     * 收款曲线（CONFIRMED fin_receipt 按月带符号合计；negative=1 退款按 -amount）
     */
    private List<BigDecimal> receipts = new ArrayList<>();

    /**
     * 毛利曲线（sales − cost）
     */
    private List<BigDecimal> profit = new ArrayList<>();
}
