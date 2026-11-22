package org.erp.print.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.service.IPrintTemplateService;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 商品数量精度继续从批量的明细投影读取，不回退到逐行查商品。 */
@Tag("dev")
class PrintStatementQtyScaleTest {

    @Test
    @DisplayName("数量按商品精度展示")
    void rendersQtyByProductScale() {
        var templateService = mock(IPrintTemplateService.class);
        var saleMapper = mock(ErpSaleMapper.class);
        var saleItemMapper = mock(ErpSaleItemMapper.class);
        var arApMapper = mock(FinArApMapper.class);
        var receiptMapper = mock(FinReceiptMapper.class);
        var customerMapper = mock(ErpCustomerMapper.class);
        var productMapper = mock(ErpProductMapper.class);
        var financeMapper = mock(PrintFinanceMapper.class);
        LocalDate from = LocalDate.of(2026, 11, 1);
        when(financeMapper.selectStatementLines("TENANT_A", 9L, from, from.plusMonths(1))).thenReturn(List.of(
            line("压射室", 0, "1.000"), line("压射头", 2, "2.5"), line(null, null, "1.234")));

        PrintServiceImpl service = new PrintServiceImpl(templateService, saleMapper, receiptMapper, null, null, null,
            arApMapper, null, customerMapper, null, productMapper, saleItemMapper, null, null, financeMapper,
            new TemplateCompiler(), new ObjectMapper());
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("TENANT_A");
            String html = service.renderStatement(9L, "2026-11", null);
            assertTrue(html.contains(">1<"), "整数精度商品应显示 1");
            assertTrue(html.contains(">2.50<"), "两位精度商品应显示 2.50");
            assertTrue(html.contains(">1.234<"), "商品已删除时应回落三位保真");
            assertFalse(html.contains("1.000"), "整数精度商品不应显示 1.000");
        }
        verifyNoInteractions(saleMapper, saleItemMapper, productMapper, receiptMapper);
    }

    private static PrintStatementLine line(String productName, Integer qtyScale, String qty) {
        var row = new PrintStatementLine();
        row.setEventDate(Date.valueOf("2026-11-07"));
        row.setDocumentNo("XS202611070001");
        row.setContent("销售");
        row.setProductName(productName);
        row.setQty(new BigDecimal(qty));
        row.setQtyScale(qtyScale);
        row.setPrice(new BigDecimal("100.00"));
        row.setSignedAmount(new BigDecimal("100.00"));
        return row;
    }

    @Test
    @DisplayName("所选月的累计金额包含历史，不包含下月")
    void cumulativeAmountsIncludeHistoryButNotNextMonth() {
        var templateService = mock(IPrintTemplateService.class);
        var customerMapper = mock(ErpCustomerMapper.class);
        var ledgerMapper = mock(FinArApMapper.class);
        var financeMapper = mock(PrintFinanceMapper.class);
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = from.plusMonths(1);
        when(financeMapper.selectStatementLines("TENANT_A", 9L, from, to)).thenReturn(List.of(
            statementLine("2026-10-10", "XS202610100001", "销售", "200.00"),
            statementLine("2026-10-12", "XT202610120001", "销售退货", "-20.00"),
            statementLine("2026-10-15", "SK202610150001", "收款", "-40.00")));
        when(financeMapper.selectStatementMovements("TENANT_A", 9L, from, to)).thenReturn(List.of(
            movement("OPENING", "50.00", "0.00"), movement("SALE", "100.00", "200.00"),
            movement("SALE_RETURN", "-10.00", "-20.00"), movement("RECEIPT", "-30.00", "-40.00")));
        when(customerMapper.selectById(9L)).thenReturn(new ErpCustomer());

        PrintServiceImpl service = new PrintServiceImpl(templateService, null, null, null, null, null,
            ledgerMapper, null, customerMapper, null, null, null, null, null, financeMapper,
            new TemplateCompiler(), new ObjectMapper());
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("TENANT_A");
            String html = service.renderStatement(9L, "2026-10", null);
            assertTrue(html.contains("期初应收：<span>110.00</span>"), "期初应取所选月之前各来源台账余额合计");
            assertTrue(html.contains("本期净发生额：<span>140.00</span>"), "本期净发生额应为净销售减净收款");
            assertTrue(html.contains("累计销售净额：<span>270.00</span>"), "累计销售净额应含历史且不含下月");
            assertTrue(html.contains("累计净收款：<span>70.00</span>"), "累计净收款应含历史收款");
            assertTrue(html.contains("累计未付金额：<b>250.00</b>"), "累计未付即所选月期末余额");
        }
    }

    @Test
    @DisplayName("对账单红字收款退款正确冲减净收款并计入期末")
    void negativeReceiptRefundNetsIntoClosing() {
        var templateService = mock(IPrintTemplateService.class);
        var customerMapper = mock(ErpCustomerMapper.class);
        var ledgerMapper = mock(FinArApMapper.class);
        var financeMapper = mock(PrintFinanceMapper.class);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = from.plusMonths(1);
        when(financeMapper.selectStatementLines("TENANT_A", 9L, from, to)).thenReturn(List.of(
            statementLine("2026-11-07", "XS202611070001", "销售", "1000.00"),
            statementLine("2026-11-07", "SK202611070001", "收款", "-800.00"),
            statementLine("2026-11-08", "SK202611070002", "退款", "200.00")));
        when(financeMapper.selectStatementMovements("TENANT_A", 9L, from, to)).thenReturn(List.of(
            movement("SALE", "0.00", "1000.00"), movement("RECEIPT", "0.00", "-800.00"),
            movement("REFUND", "0.00", "200.00")));
        when(customerMapper.selectById(9L)).thenReturn(new ErpCustomer());

        PrintServiceImpl service = new PrintServiceImpl(templateService, null, null, null, null, null,
            ledgerMapper, null, customerMapper, null, null, null, null, null, financeMapper,
            new TemplateCompiler(), new ObjectMapper());
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("TENANT_A");
            String html = service.renderStatement(9L, "2026-11", null);
            assertTrue(html.contains("本期退款：<span>200.00</span>"), "退款按正数展示");
            assertTrue(html.contains("本期净发生额：<span>400.00</span>"), "净发生额应为销售 1000 减净收款 600");
            assertTrue(html.contains("累计净收款：<span>600.00</span>"), "累计净收款应为 800 减 200");
            assertTrue(html.contains("期末应收：<b>400.00</b>"), "销售 1000 减净收款 600 等于 400");
            assertTrue(html.contains("退款"), "明细中应展示退款摘要");
        }
    }

    @Test
    @DisplayName("期初截止日落在账期当月时，期初金额计入期末应收")
    void openingDateInsideMonthCountsIntoClosing() {
        var templateService = mock(IPrintTemplateService.class);
        var customerMapper = mock(ErpCustomerMapper.class);
        var ledgerMapper = mock(FinArApMapper.class);
        var financeMapper = mock(PrintFinanceMapper.class);
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = from.plusMonths(1);
        when(financeMapper.selectStatementLines("TENANT_A", 9L, from, to)).thenReturn(List.of());
        when(financeMapper.selectStatementMovements("TENANT_A", 9L, from, to)).thenReturn(List.of(
            movement("OPENING", "0.00", "65004.00")));
        when(customerMapper.selectById(9L)).thenReturn(new ErpCustomer());

        PrintServiceImpl service = new PrintServiceImpl(templateService, null, null, null, null, null,
            ledgerMapper, null, customerMapper, null, null, null, null, null, financeMapper,
            new TemplateCompiler(), new ObjectMapper());
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("TENANT_A");
            String html = service.renderStatement(9L, "2026-09", null);
            assertTrue(html.contains("本期建账期初：<span>65004.00</span>"), "期初所属月应把金额计入本期建账期初");
            assertTrue(html.contains("期末应收：<b>65004.00</b>"), "期末应收应等于期初金额");
        }
    }

    private static PrintLedgerMovement movement(String sourceType, String priorAmount, String periodAmount) {
        var row = new PrintLedgerMovement();
        row.setSourceType(sourceType);
        row.setPriorAmount(new BigDecimal(priorAmount));
        row.setPeriodAmount(new BigDecimal(periodAmount));
        return row;
    }

    private static PrintStatementLine statementLine(String date, String no, String content, String amount) {
        var row = new PrintStatementLine();
        row.setEventDate(Date.valueOf(date));
        row.setDocumentNo(no);
        row.setContent(content);
        row.setSignedAmount(new BigDecimal(amount));
        return row;
    }
}
