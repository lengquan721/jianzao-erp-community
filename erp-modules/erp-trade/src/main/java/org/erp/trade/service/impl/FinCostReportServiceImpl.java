package org.erp.trade.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.vo.DashboardAggregateVo;
import org.erp.trade.domain.vo.FinCostReportVo;
import org.erp.trade.mapper.DashboardAggregateMapper;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleReturnMapper;
import org.erp.trade.service.IFinCostReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 综合成本/利润报表实现（R20）
 *
 * 口径（DOC/02 R20、DOC/11 §8、DOC/24 §6.6）：
 * - 综合成本 = 销售成本 − 退货成本回冲 + 其他出库成本 + 计入成本的其他支出；
 * - 生产材料成本仅作参考展示：已通过成品成本随销售出库计入，不再重复计入综合成本；
 * - 销售收入 = 已确认销售金额 − 已确认退货金额；
 * - 利润 = 销售收入 − 综合成本。
 */
@Service
public class FinCostReportServiceImpl implements IFinCostReportService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DashboardAggregateMapper dashboardAggregateMapper;
    private final ErpSaleItemMapper saleItemMapper;
    private final IInventoryService inventoryService;

    @Autowired
    public FinCostReportServiceImpl(DashboardAggregateMapper dashboardAggregateMapper, ErpSaleItemMapper saleItemMapper,
                                    IInventoryService inventoryService) {
        this.dashboardAggregateMapper = dashboardAggregateMapper;
        this.saleItemMapper = saleItemMapper;
        this.inventoryService = inventoryService;
    }

    /** 兼容旧调用方的四参构造；退货明细 Mapper 不再用于报表。 */
    public FinCostReportServiceImpl(DashboardAggregateMapper dashboardAggregateMapper, ErpSaleReturnMapper unusedSaleReturnMapper,
                                    ErpSaleItemMapper saleItemMapper, IInventoryService inventoryService) {
        this(dashboardAggregateMapper, saleItemMapper, inventoryService);
    }

    @Override
    @Transactional(readOnly = true)
    public FinCostReportVo report(String month) {
        String m = (month == null || month.isBlank())
            ? LocalDate.now().format(MONTH_FMT) : month;
        LocalDate first;
        try {
            first = LocalDate.parse(m + "-01");
        } catch (Exception e) {
            throw new ServiceException("账期格式必须是 yyyy-MM");
        }
        LocalDate next = first.plusMonths(1);
        Date from = Date.from(first.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date to = Date.from(next.atStartOfDay(ZoneId.systemDefault()).toInstant());

        String tenantId = currentTenantId();
        // 生产材料成本只作参考：材料成本已进入成品成本，销售时随 cost_amount 计入综合成本。
        BigDecimal material = sumLedger(tenantId, "PRODUCTION_MATERIAL", from, to);
        BigDecimal otherOut = sumLedger(tenantId, "OTHER_OUT", from, to);
        BigDecimal otherExpense = sumLedger(tenantId, "OTHER_EXPENSE", from, to);

        // 销售收入/成本按期间在数据库聚合，不把本期销售头明细装入 JVM。
        DashboardAggregateVo salesAggregate = dashboardAggregateMapper.salesCostSummary(tenantId, from, to);
        BigDecimal salesTotal = amount(salesAggregate);
        BigDecimal salesCost = cost(salesAggregate);
        // 成本缺失标识（DOC/26 §2.4 防线③）：按销售头期间连接计数，避免把所有销售 ID 装入 JVM 再构造大 IN。
        Long missing = saleItemMapper.countMissingCostBySaleWindow(tenantId, from, to);
        int costMissingCount = missing == null ? 0 : missing.intValue();
        // 退货汇总保留原符号和精度，在 Java 计算净额后再对展示值舍入。
        DashboardAggregateVo returnAggregate = dashboardAggregateMapper.salesReturnCostSummary(tenantId, from, to);
        BigDecimal returnAmount = amount(returnAggregate);
        BigDecimal returnCost = cost(returnAggregate);
        BigDecimal netSales = salesTotal.subtract(returnAmount);
        BigDecimal netSalesCost = salesCost.subtract(returnCost);
        BigDecimal totalCost = netSalesCost.add(otherOut).add(otherExpense);

        FinCostReportVo vo = new FinCostReportVo();
        vo.setMonth(m);
        vo.setSaleReturnAmount(returnAmount.setScale(2, RoundingMode.HALF_UP));
        vo.setSaleReturnCostAmount(returnCost.setScale(2, RoundingMode.HALF_UP));
        BigDecimal salesAmount = netSales.setScale(2, RoundingMode.HALF_UP);
        BigDecimal grossProfit = netSales.subtract(totalCost).setScale(2, RoundingMode.HALF_UP);
        vo.setSalesAmount(salesAmount);
        vo.setSalesCost(salesCost.setScale(2, RoundingMode.HALF_UP));
        vo.setProductionMaterialCost(material.setScale(2, RoundingMode.HALF_UP));
        vo.setOtherOutCost(otherOut.setScale(2, RoundingMode.HALF_UP));
        vo.setOtherExpenseCost(otherExpense.setScale(2, RoundingMode.HALF_UP));
        vo.setTotalCost(totalCost.setScale(2, RoundingMode.HALF_UP));
        vo.setGrossProfit(grossProfit);
        // 毛利率与销售单口径一致：profit*100/total，净销售收入非正时不可计算（null）
        vo.setGrossMargin(salesAmount.compareTo(BigDecimal.ZERO) > 0
            ? grossProfit.multiply(BigDecimal.valueOf(100)).divide(salesAmount, 2, RoundingMode.HALF_UP)
            : null);
        // 库存金额：期初/采购等入库形成的库存资产，体现在库存而非本期损益（DOC/26 §9.3）
        vo.setStockAmount(inventoryService.totalStockAmount());
        vo.setCostMissingCount(costMissingCount);
        return vo;
    }

    private String currentTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ServiceException("缺少租户上下文，拒绝生成成本报告", 40901);
        }
        return tenantId;
    }

    private BigDecimal sumLedger(String tenantId, String costType, Date from, Date to) {
        DashboardAggregateVo row = dashboardAggregateMapper.costLedgerSum(tenantId, costType, from, to);
        return amount(row);
    }

    private BigDecimal amount(DashboardAggregateVo row) {
        return row == null || row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
    }

    private BigDecimal cost(DashboardAggregateVo row) {
        return row == null || row.getCost() == null ? BigDecimal.ZERO : row.getCost();
    }
}
