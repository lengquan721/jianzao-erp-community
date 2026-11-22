package org.erp.print.service.impl;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.print.controller.PrintController;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.service.IPrintTemplateService;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.domain.ErpPurchase;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** B04/B07: no database, HTTP requests, or production credentials. */
@Tag("dev")
class PrintFinanceAndProfitTest {
    private static final String TENANT = "TENANT_A";
    private static final String PROFIT_PERMISSION = "erp:print:profit";
    private static final Long DOCUMENT = 41L;
    private static final Long PARTY = 7L;

    private final IPrintTemplateService templates = mock(IPrintTemplateService.class);
    private final ErpSaleMapper sales = mock(ErpSaleMapper.class);
    private final ErpSaleItemMapper saleItems = mock(ErpSaleItemMapper.class);
    private final ErpPurchaseMapper purchases = mock(ErpPurchaseMapper.class);
    private final ErpPurchaseItemMapper purchaseItems = mock(ErpPurchaseItemMapper.class);
    private final FinArApMapper ledger = mock(FinArApMapper.class);
    private final PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
    private final ErpCustomerMapper customers = mock(ErpCustomerMapper.class);
    private final ErpSupplierMapper suppliers = mock(ErpSupplierMapper.class);
    private final ErpProductMapper products = mock(ErpProductMapper.class);
    private ErpSale sale;
    private ErpPurchase purchase;
    private PrintServiceImpl service;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(ErpSale.class, ErpSaleItem.class, ErpProduct.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                var assistant = new MapperBuilderAssistant(new Configuration(), "");
                assistant.setCurrentNamespace(type.getName());
                TableInfoHelper.initTableInfo(assistant, type);
            }
        }
        service = new PrintServiceImpl(templates, sales, null, null, null, null,
            ledger, null, customers, suppliers, products, saleItems, purchases, purchaseItems,
            finance, new TemplateCompiler(), new ObjectMapper());
        sale = new ErpSale();
        sale.setId(DOCUMENT);
        sale.setCustomerId(PARTY);
        sale.setStatus("CONFIRMED");
        sale.setTotalAmount(new BigDecimal("500.00"));
        // Even an over-populated mock entity must never become an ordinary print's profit model.
        sale.setCostAmount(new BigDecimal("9876.54"));
        sale.setGrossProfit(new BigDecimal("9123.45"));
        when(sales.selectOne(any())).thenReturn(sale);
        when(saleItems.selectList(any())).thenReturn(List.of());
        purchase = new ErpPurchase();
        purchase.setId(DOCUMENT);
        purchase.setSupplierId(PARTY);
        purchase.setStatus("CONFIRMED");
        purchase.setTotalAmount(new BigDecimal("500.00"));
        when(purchases.selectById(DOCUMENT)).thenReturn(purchase);
        when(purchaseItems.selectList(any())).thenReturn(List.of());
        String balances = """
            <p>Unsettled amount <span th:text="${totals.unsettledAmount}"></span></p>
            <p>Total debt <span th:text="${totals.debtTotal}"></span></p>
            """;
        PrintTemplate saleTemplate = new PrintTemplate();
        saleTemplate.setScene("SALE_DELIVERY");
        saleTemplate.setContent(balances + """
            <p>Cost amount <span th:text="${profit == null ? 'ABSENT' : profit.costAmount}"></span></p>
            <p>Gross profit <span th:text="${profit == null ? '' : profit.grossProfit}"></span></p>
            <p>Gross margin <span th:text="${profit == null ? '' : profit.grossMargin}"></span></p>
            <p>Show profit <span th:text="${showProfit}"></span></p>
            """);
        PrintTemplate purchaseTemplate = new PrintTemplate();
        purchaseTemplate.setScene("PURCHASE_IN");
        purchaseTemplate.setContent(balances);
        when(templates.resolve(any(), eq(saleTemplate.getScene()), any())).thenReturn(saleTemplate);
        when(templates.resolve(any(), eq(purchaseTemplate.getScene()), any())).thenReturn(purchaseTemplate);
    }

    @ParameterizedTest
    @CsvSource({
        "SALE,0.00,900.00", "SALE,120.55,20.00", "SALE,120.55,-30.00", "SALE,1200.00,1500.00",
        "PURCHASE,0.00,990.00", "PURCHASE,100.12,10.00", "PURCHASE,100.12,-5.00", "PURCHASE,1300.00,1500.00"
    })
    void sourceBalanceIsIndependentOfOtherDocumentsCreditsAndDocumentTotal(String source, String balance, String debt) {
        when(ledger.sumUnsettledByParty(partyType(source), PARTY)).thenReturn(new BigDecimal(debt));
        when(finance.sumUnsettledBySource(TENANT, partyType(source), PARTY, source, DOCUMENT))
            .thenReturn(new BigDecimal(balance));
        try (MockedStatic<TenantHelper> tenant = tenant(TENANT)) {
            String html = render(source);
            assertEquals(balance, text(html, "Unsettled amount"));
            assertEquals(debt, text(html, "Total debt"));
            verify(ledger).sumUnsettledByParty(partyType(source), PARTY);
            verify(finance).sumUnsettledBySource(TENANT, partyType(source), PARTY, source, DOCUMENT);
            verify(finance, never()).selectSaleProfit(any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALE", "PURCHASE"})
    void confirmedDocumentWithMissingOrOnlyInvalidSourceLedgerFailsExplicitly(String source) {
        when(ledger.sumUnsettledByParty(partyType(source), PARTY)).thenReturn(new BigDecimal("999.00"));
        try (MockedStatic<TenantHelper> tenant = tenant(TENANT)) {
            ServiceException error = assertThrows(ServiceException.class, () -> render(source));
            assertTrue(error.getMessage().contains("缺少有效来源台账：" + source + "/" + DOCUMENT));
            verifyNoInteractions(templates);
        }
    }

    @ParameterizedTest
    @CsvSource({"SALE,DRAFT", "SALE,VOID", "PURCHASE,DRAFT", "PURCHASE,VOID"})
    void draftWithdrawnAndVoidDocumentsHaveNoInventedSourceBalance(String source, String status) {
        sale.setStatus(status);
        purchase.setStatus(status);
        when(ledger.sumUnsettledByParty(partyType(source), PARTY)).thenReturn(new BigDecimal("80.00"));
        String html = render(source);
        assertEquals("", text(html, "Unsettled amount"));
        assertEquals("80.00", text(html, "Total debt"));
        verifyNoInteractions(finance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALE", "PURCHASE"})
    void missingTenantNeverFallsBackToAnotherTenantsSource(String source) {
        try (MockedStatic<TenantHelper> tenant = tenant(null)) {
            ServiceException error = assertThrows(ServiceException.class, () -> render(source));
            assertTrue(error.getMessage().contains("缺少租户上下文"));
            verifyNoInteractions(finance, templates);
        }
    }

    @Test
    void ordinaryCustomPrintHasNoRealProfitAndUsesOnlyNonCostProjections() {
        ErpSaleItem item = new ErpSaleItem();
        item.setProductId(8L);
        item.setQty(BigDecimal.ONE);
        item.setAmount(BigDecimal.TEN);
        when(saleItems.selectList(any())).thenReturn(List.of(item));
        when(products.selectOne(any())).thenReturn(new ErpProduct());
        when(finance.sumUnsettledBySource(TENANT, "AR", PARTY, "SALE", DOCUMENT)).thenReturn(BigDecimal.ZERO);
        try (MockedStatic<TenantHelper> tenant = tenant(TENANT);
             MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkPermission(PROFIT_PERMISSION))
                .thenThrow(new NotPermissionException(PROFIT_PERMISSION));
            String html = service.renderSaleDelivery(DOCUMENT, null, false, 1);
            assertEquals("ABSENT", text(html, "Cost amount"));
            assertEquals("", text(html, "Gross profit"));
            assertEquals("", text(html, "Gross margin"));
            assertEquals("false", text(html, "Show profit"));
            assertFalse(html.contains("9876.54"));
            assertFalse(html.contains("9123.45"));
            verify(finance, never()).selectSaleProfit(any(), any());
            verify(sales, never()).selectById(anyLong());
            verify(sales).selectOne(argThat(this::isNonCostProjection));
            verify(saleItems).selectList(argThat(this::isNonCostProjection));
            verify(products).selectOne(argThat(this::isNonCostProjection));
            verify(products, never()).selectById(anyLong());
            stp.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"REAL", "PREVIEW", "DESIGN"})
    void requestedProfitRequiresIndependentPermissionBeforeAnyReadOrModel(String entry) {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkPermission(PROFIT_PERMISSION))
                .thenThrow(new NotPermissionException(PROFIT_PERMISSION));
            assertThrows(NotPermissionException.class, () -> {
                switch (entry) {
                    case "REAL" -> service.renderSaleDelivery(DOCUMENT, null, true, 1);
                    case "PREVIEW" -> service.preview("SALE_DELIVERY", "<span>sample</span>", PARTY, true);
                    case "DESIGN" -> service.previewDesign("SALE_DELIVERY", "{}", true);
                    default -> throw new AssertionError(entry);
                }
            });
            stp.verify(() -> StpUtil.checkPermission(PROFIT_PERMISSION));
            verifyNoInteractions(sales, saleItems, purchases, purchaseItems, ledger, finance, templates,
                customers, suppliers, products);
        }
    }

    @Test
    void authorizedProfitUsesSeparateTenantScopedProjection() {
        PrintContext.Profit profit = new PrintContext.Profit();
        profit.setCostAmount(new BigDecimal("123.40"));
        profit.setGrossProfit(new BigDecimal("376.60"));
        profit.setGrossMargin(new BigDecimal("75.32"));
        when(finance.sumUnsettledBySource(TENANT, "AR", PARTY, "SALE", DOCUMENT)).thenReturn(BigDecimal.ZERO);
        when(finance.selectSaleProfit(TENANT, DOCUMENT)).thenReturn(profit);
        try (MockedStatic<TenantHelper> tenant = tenant(TENANT);
             MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            String html = service.renderSaleDelivery(DOCUMENT, null, true, 1);
            stp.verify(() -> StpUtil.checkPermission(PROFIT_PERMISSION));
            verify(finance).selectSaleProfit(TENANT, DOCUMENT);
            verify(sales).selectOne(argThat(this::isNonCostProjection));
            assertEquals("123.40", text(html, "Cost amount"));
            assertEquals("376.60", text(html, "Gross profit"));
            assertEquals("75.32", text(html, "Gross margin"));
            assertEquals("true", text(html, "Show profit"));
            assertFalse(html.contains("9876.54"));
        }
    }

    @Test
    void previewWithPermissionStillUsesOnlyFixedExamplesAndNeverCustomerData() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            String html = service.preview("SALE_DELIVERY", "<b th:text=\"${profit.costAmount}\"></b>", PARTY, true);
            assertTrue(html.contains("158.80"));
            stp.verify(() -> StpUtil.checkPermission(PROFIT_PERMISSION));
            verifyNoInteractions(sales, saleItems, purchases, purchaseItems, ledger, finance, templates,
                customers, suppliers, products);
        }
    }

    @Test
    void controllerKeepsOrdinaryPermissionAndDefaultsProfitFlagToFalse() throws Exception {
        var saleMethod = PrintController.class.getMethod("saleDelivery", Long.class, Long.class, boolean.class, int.class);
        assertArrayEquals(new String[]{"erp:print:render"}, saleMethod.getAnnotation(SaCheckPermission.class).value());
        assertEquals("false", saleMethod.getParameters()[2].getAnnotation(RequestParam.class).defaultValue());
        var preview = PrintController.class.getMethod("preview", String.class, String.class, Long.class,
            boolean.class, String.class);
        assertArrayEquals(new String[]{"erp:print:template:preview"}, preview.getAnnotation(SaCheckPermission.class).value());
        assertEquals("false", preview.getParameters()[3].getAnnotation(RequestParam.class).defaultValue());
        assertFalse(preview.getParameters()[4].getAnnotation(RequestParam.class).required(), "paper 必须可选");
        var design = PrintController.class.getMethod("previewDesign", String.class, String.class, boolean.class);
        assertArrayEquals(new String[]{"erp:print:template:preview"}, design.getAnnotation(SaCheckPermission.class).value());
        assertEquals("false", design.getParameters()[2].getAnnotation(RequestParam.class).defaultValue());
    }

    private boolean isNonCostProjection(Wrapper<?> query) {
        assertNotNull(query.getSqlSelect());
        String columns = query.getSqlSelect().toLowerCase(java.util.Locale.ROOT);
        assertFalse(columns.contains("*"), columns);
        assertFalse(columns.contains("cost"), columns);
        assertFalse(columns.contains("gross"), columns);
        return true;
    }

    private String render(String source) {
        return "SALE".equals(source) ? service.renderSaleDelivery(DOCUMENT, null, false, 1)
            : service.renderPurchaseIn(DOCUMENT, null);
    }

    private String partyType(String source) {
        return "SALE".equals(source) ? "AR" : "AP";
    }

    private String text(String html, String label) {
        // The sanitizer strips ids; locate each value by its adjacent fixed label.
        var values = Jsoup.parse(html).select("p").stream()
            .filter(paragraph -> label.equals(paragraph.ownText()))
            .map(paragraph -> paragraph.selectFirst("span"))
            .filter(java.util.Objects::nonNull)
            .toList();
        assertEquals(1, values.size(), label);
        return values.get(0).text();
    }

    private MockedStatic<TenantHelper> tenant(String tenantId) {
        MockedStatic<TenantHelper> mock = mockStatic(TenantHelper.class);
        mock.when(TenantHelper::getTenantId).thenReturn(tenantId);
        return mock;
    }
}
