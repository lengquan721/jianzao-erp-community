package org.erp.print.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.service.IPrintTemplateService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class PrintStatementPeriodTest {
    private static final long PARTY = 7L;
    private static final String TENANT = "TENANT_A";
    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate FEB = JAN.plusMonths(1);
    private static final LocalDate MAR = FEB.plusMonths(1);
    private static final LocalDate APR = MAR.plusMonths(1);

    private final PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
    private final FinArApMapper ledger = mock(FinArApMapper.class);
    private final IPrintTemplateService templates = mock(IPrintTemplateService.class);
    private final ErpCustomerMapper customers = mock(ErpCustomerMapper.class);
    private final PrintServiceImpl service = new PrintServiceImpl(templates, null, null, null, null, null,
        ledger, null, customers, null, null, null, null, null, finance, new TemplateCompiler(), new ObjectMapper());

    @Test
    void monthEndCarriesForwardAndRefundDoesNotCancelTheReturnTwice() {
        PrintTemplate template = new PrintTemplate();
        template.setScene("STATEMENT");
        template.setContent("""
            <div class="opening" th:text="${statement.opening}"></div>
            <div class="setup" th:text="${statement.openingEntryAmount}"></div>
            <div class="sales" th:text="${statement.salesAmount}"></div>
            <div class="returns" th:text="${statement.returnAmount}"></div>
            <div class="receipts" th:text="${statement.receiptAmount}"></div>
            <div class="refunds" th:text="${statement.refundAmount}"></div>
            <div class="closing" th:text="${statement.closing}"></div>
            <div class="debt" th:text="${totals.debtTotal}"></div>
            <div th:each="item : ${statement.items}">
                <span class="kind" th:text="${item.content}"></span>
                <span class="amount" th:text="${item.amount}"></span>
            </div>
            """);
        when(templates.resolve(null, "STATEMENT", PARTY)).thenReturn(template);
        when(ledger.sumUnsettledByParty("AR", PARTY)).thenReturn(new BigDecimal("2240.78"));
        when(finance.selectStatementMovements(TENANT, PARTY, JAN, FEB)).thenReturn(List.of(
            movement("OPENING", null, "2000.00"), movement("SALE", "0", "500.00"),
            movement("RECEIPT", "0", "-300.00")));
        when(finance.selectStatementMovements(TENANT, PARTY, FEB, MAR)).thenReturn(List.of(
            movement("OPENING", "2000.00", "0"), movement("SALE", "500.00", "0"),
            movement("RECEIPT", "-300.00", "0"), movement("SALE_RETURN", "0", "-80.25"),
            movement("REFUND", "0", "30.15")));
        when(finance.selectStatementMovements(TENANT, PARTY, MAR, APR)).thenReturn(List.of(
            movement("OPENING", "2000.00", "0"), movement("SALE", "500.00", "0"),
            movement("RECEIPT", "-300.00", "0"), movement("SALE_RETURN", "-80.25", "0"),
            movement("REFUND", "30.15", "0")));
        when(finance.selectStatementLines(TENANT, PARTY, FEB, MAR)).thenReturn(List.of(
            line("销售退货", "XT-7", "-80.25"), line("退款", "SK-7", "30.15")));

        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);
            var jan = Jsoup.parse(service.renderStatement(PARTY, "2026-01", null));
            assertEquals("0.00", jan.select(".opening").text());
            assertEquals("2000.00", jan.select(".setup").text());
            assertEquals("500.00", jan.select(".sales").text());
            assertEquals("300.00", jan.select(".receipts").text());
            assertEquals("2200.00", jan.select(".closing").text());
            var feb = Jsoup.parse(service.renderStatement(PARTY, "2026-02", null));
            assertEquals("2200.00", feb.select(".opening").text());
            assertEquals("80.25", feb.select(".returns").text());
            assertEquals("30.15", feb.select(".refunds").text());
            assertEquals("2149.90", feb.select(".closing").text());
            assertEquals("2240.78", feb.select(".debt").text());
            assertEquals(List.of("销售退货", "退款"), feb.select(".kind").eachText());
            assertEquals(List.of("-80.25", "30.15"), feb.select(".amount").eachText());
            var mar = Jsoup.parse(service.renderStatement(PARTY, "2026-03", null));
            assertEquals("2149.90", mar.select(".opening").text());
            assertEquals("2149.90", mar.select(".closing").text());
            assertTrue(mar.select(".kind").isEmpty());
        }
        verify(finance).selectStatementLines(TENANT, PARTY, JAN, FEB);
        verify(finance).selectStatementLines(TENANT, PARTY, FEB, MAR);
        verify(finance).selectStatementLines(TENANT, PARTY, MAR, APR);
        verify(finance).selectStatementMovements(TENANT, PARTY, JAN, FEB);
        verify(finance).selectStatementMovements(TENANT, PARTY, FEB, MAR);
        verify(finance).selectStatementMovements(TENANT, PARTY, MAR, APR);
        verifyNoMoreInteractions(finance);
    }

    @Test
    void doesNotRenderATruncatedStatement() {
        when(finance.selectStatementLines(TENANT, PARTY, FEB, MAR)).thenReturn(
            Collections.nCopies(5001, line("收款", "SK-7", "-1.00")));
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(TENANT);
            var rejected = assertThrows(ServiceException.class, () -> service.renderStatement(PARTY, "2026-02", null));
            assertTrue(rejected.getMessage().contains("5000"));
        }
        verify(finance).selectStatementLines(TENANT, PARTY, FEB, MAR);
        verifyNoMoreInteractions(finance);
        verifyNoInteractions(templates, ledger, customers);
    }

    @Test
    void missingTenantStopsBeforeQueryAndTemplateResolution() {
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn(null);
            var rejected = assertThrows(ServiceException.class, () -> service.renderStatement(PARTY, "2026-02", null));
            assertEquals(40901, rejected.getCode());
        }
        verifyNoInteractions(finance, templates, ledger, customers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-2", "2026-13", "2026-02-01", "+2026-02", "0999-12", "9999-12"})
    void invalidMonthsAreRejectedBeforeQuery(String month) {
        assertThrows(ServiceException.class, () -> service.renderStatement(PARTY, month, null));
        verifyNoInteractions(finance, templates, ledger, customers);
    }

    private static PrintLedgerMovement movement(String source, String prior, String period) {
        var row = new PrintLedgerMovement();
        row.setSourceType(source);
        row.setPriorAmount(prior == null ? null : new BigDecimal(prior));
        row.setPeriodAmount(new BigDecimal(period));
        return row;
    }

    private static PrintStatementLine line(String content, String no, String amount) {
        var row = new PrintStatementLine();
        row.setEventDate(Date.valueOf(FEB));
        row.setDocumentNo(no);
        row.setContent(content);
        row.setSignedAmount(new BigDecimal(amount));
        return row;
    }
}
