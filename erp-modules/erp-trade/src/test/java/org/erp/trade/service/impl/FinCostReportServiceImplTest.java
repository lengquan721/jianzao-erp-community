package org.erp.trade.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.vo.DashboardAggregateVo;
import org.erp.trade.mapper.DashboardAggregateMapper;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleReturnMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * FinCostReportServiceImpl 单元测试（R20 口径）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class FinCostReportServiceImplTest {

    private static final String TENANT_ID = "700007";

    @Mock
    private DashboardAggregateMapper dashboardAggregateMapper;
    @Mock
    private ErpSaleReturnMapper saleReturnMapper;

    @Mock
    private ErpSaleItemMapper saleItemMapper;

    @Mock
    private IInventoryService inventoryService;

    private MockedStatic<TenantHelper> tenantMock;
    private FinCostReportServiceImpl service;

    @BeforeEach
    void setUp() {
        tenantMock = mockStatic(TenantHelper.class);
        tenantMock.when(TenantHelper::getTenantId).thenReturn(TENANT_ID);
        service = new FinCostReportServiceImpl(dashboardAggregateMapper, saleItemMapper, inventoryService);
        when(dashboardAggregateMapper.costLedgerSum(any(), any(), any(), any())).thenReturn(emptyAggregate());
        when(dashboardAggregateMapper.salesCostSummary(any(), any(), any())).thenReturn(emptyAggregate());
        when(saleItemMapper.countMissingCostBySaleWindow(any(), any(), any())).thenReturn(0L);
        when(inventoryService.totalStockAmount()).thenReturn(BigDecimal.ZERO);
    }

    @AfterEach
    void tearDown() {
        tenantMock.close();
    }

    @Test
    void report_sumsSalesCostAndTwoLedgerCostTypes_andSales() {
        // 数据库聚合依次返回 PRODUCTION_MATERIAL / OTHER_OUT / OTHER_EXPENSE = 9000/600/300。
        when(dashboardAggregateMapper.costLedgerSum(any(), any(), any(), any())).thenReturn(
            aggregate("9000.00", null), aggregate("600.00", null), aggregate("300.00", null));
        when(dashboardAggregateMapper.salesCostSummary(any(), any(), any()))
            .thenReturn(aggregate("35000.00", "16000.00"));

        var vo = service.report("2026-10");

        assertEquals("2026-10", vo.getMonth());
        assertEquals(0, new BigDecimal("35000.00").compareTo(vo.getSalesAmount()));
        assertEquals(0, new BigDecimal("16000.00").compareTo(vo.getSalesCost()));
        // 生产材料成本只作参考，不参与总成本
        assertEquals(0, new BigDecimal("9000.00").compareTo(vo.getProductionMaterialCost()));
        assertEquals(0, new BigDecimal("600.00").compareTo(vo.getOtherOutCost()));
        assertEquals(0, new BigDecimal("300.00").compareTo(vo.getOtherExpenseCost()));
        assertEquals(0, new BigDecimal("16900.00").compareTo(vo.getTotalCost()));
        assertEquals(0, new BigDecimal("18100.00").compareTo(vo.getGrossProfit()));
        // 毛利率 = 18100 / 35000 × 100 = 51.71
        assertEquals(0, new BigDecimal("51.71").compareTo(vo.getGrossMargin()));
    }

    @Test
    void report_生产材料成本不计入总成本() {
        when(dashboardAggregateMapper.costLedgerSum(any(), any(), any(), any())).thenReturn(
            aggregate("5000.00", null), emptyAggregate(), emptyAggregate());
        when(dashboardAggregateMapper.salesCostSummary(any(), any(), any()))
            .thenReturn(aggregate("10000.00", "0"));

        var vo = service.report("2026-10");

        assertEquals(0, new BigDecimal("5000.00").compareTo(vo.getProductionMaterialCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getTotalCost()));
        assertEquals(0, new BigDecimal("10000.00").compareTo(vo.getGrossProfit()));
    }

    @Test
    void report_销售成本为空视为0() {
        when(dashboardAggregateMapper.salesCostSummary(any(), any(), any()))
            .thenReturn(aggregate("5000.00", null));

        var vo = service.report("2026-10");

        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getSalesCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getTotalCost()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(vo.getGrossProfit()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void report_无销售与退货时毛利率为null(boolean hasReturnProjection) {
        when(dashboardAggregateMapper.salesReturnCostSummary(any(), any(), any()))
            .thenReturn(hasReturnProjection ? emptyAggregate() : null);

        var vo = service.report("2026-09");

        assertEquals(new BigDecimal("0.00"), vo.getSaleReturnAmount());
        assertEquals(new BigDecimal("0.00"), vo.getSaleReturnCostAmount());
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getSalesAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getTotalCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getGrossProfit()));
        // 销售收入为 0 → 毛利率不可计算（null，前端显示 '-'）
        assertNull(vo.getGrossMargin());
    }

    @Test
    void report_返回库存金额参考值() {
        when(inventoryService.totalStockAmount()).thenReturn(new BigDecimal("28.00"));

        var vo = service.report("2026-10");

        // 库存金额只作参考，不参与本期综合成本
        assertEquals(0, new BigDecimal("28.00").compareTo(vo.getStockAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getTotalCost()));
    }

    @Test
    void report_统计本期成本缺失销售明细行数() {
        when(dashboardAggregateMapper.salesCostSummary(any(), any(), any()))
            .thenReturn(aggregate("10000.00", "0"));
        when(saleItemMapper.countMissingCostBySaleWindow(any(), any(), any())).thenReturn(2L);

        var vo = service.report("2026-10");

        // 成本为 0 的销售明细行随报表返回，前端据此标「成本缺失」（DOC/26 §2.4 防线③）
        assertEquals(2, vo.getCostMissingCount().intValue());
    }

    @Test
    void report_无销售时成本缺失行数为0() {
        var vo = service.report("2026-10");

        assertEquals(0, vo.getCostMissingCount().intValue());
    }

    @Test
    void report_毛利率与销售单口径一致() {
        when(dashboardAggregateMapper.salesCostSummary(any(), any(), any()))
            .thenReturn(aggregate("10000.00", "2500.00"));

        var vo = service.report("2026-10");

        // 毛利 7500，销售 10000 → 75.00
        assertEquals(0, new BigDecimal("7500.00").compareTo(vo.getGrossProfit()));
        assertEquals(0, new BigDecimal("75.00").compareTo(vo.getGrossMargin()));
    }

    @Test
    void report_毛利率保留两位小数HALF_UP() {
        when(dashboardAggregateMapper.salesCostSummary(any(), any(), any()))
            .thenReturn(aggregate("999.99", "333.33"));

        var vo = service.report("2026-10");

        // (999.99 - 333.33) / 999.99 × 100 = 66.6667 → 66.67
        assertEquals(0, new BigDecimal("66.67").compareTo(vo.getGrossMargin()));
    }

    @Test
    void report_sumsReturnsOnceAndRoundsOnlyAfterNetCalculations() {
        Date from = monthStart(2026, 12);
        Date to = monthStart(2027, 1);
        when(dashboardAggregateMapper.costLedgerSum(TENANT_ID, "PRODUCTION_MATERIAL", from, to))
            .thenReturn(aggregate("500.005", null));
        when(dashboardAggregateMapper.costLedgerSum(TENANT_ID, "OTHER_OUT", from, to))
            .thenReturn(aggregate("3.333", null));
        when(dashboardAggregateMapper.costLedgerSum(TENANT_ID, "OTHER_EXPENSE", from, to))
            .thenReturn(aggregate("1.112", null));
        when(dashboardAggregateMapper.salesCostSummary(TENANT_ID, from, to))
            .thenReturn(aggregate("100.001", "60.004"));
        when(dashboardAggregateMapper.salesReturnCostSummary(TENANT_ID, from, to))
            .thenReturn(aggregate("20.005", "10.005"));
        when(saleItemMapper.countMissingCostBySaleWindow(TENANT_ID, from, to)).thenReturn(4L);
        when(inventoryService.totalStockAmount()).thenReturn(new BigDecimal("280.0123"));

        // 旧四参构造也走聚合，不得访问退货明细 Mapper。
        var vo = new FinCostReportServiceImpl(dashboardAggregateMapper, saleReturnMapper, saleItemMapper, inventoryService)
            .report("2026-12");

        assertEquals(new BigDecimal("20.01"), vo.getSaleReturnAmount());
        assertEquals(new BigDecimal("10.01"), vo.getSaleReturnCostAmount());
        assertEquals(new BigDecimal("80.00"), vo.getSalesAmount()); // 原精度净额 79.996
        assertEquals(new BigDecimal("60.00"), vo.getSalesCost()); // 仍展示回冲前销售成本
        assertEquals(new BigDecimal("500.01"), vo.getProductionMaterialCost());
        assertEquals(new BigDecimal("3.33"), vo.getOtherOutCost());
        assertEquals(new BigDecimal("1.11"), vo.getOtherExpenseCost());
        assertEquals(new BigDecimal("54.44"), vo.getTotalCost()); // 原精度综合成本 54.444
        assertEquals(new BigDecimal("25.55"), vo.getGrossProfit()); // 不等于两项展示值相减的 25.56
        assertEquals(new BigDecimal("31.94"), vo.getGrossMargin());
        assertEquals(new BigDecimal("280.0123"), vo.getStockAmount());
        assertEquals(4, vo.getCostMissingCount().intValue());

        verify(dashboardAggregateMapper).salesReturnCostSummary(TENANT_ID, from, to);
        verify(dashboardAggregateMapper).salesCostSummary(TENANT_ID, from, to);
        verify(dashboardAggregateMapper).costLedgerSum(TENANT_ID, "PRODUCTION_MATERIAL", from, to);
        verify(dashboardAggregateMapper).costLedgerSum(TENANT_ID, "OTHER_OUT", from, to);
        verify(dashboardAggregateMapper).costLedgerSum(TENANT_ID, "OTHER_EXPENSE", from, to);
        verify(saleItemMapper).countMissingCostBySaleWindow(TENANT_ID, from, to);
        verify(inventoryService).totalStockAmount();
        verifyNoMoreInteractions(dashboardAggregateMapper, saleItemMapper, inventoryService);
        verifyNoInteractions(saleReturnMapper);
    }

    @Test
    void report_preservesNegativeReturnSums() {
        when(dashboardAggregateMapper.salesCostSummary(any(), any(), any())).thenReturn(aggregate("100", "60"));
        when(dashboardAggregateMapper.salesReturnCostSummary(any(), any(), any()))
            .thenReturn(aggregate("-20.005", "-10.005"));

        var vo = service.report("2026-10");

        assertEquals(new BigDecimal("-20.01"), vo.getSaleReturnAmount());
        assertEquals(new BigDecimal("-10.01"), vo.getSaleReturnCostAmount());
        assertEquals(new BigDecimal("120.01"), vo.getSalesAmount());
        assertEquals(new BigDecimal("70.01"), vo.getTotalCost());
        assertEquals(new BigDecimal("50.00"), vo.getGrossProfit());
        assertEquals(new BigDecimal("41.66"), vo.getGrossMargin());
    }

    @Test
    void report_nullReturnAmountIsZeroWithoutDiscardingReturnCost() {
        when(dashboardAggregateMapper.salesReturnCostSummary(any(), any(), any())).thenReturn(aggregate(null, "7.25"));

        var vo = service.report("2026-10");

        assertEquals(new BigDecimal("0.00"), vo.getSaleReturnAmount());
        assertEquals(new BigDecimal("7.25"), vo.getSaleReturnCostAmount());
        assertEquals(new BigDecimal("0.00"), vo.getSalesAmount());
        assertEquals(new BigDecimal("-7.25"), vo.getTotalCost());
        assertEquals(new BigDecimal("7.25"), vo.getGrossProfit());
        assertNull(vo.getGrossMargin());
    }

    @Test
    void report_nullReturnCostIsZeroAndNegativeNetSalesHasNoMargin() {
        when(dashboardAggregateMapper.salesReturnCostSummary(any(), any(), any())).thenReturn(aggregate("12.50", null));

        var vo = service.report("2026-10");

        assertEquals(new BigDecimal("12.50"), vo.getSaleReturnAmount());
        assertEquals(new BigDecimal("0.00"), vo.getSaleReturnCostAmount());
        assertEquals(new BigDecimal("-12.50"), vo.getSalesAmount());
        assertEquals(new BigDecimal("0.00"), vo.getTotalCost());
        assertEquals(new BigDecimal("-12.50"), vo.getGrossProfit());
        assertNull(vo.getGrossMargin());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void report_missingTenantIsRejectedBeforeAnyQuery(String tenantId) {
        tenantMock.when(TenantHelper::getTenantId).thenReturn(tenantId);

        ServiceException error = assertThrows(ServiceException.class, () -> service.report("2026-10"));

        assertEquals(40901, error.getCode());
        verifyNoInteractions(dashboardAggregateMapper, saleItemMapper, inventoryService);
    }

    @Test
    void report_invalidMonth_rejected() {
        assertThrows(RuntimeException.class, () -> service.report("2026/10"));
        assertThrows(RuntimeException.class, () -> service.report("abc"));
    }

    @Test
    void report_blankMonth_defaultsToCurrentMonth() {
        var vo = service.report(null);
        assertEquals(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")),
            vo.getMonth());
    }

    // ---------- 辅助 ----------

    private static Date monthStart(int year, int month) {
        return Date.from(LocalDate.of(year, month, 1).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private DashboardAggregateVo aggregate(String amount, String cost) {
        DashboardAggregateVo row = new DashboardAggregateVo();
        if (amount != null) {
            row.setAmount(new BigDecimal(amount));
        }
        if (cost != null) {
            row.setCost(new BigDecimal(cost));
        }
        return row;
    }

    private DashboardAggregateVo emptyAggregate() {
        return new DashboardAggregateVo();
    }
}
