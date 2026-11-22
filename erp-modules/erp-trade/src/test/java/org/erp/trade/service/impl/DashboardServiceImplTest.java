package org.erp.trade.service.impl;

import org.erp.fin.domain.FinArAp;
import org.erp.fin.domain.FinCostLedger;
import org.erp.fin.domain.FinPayment;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinCostLedgerMapper;
import org.erp.fin.mapper.FinPaymentMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.stock.domain.ErpStock;
import org.erp.stock.domain.vo.ErpStockAlertTopRow;
import org.erp.stock.domain.vo.ErpStockSumVo;
import org.erp.stock.mapper.ErpStockAlertMapper;
import org.erp.stock.mapper.ErpStockMapper;
import org.erp.trade.domain.ErpPurchase;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.vo.DashboardPartnerMonthVo;
import org.erp.trade.domain.vo.DashboardPartnerRankVo;
import org.erp.trade.domain.vo.DashboardStockAlertVo;
import org.erp.trade.domain.vo.DashboardSummaryVo;
import org.erp.trade.domain.vo.DashboardTrendVo;
import org.erp.trade.domain.vo.DashboardAggregateVo;
import org.erp.trade.mapper.DashboardAggregateMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 工作台看板单测：口径正确性（VOID/REVERSED 排除、成品原料过滤、状态过滤）、
 * 最近单据排序截断、趋势月序列与参数校验。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceImplTest {

    @Mock
    private ErpSaleMapper saleMapper;
    @Mock
    private ErpPurchaseMapper purchaseMapper;
    @Mock
    private FinArApMapper arApMapper;
    @Mock
    private FinReceiptMapper receiptMapper;
    @Mock
    private FinPaymentMapper paymentMapper;
    @Mock
    private FinCostLedgerMapper costLedgerMapper;
    @Mock
    private ErpStockMapper stockMapper;

    @Mock
    private ErpStockAlertMapper stockAlertMapper;
    @Mock
    private ErpProductMapper productMapper;
    @Mock
    private ErpCustomerMapper customerMapper;
    @Mock
    private ErpSupplierMapper supplierMapper;
    @Mock
    private DashboardAggregateMapper dashboardAggregateMapper;

    private MockedStatic<TenantHelper> tenantMock;
    private DashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        tenantMock = mockStatic(TenantHelper.class);
        tenantMock.when(TenantHelper::getTenantId).thenReturn("000000");
        // MyBatis-Plus Lambda wrapper 需要实体 TableInfo 缓存（纯单测无 Spring 环境）
        initTableInfo(ErpSale.class);
        initTableInfo(ErpPurchase.class);
        initTableInfo(FinArAp.class);
        initTableInfo(FinReceipt.class);
        initTableInfo(FinPayment.class);
        initTableInfo(FinCostLedger.class);
        initTableInfo(ErpStock.class);
        initTableInfo(ErpProduct.class);
        service = new DashboardServiceImpl(saleMapper, purchaseMapper,
            arApMapper, receiptMapper, paymentMapper, costLedgerMapper, stockMapper, stockAlertMapper, productMapper,
            customerMapper, supplierMapper, dashboardAggregateMapper);
        lenient().when(saleMapper.selectList(any())).thenReturn(List.of());
        lenient().when(purchaseMapper.selectList(any())).thenReturn(List.of());
        lenient().when(arApMapper.sumUnsettledByPartyType(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(receiptMapper.selectList(any())).thenReturn(List.of());
        lenient().when(paymentMapper.selectList(any())).thenReturn(List.of());
        lenient().when(costLedgerMapper.selectList(any())).thenReturn(List.of());
        ErpStockSumVo emptySum = new ErpStockSumVo();
        emptySum.setQty(BigDecimal.ZERO);
        emptySum.setAmount(BigDecimal.ZERO);
        lenient().when(stockMapper.sumStockByProductType(any())).thenReturn(emptySum);
        lenient().when(productMapper.selectByIds(any())).thenReturn(List.of());
        lenient().when(saleMapper.selectCount(any())).thenReturn(0L);
        lenient().when(purchaseMapper.selectCount(any())).thenReturn(0L);
        lenient().when(receiptMapper.selectCount(any())).thenReturn(0L);
        lenient().when(paymentMapper.selectCount(any())).thenReturn(0L);
        lenient().when(dashboardAggregateMapper.trendSales(any(), any(), any())).thenReturn(List.of());
        lenient().when(dashboardAggregateMapper.trendReceipts(any(), any(), any())).thenReturn(List.of());
        lenient().when(dashboardAggregateMapper.trendOtherCosts(any(), any(), any())).thenReturn(List.of());
        lenient().when(dashboardAggregateMapper.rankCustomerSales(any(), any(), any())).thenReturn(List.of());
        lenient().when(dashboardAggregateMapper.rankCustomerReceipts(any(), any(), any())).thenReturn(List.of());
        lenient().when(dashboardAggregateMapper.rankSupplierPurchases(any(), any(), any())).thenReturn(List.of());
        lenient().when(dashboardAggregateMapper.rankSupplierPayments(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        tenantMock.close();
    }

    private Date date(int y, int m, int d) {
        return Date.from(LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static void initTableInfo(Class<?> clazz) {
        if (com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(clazz) == null) {
            org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                    new org.apache.ibatis.session.Configuration(), "");
            assistant.setCurrentNamespace(clazz.getName());
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, clazz);
        }
    }

    private ErpSale sale(String no, Date bizDate, String total, String gross) {
        ErpSale s = new ErpSale();
        s.setNo(no);
        s.setBizDate(bizDate);
        s.setTotalAmount(new BigDecimal(total));
        s.setGrossProfit(new BigDecimal(gross));
        return s;
    }

    @Test
    @DisplayName("空数据：所有指标为 0")
    void emptyData() {
        DashboardSummaryVo vo = service.summary();
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getMonthSales()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getUnreceived()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getFinishedStockQty()));
        assertEquals(0L, vo.getDraftTodos().total());
        assertTrue(vo.getRecentDocs().isEmpty());
    }

    @Test
    @DisplayName("未收/未付：按 party_type 聚合，VOID 由 SQL 排除")
    void unsettledExcludesVoid() {
        // VOID 过滤与 settled 扣减已下推到 SQL（sumUnsettledByPartyType）：
        // 此处只验证 service 把两路聚合结果正确地映射成未收/未付并保留两位小数。
        when(arApMapper.sumUnsettledByPartyType("AR")).thenReturn(new BigDecimal("600"));
        when(arApMapper.sumUnsettledByPartyType("AP")).thenReturn(new BigDecimal("300"));

        DashboardSummaryVo vo = service.summary();
        assertEquals(0, new BigDecimal("600.00").compareTo(vo.getUnreceived()));
        assertEquals(0, new BigDecimal("300.00").compareTo(vo.getUnpaid()));
    }

    @Test
    @DisplayName("库存：成品/原料按 product type 划分，金额=Σqty×avg_cost")
    void stockSplitByProductType() {
        // 聚合下推数据库：成品与原料各回一行（Σqty、Σqty×avg_cost）
        ErpStockSumVo finished = new ErpStockSumVo();
        finished.setQty(new BigDecimal("10"));
        finished.setAmount(new BigDecimal("264.17"));
        ErpStockSumVo material = new ErpStockSumVo();
        material.setQty(new BigDecimal("125"));
        material.setAmount(new BigDecimal("1320.84"));
        when(stockMapper.sumStockByProductType("PRODUCT")).thenReturn(finished);
        when(stockMapper.sumStockByProductType("MATERIAL")).thenReturn(material);

        DashboardSummaryVo vo = service.summary();
        assertEquals(0, new BigDecimal("10").compareTo(vo.getFinishedStockQty()));
        assertEquals(0, new BigDecimal("264.17").compareTo(vo.getFinishedStockAmount()));
        assertEquals(0, new BigDecimal("125").compareTo(vo.getMaterialStockQty()));
    }

    @Test
    @DisplayName("库存：聚合无数据时按 0 处理")
    void stockSkipsUnknownProduct() {
        // SQL 内已 coalesce，无数据时返回 qty=0、amount=0
        ErpStockSumVo empty = new ErpStockSumVo();
        empty.setQty(BigDecimal.ZERO);
        empty.setAmount(BigDecimal.ZERO);
        when(stockMapper.sumStockByProductType(any())).thenReturn(empty);

        DashboardSummaryVo vo = service.summary();
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getFinishedStockQty()));
    }

    @Test
    @DisplayName("库存预警：单查每类前五，以窗口计数显示全量并保留顺序和缺口")
    void stockAlertUsesTopFiveAndFullCounts() {
        when(stockAlertMapper.selectTopAlerts(null, 5)).thenReturn(List.of(
            topAlert("HIGH", 12L, 21L, "31.250", "21.250"),
            topAlert("HIGH", 12L, 22L, "32.250", "21.250"),
            topAlert("HIGH", 12L, 13L, "27.000", "17.000"),
            topAlert("HIGH", 12L, 16L, "25.000", "15.000"),
            topAlert("HIGH", 12L, 17L, "22.000", "12.000"),
            topAlert("LOW", 9L, 31L, "0.000", "-14.500"),
            topAlert("LOW", 9L, 32L, "2.000", "-14.500"),
            topAlert("LOW", 9L, 23L, "1.000", "-12.000"),
            topAlert("LOW", 9L, 14L, "2.800", "-8.200"),
            topAlert("LOW", 9L, 15L, "3.000", "-7.000")));

        DashboardStockAlertVo vo = service.stockAlert();

        assertEquals(9L, vo.getLowCount());
        assertEquals(12L, vo.getHighCount());
        assertEquals(List.of(31L, 32L, 23L, 14L, 15L),
            vo.getLowTop5().stream().map(DashboardStockAlertVo.Item::getProductId).toList());
        assertEquals(List.of(21L, 22L, 13L, 16L, 17L),
            vo.getHighTop5().stream().map(DashboardStockAlertVo.Item::getProductId).toList());
        DashboardStockAlertVo.Item low = vo.getLowTop5().get(0);
        assertEquals("商品-31", low.getProductName());
        assertEquals(new BigDecimal("0.000"), low.getTotalQty());
        assertEquals(new BigDecimal("-14.500"), low.getGap());
        DashboardStockAlertVo.Item high = vo.getHighTop5().get(0);
        assertEquals("商品-21", high.getProductName());
        assertEquals(new BigDecimal("31.250"), high.getTotalQty());
        assertEquals(new BigDecimal("21.250"), high.getGap());

        verify(stockAlertMapper).selectTopAlerts(null, 5);
        verifyNoMoreInteractions(stockAlertMapper);
        verifyNoInteractions(saleMapper, purchaseMapper, arApMapper, receiptMapper, paymentMapper, costLedgerMapper,
            stockMapper, productMapper, customerMapper, supplierMapper, dashboardAggregateMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOW", "HIGH"})
    @DisplayName("库存预警：仅一类命中时另一类计数为零、列表为空")
    void stockAlertMissingTypeIsEmpty(String type) {
        boolean high = "HIGH".equals(type);
        when(stockAlertMapper.selectTopAlerts(null, 5)).thenReturn(List.of(
            topAlert(type, 1L, 41L, "3.000", high ? "1.000" : "-1.000")));

        DashboardStockAlertVo vo = service.stockAlert();

        assertEquals(high ? 0L : 1L, vo.getLowCount());
        assertEquals(high ? 1L : 0L, vo.getHighCount());
        assertEquals(1, (high ? vo.getHighTop5() : vo.getLowTop5()).size());
        assertTrue((high ? vo.getLowTop5() : vo.getHighTop5()).isEmpty());
    }

    @Test
    @DisplayName("库存预警：无命中时两类计数均为零、列表均为空")
    void stockAlertEmpty() {
        when(stockAlertMapper.selectTopAlerts(null, 5)).thenReturn(List.of());

        DashboardStockAlertVo vo = service.stockAlert();

        assertEquals(0L, vo.getLowCount());
        assertEquals(0L, vo.getHighCount());
        assertTrue(vo.getLowTop5().isEmpty());
        assertTrue(vo.getHighTop5().isEmpty());
    }

    private static ErpStockAlertTopRow topAlert(String type, long count, long id, String qty, String gap) {
        ErpStockAlertTopRow row = new ErpStockAlertTopRow();
        row.setAlertType(type);
        row.setAlertCount(count);
        row.setProductId(id);
        row.setProductName("商品-" + id);
        row.setTotalQty(new BigDecimal(qty));
        row.setGap(new BigDecimal(gap));
        return row;
    }

    @Test
    @DisplayName("最近单据：混排按 bizDate 倒序，截断 10 条")
    void recentDocsSortedAndTruncated() {
        when(saleMapper.selectList(any())).thenReturn(List.of(
            sale("XS001", date(2026, 10, 1), "100", "10"),
            sale("XS002", date(2026, 10, 3), "200", "20")));
        ErpPurchase p = new ErpPurchase();
        p.setNo("CG001");
        p.setBizDate(date(2026, 10, 2));
        p.setTotalAmount(new BigDecimal("50"));
        when(purchaseMapper.selectList(any())).thenReturn(List.of(p));

        DashboardSummaryVo vo = service.summary();
        assertEquals(3, vo.getRecentDocs().size());
        assertEquals("XS002", vo.getRecentDocs().get(0).getNo());
        assertEquals("CG001", vo.getRecentDocs().get(1).getNo());
        assertEquals("XS001", vo.getRecentDocs().get(2).getNo());
        assertEquals("SALE", vo.getRecentDocs().get(0).getType());
        assertEquals(0, new BigDecimal("200").compareTo(vo.getRecentDocs().get(0).getAmount()));
    }

    @Test
    @DisplayName("最近单据：实际收款查询投影包含 negative，50 元退款展示为 -50")
    void recentDocsRefundSelectsNegativeAndDisplaysNegativeAmount() {
        when(receiptMapper.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FinReceipt> query = invocation.getArgument(0);
            List<String> columns = java.util.Arrays.stream(query.getSqlSelect().split(","))
                .map(String::strip).toList();
            assertTrue(columns.contains("negative"), "退款方向必须在实际 SQL 投影中：" + query.getSqlSelect());

            // 只模拟所选字段的映射；未选 negative 时不回填，不能用完整实体掩盖漏选。
            FinReceipt projectedRefund = new FinReceipt();
            projectedRefund.setNo("TK001");
            projectedRefund.setAmount(new BigDecimal("50.00"));
            projectedRefund.setNegative(columns.contains("negative") ? 1 : null);
            return List.of(projectedRefund);
        });

        DashboardSummaryVo vo = service.summary();

        assertEquals(1, vo.getRecentDocs().size());
        DashboardSummaryVo.RecentDoc refund = vo.getRecentDocs().get(0);
        assertEquals("TK001", refund.getNo());
        assertEquals("RECEIPT", refund.getType());
        assertEquals(new BigDecimal("-50.00"), refund.getAmount());
        verify(receiptMapper).selectList(any());
    }

    @Test
    @DisplayName("最近单据：bizDate 为空排在末尾不抛 NPE")
    void recentDocsNullDateLast() {
        when(saleMapper.selectList(any())).thenReturn(List.of(sale("XS001", null, "100", "10")));
        ErpPurchase p = new ErpPurchase();
        p.setNo("CG001");
        p.setBizDate(date(2026, 10, 2));
        when(purchaseMapper.selectList(any())).thenReturn(List.of(p));

        DashboardSummaryVo vo = service.summary();
        assertEquals(2, vo.getRecentDocs().size());
        assertEquals("CG001", vo.getRecentDocs().get(0).getNo());
    }

    @Test
    @DisplayName("草稿待办：四类合计")
    void draftTodosTotal() {
        when(saleMapper.selectCount(any())).thenReturn(2L);
        when(purchaseMapper.selectCount(any())).thenReturn(1L);
        when(receiptMapper.selectCount(any())).thenReturn(0L);
        when(paymentMapper.selectCount(any())).thenReturn(1L);

        DashboardSummaryVo vo = service.summary();
        assertEquals(4L, vo.getDraftTodos().total());
        assertEquals(2L, vo.getDraftTodos().getSaleDrafts());
    }

    @Test
    @DisplayName("趋势：默认 6 个月，月份序列从早到晚，profit=sales-cost")
    void trendDefaultSixMonths() {
        DashboardTrendVo vo = service.trend(null);
        assertEquals(6, vo.getMonths().size());
        assertEquals(6, vo.getSales().size());
        LocalDate expectedLast = LocalDate.now().withDayOfMonth(1);
        assertEquals(expectedLast.minusMonths(5).toString().substring(0, 7), vo.getMonths().get(0));
        assertEquals(expectedLast.toString().substring(0, 7), vo.getMonths().get(5));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getProfit().get(0)));
    }

    @Test
    @DisplayName("趋势：12 个月合法")
    void trendTwelveMonths() {
        DashboardTrendVo vo = service.trend(12);
        assertEquals(12, vo.getMonths().size());
    }

    @Test
    @DisplayName("趋势：0/13 拒绝")
    void trendInvalidMonths() {
        assertThrows(ServiceException.class, () -> service.trend(0));
        assertThrows(ServiceException.class, () -> service.trend(13));
        assertThrows(ServiceException.class, () -> service.trend(-1));
    }

    @Test
    @DisplayName("成本趋势：其他出库/其他支出台账按月分组（聚合只做月分组，ACTIVE 由查询条件保证）")
    void trendCostGroupsByMonth() {
        DashboardAggregateVo c1 = new DashboardAggregateVo();
        c1.setMonth(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        c1.setAmount(new BigDecimal("317.59"));
        when(dashboardAggregateMapper.trendOtherCosts(any(), any(), any())).thenReturn(List.of(c1));

        DashboardTrendVo vo = service.trend(1);
        assertEquals(0, new BigDecimal("317.59").compareTo(vo.getCost().get(0)));
    }

    @Test
    @DisplayName("成本趋势 = 销售出库成本 + 其他出库/其他支出，不含生产材料成本")
    void trendCostUsesSalesCostBasis() {
        DashboardAggregateVo s = new DashboardAggregateVo();
        s.setMonth(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        s.setAmount(new BigDecimal("1000"));
        s.setCost(new BigDecimal("700"));
        when(dashboardAggregateMapper.trendSales(any(), any(), any())).thenReturn(List.of(s));

        DashboardAggregateVo otherOut = new DashboardAggregateVo();
        otherOut.setMonth(s.getMonth());
        otherOut.setAmount(new BigDecimal("50"));
        when(dashboardAggregateMapper.trendOtherCosts(any(), any(), any())).thenReturn(List.of(otherOut));

        DashboardTrendVo vo = service.trend(1);
        assertEquals(0, new BigDecimal("750.00").compareTo(vo.getCost().get(0)));
        assertEquals(0, new BigDecimal("250.00").compareTo(vo.getProfit().get(0)));
    }

    @Test
    @DisplayName("收款金额 null 视为 0")
    void receiptNullAmountTreatedAsZero() {
        FinReceipt r = new FinReceipt();
        r.setAmount(null);
        r.setBizDate(date(2026, 10, 1));
        when(receiptMapper.selectList(any())).thenReturn(List.of(r));

        DashboardSummaryVo vo = service.summary();
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getMonthReceiptAmount()));
    }

    @Test
    @DisplayName("付款单草稿计数进入待办")
    void paymentDraftTodo() {
        when(paymentMapper.selectCount(any())).thenReturn(4L);
        DashboardSummaryVo vo = service.summary();
        assertEquals(4L, vo.getDraftTodos().getPaymentDrafts());
    }

    @Test
    @DisplayName("本月销售毛利率 = Σgross_profit / Σtotal_amount × 100")
    void monthGrossMargin() {
        DashboardAggregateVo aggregate = new DashboardAggregateVo();
        aggregate.setAmount(new BigDecimal("10000"));
        aggregate.setGrossProfit(new BigDecimal("2500"));
        when(dashboardAggregateMapper.monthSales(any(), any(), any())).thenReturn(aggregate);

        DashboardSummaryVo vo = service.summary();
        assertEquals(0, new BigDecimal("10000.00").compareTo(vo.getMonthSales()));
        assertEquals(0, new BigDecimal("2500.00").compareTo(vo.getMonthGrossProfit()));
        assertEquals(0, new BigDecimal("25.00").compareTo(vo.getMonthGrossMargin()));
    }

    @Test
    @DisplayName("本月无销售时毛利率为 null")
    void monthGrossMarginNullWhenNoSales() {
        DashboardSummaryVo vo = service.summary();
        assertNull(vo.getMonthGrossMargin());
    }

    /* ------------------------------ 往来单位当月明细 ------------------------------ */

    /** 当前月份内的某一天，保证落在服务端统计窗口内 */
    private Date currentMonthDate(int day) {
        LocalDate now = LocalDate.now().withDayOfMonth(1);
        return Date.from(now.plusDays(day - 1).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("往来单位当月明细：客户口径统计本月销售与收款，并列出单据")
    void partnerMonthCustomer() {
        ErpCustomer customer = new ErpCustomer();
        customer.setId(7L);
        customer.setName("宏达建材");
        when(customerMapper.selectById(7L)).thenReturn(customer);

        ErpSale sale = new ErpSale();
        sale.setNo("XS20261101001");
        sale.setBizDate(currentMonthDate(5));
        sale.setTotalAmount(new BigDecimal("1000"));
        sale.setGrossProfit(new BigDecimal("300"));
        when(saleMapper.selectList(any())).thenReturn(List.of(sale));
        DashboardAggregateVo salesAggregate = new DashboardAggregateVo();
        salesAggregate.setAmount(new BigDecimal("1000"));
        salesAggregate.setGrossProfit(new BigDecimal("300"));
        salesAggregate.setDocCount(1L);
        when(dashboardAggregateMapper.customerSales(any(), any(), any(), any())).thenReturn(salesAggregate);

        FinReceipt receipt = new FinReceipt();
        receipt.setNo("SK20261101001");
        receipt.setBizDate(currentMonthDate(8));
        receipt.setAmount(new BigDecimal("400"));
        when(receiptMapper.selectList(any())).thenReturn(List.of(receipt));
        DashboardAggregateVo receiptAggregate = new DashboardAggregateVo();
        receiptAggregate.setAmount(new BigDecimal("400"));
        when(dashboardAggregateMapper.customerReceipts(any(), any(), any(), any())).thenReturn(receiptAggregate);

        DashboardPartnerMonthVo vo = service.partnerMonth("CUSTOMER", 7L);
        assertEquals("宏达建材", vo.getPartyName());
        assertEquals(1L, vo.getDocCount());
        assertEquals(0, new BigDecimal("1000.00").compareTo(vo.getSalesAmount()));
        assertEquals(0, new BigDecimal("300.00").compareTo(vo.getGrossProfit()));
        assertEquals(0, new BigDecimal("30.00").compareTo(vo.getGrossMargin()));
        assertEquals(0, new BigDecimal("400.00").compareTo(vo.getReceiptAmount()));
        assertEquals(2, vo.getDocs().size());
        // 收款单业务日期晚于销售单，倒序排列时收款在前
        assertEquals("RECEIPT", vo.getDocs().get(0).getType());
        assertEquals("SALE", vo.getDocs().get(1).getType());
    }

    @Test
    @DisplayName("退款方向：negative=1 的 50 元退款在客户报表中为 -50，不是收款 +50")
    void partnerMonthNegativeReceiptIsSigned() {
        ErpCustomer customer = new ErpCustomer();
        customer.setId(7L);
        customer.setName("客户");
        when(customerMapper.selectById(7L)).thenReturn(customer);
        FinReceipt refund = new FinReceipt();
        refund.setNo("TK001");
        refund.setBizDate(currentMonthDate(2));
        refund.setAmount(new BigDecimal("50"));
        refund.setNegative(1);
        when(receiptMapper.selectList(any())).thenReturn(List.of(refund));
        DashboardAggregateVo aggregate = new DashboardAggregateVo();
        aggregate.setAmount(new BigDecimal("-50"));
        when(dashboardAggregateMapper.customerReceipts(any(), any(), any(), any())).thenReturn(aggregate);

        DashboardPartnerMonthVo vo = service.partnerMonth("CUSTOMER", 7L);

        assertEquals(0, new BigDecimal("-50.00").compareTo(vo.getReceiptAmount()));
        assertEquals(0, new BigDecimal("-50.00").compareTo(vo.getDocs().get(0).getAmount()));
    }

    @Test
    @DisplayName("往来单位当月明细：供货商口径统计本月采购与付款")
    void partnerMonthSupplier() {
        ErpSupplier supplier = new ErpSupplier();
        supplier.setId(9L);
        supplier.setName("钢材供应商");
        when(supplierMapper.selectById(9L)).thenReturn(supplier);

        ErpPurchase purchase = new ErpPurchase();
        purchase.setNo("CG20261101001");
        purchase.setBizDate(currentMonthDate(3));
        purchase.setTotalAmount(new BigDecimal("800"));
        when(purchaseMapper.selectList(any())).thenReturn(List.of(purchase));
        DashboardAggregateVo purchaseAggregate = new DashboardAggregateVo();
        purchaseAggregate.setAmount(new BigDecimal("800"));
        purchaseAggregate.setDocCount(1L);
        when(dashboardAggregateMapper.supplierPurchases(any(), any(), any(), any())).thenReturn(purchaseAggregate);

        FinPayment payment = new FinPayment();
        payment.setNo("FK20261101001");
        payment.setBizDate(currentMonthDate(9));
        payment.setAmount(new BigDecimal("300"));
        when(paymentMapper.selectList(any())).thenReturn(List.of(payment));
        DashboardAggregateVo paymentAggregate = new DashboardAggregateVo();
        paymentAggregate.setAmount(new BigDecimal("300"));
        when(dashboardAggregateMapper.supplierPayments(any(), any(), any(), any())).thenReturn(paymentAggregate);

        DashboardPartnerMonthVo vo = service.partnerMonth("SUPPLIER", 9L);
        assertEquals("钢材供应商", vo.getPartyName());
        assertEquals(1L, vo.getDocCount());
        assertEquals(0, new BigDecimal("800.00").compareTo(vo.getPurchaseAmount()));
        assertEquals(0, new BigDecimal("300.00").compareTo(vo.getPaymentAmount()));
        assertEquals(2, vo.getDocs().size());
        assertEquals("PAYMENT", vo.getDocs().get(0).getType());
        assertEquals("PURCHASE", vo.getDocs().get(1).getType());
    }

    @Test
    @DisplayName("往来单位当月明细：客户无销售时毛利率为 null，单据列表为空")
    void partnerMonthCustomerNoData() {
        DashboardPartnerMonthVo vo = service.partnerMonth("CUSTOMER", 7L);
        assertNull(vo.getPartyName());
        assertEquals(0L, vo.getDocCount());
        assertNull(vo.getGrossMargin());
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getSalesAmount()));
        assertTrue(vo.getDocs().isEmpty());
    }

    @Test
    @DisplayName("往来单位当月明细：非法单位类型或缺失单位 ID 抛参数异常")
    void partnerMonthInvalidParams() {
        assertThrows(ServiceException.class, () -> service.partnerMonth("CUSTOMER", null));
        assertThrows(ServiceException.class, () -> service.partnerMonth("OTHER", 7L));
    }

    /* ------------------------------ 往来单位本月排行 ------------------------------ */

    @Test
    @DisplayName("排行：客户按本月销售额倒序，含毛利与收款")
    void partnerRankCustomerSortedByAmount() {
        ErpCustomer c1 = new ErpCustomer();
        c1.setId(7L);
        c1.setCode("KH01");
        c1.setName("远达机械制造");
        ErpCustomer c2 = new ErpCustomer();
        c2.setId(8L);
        c2.setCode("KH02");
        c2.setName("恒通机电设备");
        when(customerMapper.selectByIds(any())).thenReturn(List.of(c1, c2));

        ErpSale big = new ErpSale();
        big.setCustomerId(7L);
        big.setTotalAmount(new BigDecimal("1000"));
        big.setGrossProfit(new BigDecimal("300"));
        ErpSale small = new ErpSale();
        small.setCustomerId(8L);
        small.setTotalAmount(new BigDecimal("400"));
        small.setGrossProfit(new BigDecimal("100"));
        when(saleMapper.selectList(any())).thenReturn(List.of(big, small));
        DashboardAggregateVo c1Aggregate = new DashboardAggregateVo();
        c1Aggregate.setPartyId(7L);
        c1Aggregate.setAmount(new BigDecimal("1000"));
        c1Aggregate.setGrossProfit(new BigDecimal("300"));
        c1Aggregate.setDocCount(1L);
        DashboardAggregateVo c2Aggregate = new DashboardAggregateVo();
        c2Aggregate.setPartyId(8L);
        c2Aggregate.setAmount(new BigDecimal("400"));
        c2Aggregate.setGrossProfit(new BigDecimal("100"));
        c2Aggregate.setDocCount(1L);
        when(dashboardAggregateMapper.rankCustomerSales(any(), any(), any())).thenReturn(List.of(c1Aggregate, c2Aggregate));

        FinReceipt receipt = new FinReceipt();
        receipt.setCustomerId(7L);
        receipt.setAmount(new BigDecimal("500"));
        when(receiptMapper.selectList(any())).thenReturn(List.of(receipt));
        DashboardAggregateVo receiptAggregate = new DashboardAggregateVo();
        receiptAggregate.setPartyId(7L);
        receiptAggregate.setAmount(new BigDecimal("500"));
        when(dashboardAggregateMapper.rankCustomerReceipts(any(), any(), any())).thenReturn(List.of(receiptAggregate));

        DashboardPartnerRankVo vo = service.partnerRank("CUSTOMER");
        assertEquals(2, vo.getItems().size());
        DashboardPartnerRankVo.RankItem first = vo.getItems().get(0);
        assertEquals(7L, first.getPartyId());
        assertEquals("远达机械制造", first.getPartyName());
        assertEquals("KH01", first.getPartyCode());
        assertEquals(0, new BigDecimal("1000.00").compareTo(first.getAmount()));
        assertEquals(0, new BigDecimal("300.00").compareTo(first.getGrossProfit()));
        assertEquals(0, new BigDecimal("30.00").compareTo(first.getGrossMargin()));
        assertEquals(0, new BigDecimal("500.00").compareTo(first.getReceiptAmount()));
        assertEquals(1L, first.getDocCount());
        assertEquals(8L, vo.getItems().get(1).getPartyId());
    }

    @Test
    @DisplayName("排行：同一客户多张销售单金额累加，单据数累加")
    void partnerRankAggregatesSameParty() {
        ErpCustomer c1 = new ErpCustomer();
        c1.setId(7L);
        c1.setName("远达机械制造");
        when(customerMapper.selectByIds(any())).thenReturn(List.of(c1));

        ErpSale s1 = new ErpSale();
        s1.setCustomerId(7L);
        s1.setTotalAmount(new BigDecimal("100"));
        s1.setGrossProfit(new BigDecimal("30"));
        ErpSale s2 = new ErpSale();
        s2.setCustomerId(7L);
        s2.setTotalAmount(new BigDecimal("200"));
        s2.setGrossProfit(new BigDecimal("70"));
        when(saleMapper.selectList(any())).thenReturn(List.of(s1, s2));
        DashboardAggregateVo salesAggregate = new DashboardAggregateVo();
        salesAggregate.setPartyId(7L);
        salesAggregate.setAmount(new BigDecimal("300"));
        salesAggregate.setGrossProfit(new BigDecimal("100"));
        salesAggregate.setDocCount(2L);
        when(dashboardAggregateMapper.rankCustomerSales(any(), any(), any())).thenReturn(List.of(salesAggregate));

        DashboardPartnerRankVo vo = service.partnerRank("CUSTOMER");
        assertEquals(1, vo.getItems().size());
        assertEquals(2L, vo.getItems().get(0).getDocCount());
        assertEquals(0, new BigDecimal("300.00").compareTo(vo.getItems().get(0).getAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getItems().get(0).getGrossProfit()));
    }

    @Test
    @DisplayName("排行：供货商按本月采购额倒序")
    void partnerRankSupplier() {
        ErpSupplier s1 = new ErpSupplier();
        s1.setId(9L);
        s1.setCode("GYS01");
        s1.setName("宏发五金钢材");
        when(supplierMapper.selectByIds(any())).thenReturn(List.of(s1));

        ErpPurchase p1 = new ErpPurchase();
        p1.setSupplierId(9L);
        p1.setTotalAmount(new BigDecimal("2220"));
        when(purchaseMapper.selectList(any())).thenReturn(List.of(p1));
        DashboardAggregateVo purchasesAggregate = new DashboardAggregateVo();
        purchasesAggregate.setPartyId(9L);
        purchasesAggregate.setAmount(new BigDecimal("2220"));
        purchasesAggregate.setDocCount(1L);
        when(dashboardAggregateMapper.rankSupplierPurchases(any(), any(), any())).thenReturn(List.of(purchasesAggregate));

        FinPayment pay = new FinPayment();
        pay.setSupplierId(9L);
        pay.setAmount(new BigDecimal("300"));
        when(paymentMapper.selectList(any())).thenReturn(List.of(pay));
        DashboardAggregateVo paymentsAggregate = new DashboardAggregateVo();
        paymentsAggregate.setPartyId(9L);
        paymentsAggregate.setAmount(new BigDecimal("300"));
        when(dashboardAggregateMapper.rankSupplierPayments(any(), any(), any())).thenReturn(List.of(paymentsAggregate));

        DashboardPartnerRankVo vo = service.partnerRank("SUPPLIER");
        assertEquals(1, vo.getItems().size());
        DashboardPartnerRankVo.RankItem item = vo.getItems().get(0);
        assertEquals("宏发五金钢材", item.getPartyName());
        assertEquals(0, new BigDecimal("2220.00").compareTo(item.getAmount()));
        assertEquals(0, new BigDecimal("300.00").compareTo(item.getPaymentAmount()));
        assertEquals(1L, item.getDocCount());
        assertNull(item.getGrossMargin());
    }

    @Test
    @DisplayName("排行：仅收款无销售的客户金额为 0，毛利率为 null")
    void partnerRankReceiptOnlyMarginNull() {
        FinReceipt receipt = new FinReceipt();
        receipt.setCustomerId(7L);
        receipt.setAmount(new BigDecimal("500"));
        when(receiptMapper.selectList(any())).thenReturn(List.of(receipt));
        DashboardAggregateVo receiptAggregate = new DashboardAggregateVo();
        receiptAggregate.setPartyId(7L);
        receiptAggregate.setAmount(new BigDecimal("500"));
        when(dashboardAggregateMapper.rankCustomerReceipts(any(), any(), any())).thenReturn(List.of(receiptAggregate));

        DashboardPartnerRankVo vo = service.partnerRank("CUSTOMER");
        assertEquals(1, vo.getItems().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getItems().get(0).getAmount()));
        assertNull(vo.getItems().get(0).getGrossMargin());
        assertEquals(0, new BigDecimal("500.00").compareTo(vo.getItems().get(0).getReceiptAmount()));
    }

    @Test
    @DisplayName("排行：本月无往来单位时列表为空")
    void partnerRankEmpty() {
        DashboardPartnerRankVo vo = service.partnerRank("CUSTOMER");
        assertTrue(vo.getItems().isEmpty());
    }

    @Test
    @DisplayName("排行：非法单位类型抛参数异常")
    void partnerRankInvalidType() {
        assertThrows(ServiceException.class, () -> service.partnerRank("OTHER"));
    }
}
