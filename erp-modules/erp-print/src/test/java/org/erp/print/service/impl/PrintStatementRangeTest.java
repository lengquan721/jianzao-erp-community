package org.erp.print.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.service.IPrintTemplateService;
import org.erp.trade.mapper.ErpSaleMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Range behavior is exercised through the same narrow projection Mapper used by production printing. */
@Tag("dev")
class PrintStatementRangeTest {
    private static final String TENANT = "TENANT_A";
    private static final Long CUSTOMER = 9L;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 1);

    @Test
    @DisplayName("跨月闭区间保持头账务口径，独立展示净额且明细不参与汇总")
    void rendersRangeAcrossMonthsFromNarrowMapper() {
        IPrintTemplateService templates = mock(IPrintTemplateService.class);
        PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
        FinArApMapper ledger = mock(FinArApMapper.class);
        ErpCustomerMapper customers = mock(ErpCustomerMapper.class);
        PrintTemplate template = template("STATEMENT_RANGE");
        when(templates.resolve(null, "STATEMENT_RANGE", CUSTOMER)).thenReturn(template);
        when(finance.selectStatementLines(TENANT, CUSTOMER, FROM, TO)).thenReturn(List.of(
            line("销售", "XS-1", "销售明细合计440.00", "440.00"),
            line("销售退货", "XT-1", "退货明细", "-20.00")));
        when(finance.selectStatementMovements(TENANT, CUSTOMER, FROM, TO)).thenReturn(List.of(
            movement("OPENING", "50.00", "0.00"),
            movement("SALE", "100.00", "500.00"),
            movement("SALE_RETURN", "-10.00", "-20.00"),
            movement("RECEIPT", "-30.00", "-40.00"),
            movement("REFUND", "0.00", "0.00")));
        when(ledger.sumUnsettledByParty("AR", CUSTOMER)).thenReturn(new BigDecimal("999.00"));

        String html = render(templates, finance, ledger, customers, "2026-09-01", "2026-10-31");
        var doc = Jsoup.parse(html);

        assertEquals("2026-09-01 至 2026-10-31", doc.selectFirst(".period").text());
        assertEquals("110.00", doc.selectFirst(".opening").text());
        assertEquals("500.00", doc.selectFirst(".gross-sales").text());
        assertEquals("20.00", doc.selectFirst(".returns").text());
        assertEquals("480.00", doc.selectFirst(".net-sales").text());
        assertEquals("40.00", doc.selectFirst(".receipts").text());
        assertEquals("440.00", doc.selectFirst(".net-unpaid").text());
        assertEquals("570.00", doc.selectFirst(".cumulative-sales").text());
        assertEquals("70.00", doc.selectFirst(".cumulative-receipts").text());
        assertEquals("550.00", doc.selectFirst(".closing").text());
        assertEquals("999.00", doc.selectFirst(".live-debt").text(), "实时未核销与所选期间期末余额不得混用");
        assertEquals(List.of("440.00", "-20.00"), doc.select(".line").eachText());
        verify(finance).selectStatementLines(TENANT, CUSTOMER, FROM, TO);
        verify(finance).selectStatementMovements(TENANT, CUSTOMER, FROM, TO);
        verify(templates).resolve(null, "STATEMENT_RANGE", CUSTOMER);
    }

    @Test
    @DisplayName("期初截止日位于区间内时纳入期初建账和期末，单日区间有效")
    void countsOpeningInsideRangeAndAcceptsSingleDay() {
        IPrintTemplateService templates = mock(IPrintTemplateService.class);
        PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
        FinArApMapper ledger = mock(FinArApMapper.class);
        when(templates.resolve(null, "STATEMENT_RANGE", CUSTOMER)).thenReturn(template("STATEMENT_RANGE"));
        LocalDate day = LocalDate.of(2026, 9, 30);
        when(finance.selectStatementLines(TENANT, CUSTOMER, day, day.plusDays(1))).thenReturn(List.of());
        when(finance.selectStatementMovements(TENANT, CUSTOMER, day, day.plusDays(1))).thenReturn(List.of(
            movement("OPENING", "0.00", "65004.00")));
        String html = render(templates, finance, ledger, mock(ErpCustomerMapper.class), "2026-09-30", "2026-09-30");
        var doc = Jsoup.parse(html);
        assertEquals("65004.00", doc.selectFirst(".opening-entry").text());
        assertEquals("65004.00", doc.selectFirst(".closing").text());
        assertEquals("2026-09-30 至 2026-09-30", doc.selectFirst(".period").text());
    }

    @Test
    @DisplayName("区间参数优先于 month，模板场景与权限不可混淆")
    void rangeWinsOverMonthAndResolvesRangeTemplate() {
        IPrintTemplateService templates = mock(IPrintTemplateService.class);
        PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
        when(templates.resolve(null, "STATEMENT_RANGE", CUSTOMER)).thenReturn(template("STATEMENT_RANGE"));
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = from.plusMonths(1);
        when(finance.selectStatementLines(TENANT, CUSTOMER, from, to)).thenReturn(List.of());
        when(finance.selectStatementMovements(TENANT, CUSTOMER, from, to)).thenReturn(List.of());
        String html;
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);
            html = service(templates, finance, mock(FinArApMapper.class), mock(ErpCustomerMapper.class))
                .renderStatement(CUSTOMER, "2026-10", "2026-09-01", "2026-09-30", null);
        }
        assertTrue(html.contains("2026-09-01 至 2026-09-30"));
        verify(templates).resolve(null, "STATEMENT_RANGE", CUSTOMER);
    }

    @Test
    @DisplayName("恰好366个自然日的闭区间可用")
    void acceptsExactly366InclusiveDays() {
        IPrintTemplateService templates = mock(IPrintTemplateService.class);
        PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2025, 1, 1);
        when(templates.resolve(null, "STATEMENT_RANGE", CUSTOMER)).thenReturn(template("STATEMENT_RANGE"));
        when(finance.selectStatementLines(TENANT, CUSTOMER, from, to)).thenReturn(List.of());
        when(finance.selectStatementMovements(TENANT, CUSTOMER, from, to)).thenReturn(List.of());
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);
            String html = service(templates, finance, mock(FinArApMapper.class), mock(ErpCustomerMapper.class))
                .renderStatement(CUSTOMER, null, "2024-01-01", "2024-12-31", null);
            assertTrue(html.contains("2024-01-01 至 2024-12-31"));
        }
    }

    @Test
    @DisplayName("366天含首尾可用，367天、反向、缺端、非法格式与越界日期拒绝")
    void validatesInclusiveRangeBoundaries() {
        IPrintTemplateService templates = mock(IPrintTemplateService.class);
        PrintServiceImpl service = service(templates, mock(PrintFinanceMapper.class), mock(FinArApMapper.class),
            mock(ErpCustomerMapper.class));
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null, "2024-01-01", "2025-01-01", null));
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null, "2026-10-31", "2026-10-01", null));
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null, "2026-09-01", null, null));
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null, "2026-9-1", "2026-09-30", null));
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null, "2026-02-30", "2026-03-01", null));
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null,
                "+999999999-01-01", "+999999999-01-02", null));
        }
        verifyNoInteractions(templates);
    }

    @Test
    @DisplayName("区间超过5000行时在解析模板和其他汇总前拒绝，5000行边界允许")
    void enforcesFiveThousandOneRowProjectionLimit() {
        IPrintTemplateService templates = mock(IPrintTemplateService.class);
        PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
        FinArApMapper ledger = mock(FinArApMapper.class);
        PrintServiceImpl service = service(templates, finance, ledger, mock(ErpCustomerMapper.class));
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);
            when(finance.selectStatementLines(TENANT, CUSTOMER, FROM, TO))
                .thenReturn(Collections.nCopies(5001, line("收款", "SK-1", "", "-1.00")));
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null,
                "2026-09-01", "2026-10-31", null));
            verify(finance).selectStatementLines(TENANT, CUSTOMER, FROM, TO);
            verify(finance, never()).selectStatementMovements(any(), any(), any(), any());
            verifyNoInteractions(templates, ledger);

            when(finance.selectStatementLines(TENANT, CUSTOMER, FROM, TO))
                .thenReturn(Collections.nCopies(5000, line("收款", "SK-1", "", "-1.00")));
            when(finance.selectStatementMovements(TENANT, CUSTOMER, FROM, TO)).thenReturn(List.of());
            when(templates.resolve(null, "STATEMENT_RANGE", CUSTOMER)).thenReturn(template("STATEMENT_RANGE"));
            assertDoesNotThrow(() -> service.renderStatement(CUSTOMER, null, "2026-09-01", "2026-10-31", null));
        }
    }

    @Test
    @DisplayName("非租户上下文不触发财务Mapper、客户Mapper或模板解析")
    void missingTenantStopsBeforeQueries() {
        IPrintTemplateService templates = mock(IPrintTemplateService.class);
        PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
        ErpCustomerMapper customers = mock(ErpCustomerMapper.class);
        PrintServiceImpl service = service(templates, finance, mock(FinArApMapper.class), customers);
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(null);
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null,
                "2026-09-01", "2026-09-30", null));
        }
        verifyNoInteractions(finance, templates, customers);
    }

    @Test
    @DisplayName("显式模板场景不匹配时拒绝区间打印")
    void rejectsTemplateSceneMismatch() {
        IPrintTemplateService templates = mock(IPrintTemplateService.class);
        PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
        PrintTemplate wrongScene = template("STATEMENT");
        when(templates.resolve(7L, "STATEMENT_RANGE", CUSTOMER)).thenReturn(wrongScene);
        LocalDate from = LocalDate.of(2026, 9, 1);
        when(finance.selectStatementLines(TENANT, CUSTOMER, from, from.plusDays(1))).thenReturn(List.of());
        when(finance.selectStatementMovements(TENANT, CUSTOMER, from, from.plusDays(1))).thenReturn(List.of());
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);
            var service = service(templates, finance, mock(FinArApMapper.class), mock(ErpCustomerMapper.class));
            assertThrows(ServiceException.class, () -> service.renderStatement(CUSTOMER, null,
                "2026-09-01", "2026-09-01", 7L));
        }
    }

    @Test
    @DisplayName("月度与区间均由实现类的公共入口启动只读RR事务")
    void bothPublicEntryPointsHaveReadOnlyRepeatableReadTransactions() throws Exception {
        for (var method : List.of(
            PrintServiceImpl.class.getMethod("renderStatement", Long.class, String.class, Long.class),
            PrintServiceImpl.class.getMethod("renderStatement", Long.class, String.class, String.class, String.class, Long.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertNotNull(tx);
            assertTrue(tx.readOnly());
            assertEquals(Isolation.REPEATABLE_READ, tx.isolation());
        }
    }

    private static String render(IPrintTemplateService templates, PrintFinanceMapper finance, FinArApMapper ledger,
                                 ErpCustomerMapper customers, String start, String end) {
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);
            return service(templates, finance, ledger, customers)
                .renderStatement(CUSTOMER, null, start, end, null);
        }
    }

    private static PrintServiceImpl service(IPrintTemplateService templates, PrintFinanceMapper finance,
                                            FinArApMapper ledger, ErpCustomerMapper customers) {
        return new PrintServiceImpl(templates, mock(ErpSaleMapper.class), null, null, null, null, ledger, null,
            customers, null, null, null, null, null, finance, new TemplateCompiler(), new ObjectMapper());
    }

    private static PrintTemplate template(String scene) {
        PrintTemplate t = new PrintTemplate();
        t.setScene(scene);
        t.setPaper("A4");
        t.setContent("""
            <div class="period" th:text="${statement.periodLabel}"></div>
            <div class="opening" th:text="${statement.opening}"></div>
            <div class="opening-entry" th:text="${statement.openingEntryAmount}"></div>
            <div class="gross-sales" th:text="${statement.salesAmount}"></div>
            <div class="returns" th:text="${statement.returnAmount}"></div>
            <div class="net-sales" th:text="${statement.netSalesAmount}"></div>
            <div class="receipts" th:text="${statement.receiptAmount}"></div>
            <div class="net-unpaid" th:text="${statement.monthlyUnpaid}"></div>
            <div class="cumulative-sales" th:text="${statement.cumulativeSales}"></div>
            <div class="cumulative-receipts" th:text="${statement.cumulativeReceipts}"></div>
            <div class="closing" th:text="${statement.closing}"></div>
            <div class="live-debt" th:text="${totals.debtTotal}"></div>
            <div th:each="item : ${statement.items}" class="line" th:text="${item.amount}"></div>
            """);
        return t;
    }

    private static PrintLedgerMovement movement(String source, String prior, String period) {
        PrintLedgerMovement row = new PrintLedgerMovement();
        row.setSourceType(source);
        row.setPriorAmount(new BigDecimal(prior));
        row.setPeriodAmount(new BigDecimal(period));
        return row;
    }

    private static PrintStatementLine line(String content, String no, String product, String amount) {
        PrintStatementLine row = new PrintStatementLine();
        row.setEventDate(Date.valueOf("2026-09-15"));
        row.setDocumentNo(no);
        row.setContent(content);
        row.setProductName(product);
        row.setSignedAmount(new BigDecimal(amount));
        return row;
    }
}
