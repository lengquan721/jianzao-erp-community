package org.erp.print.service.impl;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.fin.mapper.*;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.domain.bo.PrintTemplateBo;
import org.erp.print.domain.design.DesignSchema;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.mapper.PrintTemplateMapper;
import org.erp.print.service.IPrintTemplateService;
import org.erp.trade.domain.ErpPurchase;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual service-entry guards; database and renderer calls are observed, not text-scanned. */
@Tag("dev")
class PrintTemplateEntrySecurityTest {
    private static final long DOCUMENT = 41L;
    private static final long TEMPLATE = 99L;
    private static final String SALE = "SALE_DELIVERY";
    private static final String PURCHASE = "PURCHASE_IN";
    private static final String PROFIT_PERMISSION = "erp:print:profit";

    private final IPrintTemplateService templates = mock(IPrintTemplateService.class);
    private final ErpSaleMapper sales = mock(ErpSaleMapper.class);
    private final FinReceiptMapper receipts = mock(FinReceiptMapper.class);
    private final FinReceiptAllocMapper receiptAllocs = mock(FinReceiptAllocMapper.class);
    private final FinPaymentMapper payments = mock(FinPaymentMapper.class);
    private final FinPaymentAllocMapper paymentAllocs = mock(FinPaymentAllocMapper.class);
    private final FinArApMapper ledger = mock(FinArApMapper.class);
    private final FinOpeningMapper openings = mock(FinOpeningMapper.class);
    private final ErpCustomerMapper customers = mock(ErpCustomerMapper.class);
    private final ErpSupplierMapper suppliers = mock(ErpSupplierMapper.class);
    private final ErpProductMapper products = mock(ErpProductMapper.class);
    private final ErpSaleItemMapper saleItems = mock(ErpSaleItemMapper.class);
    private final ErpPurchaseMapper purchases = mock(ErpPurchaseMapper.class);
    private final ErpPurchaseItemMapper purchaseItems = mock(ErpPurchaseItemMapper.class);
    private final PrintFinanceMapper finance = mock(PrintFinanceMapper.class);
    private final PrintTemplateMapper templateMapper = mock(PrintTemplateMapper.class);
    private final TemplateCompiler compiler = spy(new TemplateCompiler());
    private final TemplateSanitizer savingSanitizer = spy(new TemplateSanitizer());
    private final ObjectMapper objectMapper = spy(new ObjectMapper());
    private PrintServiceImpl printer;
    private PrintTemplateServiceImpl saver;
    private PrintTemplate stored;
    private ErpSale sale;
    private ErpPurchase purchase;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(ErpSale.class, ErpSaleItem.class, ErpProduct.class, PrintTemplate.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                var assistant = new MapperBuilderAssistant(new Configuration(), "");
                assistant.setCurrentNamespace(type.getName());
                TableInfoHelper.initTableInfo(assistant, type);
            }
        }
        printer = newPrinter();
        saver = new PrintTemplateServiceImpl(templateMapper, finance, customers, savingSanitizer, compiler, objectMapper);
        sale = new ErpSale();
        sale.setId(DOCUMENT);
        sale.setNo("XS-ENTRY");
        sale.setBizDate(new Date(0));
        sale.setStatus("DRAFT"); // No ledger lookup is required to reach the real rendering entry.
        sale.setTotalAmount(new BigDecimal("100.00"));
        when(sales.selectOne(any())).thenReturn(sale);
        when(saleItems.selectList(any())).thenReturn(List.of());
        purchase = new ErpPurchase();
        purchase.setId(DOCUMENT);
        purchase.setNo("CG-ENTRY");
        purchase.setBizDate(new Date(0));
        purchase.setStatus("DRAFT");
        purchase.setTotalAmount(new BigDecimal("100.00"));
        when(purchases.selectById(DOCUMENT)).thenReturn(purchase);
        when(purchaseItems.selectList(any())).thenReturn(List.of());
        stored = new PrintTemplate();
        stored.setContent("<div>original</div>");
        stored.setName("original");
        stored.setVersion(7);
        // Successful formal-print fixtures must satisfy resolve's actual-scene contract.
        stored.setScene(SALE);
        when(templates.resolve(any(), eq(SALE), any())).thenReturn(stored);
        when(templates.resolve(any(), eq(PURCHASE), any())).thenReturn(stored);
        when(templateMapper.selectById(TEMPLATE)).thenReturn(stored);
    }

    private PrintServiceImpl newPrinter() {
        return new PrintServiceImpl(templates, sales, receipts, receiptAllocs, payments, paymentAllocs,
            ledger, openings, customers, suppliers, products, saleItems, purchases, purchaseItems,
            finance, compiler, objectMapper);
    }

    record SourceCase(String scene, String html, String reason) {
        @Override public String toString() { return reason + "@" + scene; }
    }

    static Stream<SourceCase> rejectedSources() {
        return Stream.of(
            new SourceCase(SALE, "<b th:text=\"${doc.no.toString()}\"></b>", "method"),
            new SourceCase(SALE, "<b th:text=\"${tenant.secret}\"></b>", "unknown"),
            new SourceCase(SALE, "<b th:text=\"${doc.class}\"></b>", "class"),
            new SourceCase(SALE, "<b th:text=\"${statement.month}\"></b>", "cross-scene"),
            new SourceCase(PURCHASE, "<b th:text=\"${profit.costAmount}\"></b>", "profit-cross-scene"),
            new SourceCase(SALE, "<div>" + "x".repeat(TemplateSanitizer.MAX_TEMPLATE_CHARS - 10) + "</div>", "length-plus-one")
        );
    }

    @ParameterizedTest @MethodSource("rejectedSources")
    void sourcePreviewRejectsBeforeRendererOrDatabase(SourceCase input) {
        try (MockedStatic<ThymeleafRenderer> renderer = mockStatic(ThymeleafRenderer.class)) {
            assertThrows(IllegalArgumentException.class,
                () -> printer.preview(input.scene(), input.html(), 123L, false));
            renderer.verifyNoInteractions();
        }
        verifyNoInteractions(databaseMocks());
        verifyNoInteractions(compiler, objectMapper);
    }

    @ParameterizedTest @MethodSource("rejectedSources")
    void sourceInsertRejectsBeforeAnyMapperCall(SourceCase input) {
        assertThrows(IllegalArgumentException.class, () -> saver.insert(sourceBo(input.scene(), input.html())));
        verify(savingSanitizer).clean(input.html(), input.scene());
        verifyNoInteractions(databaseMocks());
    }

    @ParameterizedTest @MethodSource("rejectedSources")
    void sourceUpdateRejectsWithoutMutatingOrWriting(SourceCase input) {
        assertThrows(IllegalArgumentException.class,
            () -> saver.update(TEMPLATE, sourceBo(input.scene(), input.html())));
        verify(savingSanitizer).clean(input.html(), input.scene());
        assertUpdateUntouched();
    }

    @ParameterizedTest @MethodSource("rejectedSources")
    void oldStoredSourceIsCheckedAgainstActualSceneBeforeRenderer(SourceCase input) {
        stored.setScene("STATEMENT"); // Deliberately wrong only for the scene-mismatch regression.
        stored.setContent(input.html());
        try (MockedStatic<ThymeleafRenderer> renderer = mockStatic(ThymeleafRenderer.class)) {
            ServiceException error = assertThrows(ServiceException.class, () -> renderTemplateBoundary(input.scene()));
            assertEquals("打印模板场景不匹配：当前场景为 " + input.scene(), error.getMessage());
            renderer.verifyNoInteractions();
        }
        verify(templates).resolve(TEMPLATE, input.scene(), null);
        verifyNoMoreInteractions(templates);
        verifyNoInteractions(sales, receipts, receiptAllocs, payments, paymentAllocs, ledger, openings,
            customers, suppliers, products, saleItems, purchases, purchaseItems, finance, templateMapper);
        verifyNoInteractions(compiler, objectMapper);
    }

    @ParameterizedTest @ValueSource(strings = {"SALE_DELIVERY", "PURCHASE_IN"})
    void explicitMismatchedStoredSceneAlsoRejectsAtFormalEntry(String scene) {
        stored.setScene("STATEMENT");
        try (MockedStatic<ThymeleafRenderer> renderer = mockStatic(ThymeleafRenderer.class)) {
            ServiceException error = assertThrows(ServiceException.class, () -> printDocument(scene));
            assertEquals("打印模板场景不匹配：当前场景为 " + scene, error.getMessage());
            renderer.verifyNoInteractions();
        }
        verify(templates).resolve(TEMPLATE, scene, null);
        // Formal entry loads the document before the shared render guard; permit only those mocked reads.
        if (SALE.equals(scene)) {
            verify(sales).selectOne(any());
            verify(saleItems).selectList(any());
        } else {
            verify(purchases).selectById(DOCUMENT);
            verify(purchaseItems).selectList(any());
        }
        verifyNoMoreInteractions(databaseMocks());
    }

    @ParameterizedTest @MethodSource("rejectedSources")
    void matchingStoredSceneStillRejectsUnsafeSourceBeforeRenderer(SourceCase input) {
        stored.setScene(input.scene());
        stored.setContent(input.html());
        try (MockedStatic<ThymeleafRenderer> renderer = mockStatic(ThymeleafRenderer.class)) {
            assertThrows(IllegalArgumentException.class, () -> printDocument(input.scene()));
            renderer.verifyNoInteractions();
        }
        verify(templates).resolve(TEMPLATE, input.scene(), null);
        verifyNoInteractions(finance, templateMapper);
    }

    record DesignCase(String scene, String json, String reason) {
        @Override public String toString() { return reason + "@" + scene; }
    }

    static Stream<DesignCase> rejectedDesigns() throws Exception {
        return Stream.of(
            new DesignCase(SALE, fieldJson("doc.no()"), "method"),
            new DesignCase(SALE, fieldJson("tenant.secret"), "unknown"),
            new DesignCase(SALE, fieldJson("statement.month"), "cross-scene"),
            new DesignCase(PURCHASE, fieldJson("profit.costAmount"), "profit-cross-scene"),
            new DesignCase(SALE, jsonBlock("TABLE", Map.of("dataSource", "statement.items",
                "columns", List.of(Map.of("key", "no")))), "source-cross-scene"),
            new DesignCase(SALE, designAtCompiledLength(TemplateSanitizer.MAX_TEMPLATE_CHARS + 1), "length-plus-one")
        );
    }

    @ParameterizedTest @MethodSource("rejectedDesigns")
    void designPreviewPassesActualSceneAndRejectsBeforeRenderer(DesignCase input) {
        try (MockedStatic<ThymeleafRenderer> renderer = mockStatic(ThymeleafRenderer.class)) {
            ServiceException error = assertThrows(ServiceException.class,
                () -> printer.previewDesign(input.scene(), input.json(), false));
            assertTrue(error.getMessage().contains("设计器内容无法编译"));
            renderer.verifyNoInteractions();
        }
        verify(compiler).compile(any(DesignSchema.class), eq(input.scene()));
        verify(compiler, never()).compile(any(DesignSchema.class));
        verifyNoInteractions(databaseMocks());
    }

    @ParameterizedTest @MethodSource("rejectedDesigns")
    void designInsertPassesActualSceneAndRejectsBeforeDatabase(DesignCase input) {
        assertThrows(ServiceException.class, () -> saver.insert(designBo(input.scene(), input.json())));
        verify(compiler).compile(any(DesignSchema.class), eq(input.scene()));
        verify(compiler, never()).compile(any(DesignSchema.class));
        verifyNoInteractions(databaseMocks());
    }

    @ParameterizedTest @MethodSource("rejectedDesigns")
    void designUpdatePassesActualSceneAndRejectsWithoutWrite(DesignCase input) {
        assertThrows(ServiceException.class, () -> saver.update(TEMPLATE, designBo(input.scene(), input.json())));
        verify(compiler).compile(any(DesignSchema.class), eq(input.scene()));
        verify(compiler, never()).compile(any(DesignSchema.class));
        assertUpdateUntouched();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN", "RECEIPT", "PAYMENT"})
    void existingTemplatesPreviewAndBothSaveModesSucceed(String scene) throws Exception {
        String source = switch (scene) {
            case "SALE_DELIVERY" -> BuiltinTemplates.saleDelivery().getContent();
            case "STATEMENT" -> BuiltinTemplates.statement().getContent();
            case "STATEMENT_RANGE" -> BuiltinTemplates.statementRange().getContent();
            case "PURCHASE_IN" -> BuiltinTemplates.purchaseIn().getContent();
            default -> BuiltinTemplates.receiptPayment().getContent();
        };
        String design = new ObjectMapper().writeValueAsString(DesignPresets.of(scene));
        assertTrue(printer.preview(scene, source, null, false).contains("示例机械有限公司"));
        assertTrue(printer.previewDesign(scene, design, false).contains("示例机械有限公司"));
        saver.insert(sourceBo(scene, source));
        saver.insert(designBo(scene, design));
        ArgumentCaptor<PrintTemplate> saved = ArgumentCaptor.forClass(PrintTemplate.class);
        verify(templateMapper, times(2)).insert(saved.capture());
        for (PrintTemplate row : saved.getAllValues()) {
            assertEquals(scene, row.getScene());
            assertTrue(row.getContent().contains("th:text"));
        }
        verify(compiler, times(2)).compile(any(DesignSchema.class), eq(scene));
        verify(savingSanitizer).clean(source, scene);
    }

    @ParameterizedTest @ValueSource(strings = {"SALE_DELIVERY", "PURCHASE_IN"})
    void existingBuiltinFallbackStillPrints(String scene) {
        when(templates.resolve(any(), any(), any())).thenReturn(null);
        String html = printDocument(scene);
        assertTrue(html.contains(SALE.equals(scene) ? "XS-ENTRY" : "CG-ENTRY"));
        assertFalse(html.contains("th:text"));
    }

    @Test
    void savedAndPreviewedHtmlActuallyUsesCleanedOutput() {
        String input = "<div th:text=\"${doc.no}\"></div><script>ENTRY_MARKER</script>";
        String html = printer.preview(SALE, input, null, false);
        assertFalse(html.contains("ENTRY_MARKER"));
        assertFalse(html.contains("<script"));
        assertTrue(html.contains("XS202610310001"));
        stored.setContent(input);
        String formal = printDocument(SALE);
        assertFalse(formal.contains("ENTRY_MARKER"));
        assertFalse(formal.contains("<script"));
        assertTrue(formal.contains("XS-ENTRY"));
    }

    @Test
    void storedProfitNullGuardRemainsValidWithoutLoadingProfit() {
        stored.setContent("<div class=\"profit-result\" th:text=\"${profit == null ? 'ABSENT' : profit.costAmount}\"></div>");
        String html = printDocument(SALE);
        assertEquals("ABSENT", Jsoup.parse(html).selectFirst(".profit-result").text());
        verifyNoInteractions(finance);
    }

    @Test
    void authorizedProfitPreviewRetainsGateAndFixedExample() {
        try (MockedStatic<StpUtil> permissions = mockStatic(StpUtil.class)) {
            String html = printer.preview(SALE, "<b th:text=\"${profit.costAmount}\"></b>", null, true);
            permissions.verify(() -> StpUtil.checkPermission(PROFIT_PERMISSION));
            assertTrue(html.contains("158.80"));
            verifyNoInteractions(databaseMocks());
        }
    }

    @Test
    void authorizedFormalProfitStillUsesB07Projection() {
        PrintContext.Profit profit = new PrintContext.Profit();
        profit.setCostAmount(new BigDecimal("123.40"));
        when(finance.selectSaleProfit("ENTRY_TENANT", DOCUMENT)).thenReturn(profit);
        stored.setContent("<b th:text=\"${profit == null ? 'ABSENT' : profit.costAmount}\"></b>");
        try (MockedStatic<StpUtil> permissions = mockStatic(StpUtil.class);
             MockedStatic<TenantHelper> tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("ENTRY_TENANT");
            assertTrue(printer.renderSaleDelivery(DOCUMENT, TEMPLATE, true, 1).contains("123.40"));
            permissions.verify(() -> StpUtil.checkPermission(PROFIT_PERMISSION));
            verify(finance).selectSaleProfit("ENTRY_TENANT", DOCUMENT);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"SALE_DELIVERY", "PURCHASE_IN"})
    void confirmedDocumentBalancesRemainSourceScopedAfterRuntimeCleaning(String scene) {
        sale.setStatus("CONFIRMED");
        sale.setCustomerId(7L);
        purchase.setStatus("CONFIRMED");
        purchase.setSupplierId(7L);
        String party = SALE.equals(scene) ? "AR" : "AP";
        String source = SALE.equals(scene) ? "SALE" : "PURCHASE";
        when(ledger.sumUnsettledByParty(party, 7L)).thenReturn(new BigDecimal("80.00"));
        when(finance.sumUnsettledBySource("ENTRY_TENANT", party, 7L, source, DOCUMENT))
            .thenReturn(new BigDecimal("20.00"));
        stored.setScene(scene);
        stored.setContent("<b class=\"balance\" th:text=\"${totals.unsettledAmount}\"></b>"
            + "<b class=\"debt\" th:text=\"${totals.debtTotal}\"></b>");
        try (MockedStatic<TenantHelper> tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("ENTRY_TENANT");
            var rendered = Jsoup.parse(printDocument(scene));
            assertEquals("20.00", rendered.selectFirst(".balance").text());
            assertEquals("80.00", rendered.selectFirst(".debt").text());
            verify(finance).sumUnsettledBySource("ENTRY_TENANT", party, 7L, source, DOCUMENT);
            verify(finance, never()).selectSaleProfit(any(), any());
        }
    }

    @Test
    void sourcePreviewAndInsertAcceptExactTemplateLimit() {
        String prefix = "<body><div>";
        String suffix = "</div></body>";
        String exact = prefix + "x".repeat(TemplateSanitizer.MAX_TEMPLATE_CHARS - prefix.length() - suffix.length()) + suffix;
        assertDoesNotThrow(() -> printer.preview(SALE, exact, null, false));
        assertDoesNotThrow(() -> saver.insert(sourceBo(SALE, exact)));
        assertDoesNotThrow(() -> saver.update(TEMPLATE, sourceBo(SALE, exact)));
        assertDoesNotThrow(() -> printDocument(SALE));
    }

    @Test
    void designPreviewAndInsertAcceptExactCompiledLimit() throws Exception {
        String exact = designAtCompiledLength(TemplateSanitizer.MAX_TEMPLATE_CHARS);
        assertDoesNotThrow(() -> printer.previewDesign(SALE, exact, false));
        assertDoesNotThrow(() -> saver.insert(designBo(SALE, exact)));
        assertDoesNotThrow(() -> saver.update(TEMPLATE, designBo(SALE, exact)));
        assertDoesNotThrow(() -> printDocument(SALE));
    }

    @Test
    void oversizedDesignerRequestBodyStopsBeforeJacksonAndDatabase() {
        String oversized = "{\"blocks\":[]}" + " ".repeat(TemplateSanitizer.MAX_TEMPLATE_CHARS);
        assertThrows(ServiceException.class, () -> printer.previewDesign(SALE, oversized, false));
        verifyNoInteractions(objectMapper, compiler);
        verifyNoInteractions(databaseMocks());
    }

    @ParameterizedTest @ValueSource(strings = {"PREVIEW", "INSERT", "UPDATE"})
    void designCompilerOutputAlwaysPassesRealSanitizerBeforeUse(String entry) throws Exception {
        String unsafe = "<div th:text=\"${doc.class}\"></div>";
        doReturn(unsafe).when(compiler).compile(any(DesignSchema.class), eq(SALE));
        String validJson = fieldJson("doc.no");
        try (MockedStatic<ThymeleafRenderer> renderer = mockStatic(ThymeleafRenderer.class)) {
            if ("PREVIEW".equals(entry)) {
                assertThrows(ServiceException.class, () -> printer.previewDesign(SALE, validJson, false));
            } else if ("INSERT".equals(entry)) {
                assertThrows(IllegalArgumentException.class, () -> saver.insert(designBo(SALE, validJson)));
                verify(savingSanitizer).clean(unsafe, SALE);
            } else {
                assertThrows(IllegalArgumentException.class, () -> saver.update(TEMPLATE, designBo(SALE, validJson)));
                assertUpdateUntouched();
            }
            renderer.verifyNoInteractions();
        }
        verify(templateMapper, never()).insert(any(PrintTemplate.class));
        verify(templateMapper, never()).updateById(any(PrintTemplate.class));
    }

    @ParameterizedTest @ValueSource(strings = {"SOURCE", "DESIGN", "FORMAL"})
    void deniedProfitPrecedesParsingCleaningRenderingAndEveryDatabaseCall(String entry) {
        try (MockedStatic<StpUtil> permissions = mockStatic(StpUtil.class);
             MockedStatic<ThymeleafRenderer> renderer = mockStatic(ThymeleafRenderer.class);
             MockedConstruction<TemplateSanitizer> cleaners = mockConstruction(TemplateSanitizer.class)) {
            PrintServiceImpl guarded = newPrinter();
            permissions.when(() -> StpUtil.checkPermission(PROFIT_PERMISSION))
                .thenThrow(new NotPermissionException(PROFIT_PERMISSION));
            assertThrows(NotPermissionException.class, () -> {
                switch (entry) {
                    case "SOURCE" -> guarded.preview(null, "<b th:text=\"${doc.class}\"></b>", 123L, true);
                    case "DESIGN" -> guarded.previewDesign(SALE, "{broken json", true);
                    default -> guarded.renderSaleDelivery(DOCUMENT, TEMPLATE, true, 1);
                }
            });
            permissions.verify(() -> StpUtil.checkPermission(PROFIT_PERMISSION));
            assertEquals(1, cleaners.constructed().size());
            cleaners.constructed().forEach(cleaner -> verifyNoInteractions(cleaner));
            renderer.verifyNoInteractions();
            verifyNoInteractions(compiler, objectMapper);
            verifyNoInteractions(databaseMocks());
        }
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"UNKNOWN"})
    void missingOrUnsupportedPreviewSceneCannotUseUnionWhitelist(String scene) {
        try (MockedStatic<ThymeleafRenderer> renderer = mockStatic(ThymeleafRenderer.class)) {
            assertThrows(ServiceException.class,
                () -> printer.preview(scene, "<b th:text=\"${doc.no}\"></b>", null, false));
            assertThrows(ServiceException.class, () -> printer.previewDesign(scene, "{broken json", false));
            renderer.verifyNoInteractions();
        }
        verifyNoInteractions(objectMapper, compiler);
        verifyNoInteractions(databaseMocks());
    }

    @Test
    void imageLayoutSurvivesServiceCompilationAndCleaning() throws Exception {
        String design = new ObjectMapper().writeValueAsString(Map.of("blocks", List.of(Map.of(
            "type", "IMAGE", "props", Map.of("src", "data:image/png;base64,AAA", "width", 160,
                "ratio", 0.5, "layoutWidth", 30, "offsetX", 40), "style", Map.of("offsetPx", 120)))));
        String html = printer.previewDesign(SALE, design, false);
        assertTrue(html.contains("margin-left:120px"));
        assertTrue(html.contains("width:160px;height:80px"));
        assertFalse(html.contains("width:30%"));
        assertFalse(html.contains("margin-left:40%"));
    }

    /** Exercise the real shared guard in isolation: formal entry otherwise reads its document first. */
    private String renderTemplateBoundary(String scene) throws Throwable {
        var render = PrintServiceImpl.class.getDeclaredMethod("render", PrintContext.class, Long.class,
            String.class, Long.class);
        render.setAccessible(true);
        try {
            return (String) render.invoke(printer, new PrintContext(), TEMPLATE, scene, null);
        } catch (InvocationTargetException error) {
            throw error.getCause(); // Preserve the actual service exception; never swallow or widen it.
        }
    }

    private String printDocument(String scene) {
        return SALE.equals(scene) ? printer.renderSaleDelivery(DOCUMENT, TEMPLATE, false, 1)
            : printer.renderPurchaseIn(DOCUMENT, TEMPLATE);
    }

    private void assertUpdateUntouched() {
        verify(templateMapper).selectById(TEMPLATE);
        verifyNoMoreInteractions(templateMapper);
        assertEquals("<div>original</div>", stored.getContent());
        assertEquals("original", stored.getName());
        assertEquals(7, stored.getVersion());
    }

    private Object[] databaseMocks() {
        return new Object[]{templates, sales, receipts, receiptAllocs, payments, paymentAllocs, ledger,
            openings, customers, suppliers, products, saleItems, purchases, purchaseItems, finance, templateMapper};
    }

    private PrintTemplateBo sourceBo(String scene, String html) {
        PrintTemplateBo bo = new PrintTemplateBo();
        bo.setCode("ENTRY");
        bo.setName("entry");
        bo.setScene(scene);
        bo.setContent(html);
        bo.setIsDefault(0);
        return bo;
    }

    private PrintTemplateBo designBo(String scene, String json) {
        PrintTemplateBo bo = sourceBo(scene, null);
        bo.setSchemaJson(json);
        return bo;
    }

    private static String designAtCompiledLength(int length) throws Exception {
        String one = jsonBlock("TEXT", Map.of("text", "x"));
        DesignSchema schema = new ObjectMapper().readValue(one, DesignSchema.class);
        int overhead = new TemplateCompiler().compile(schema, SALE).length() - 1;
        return jsonBlock("TEXT", Map.of("text", "x".repeat(length - overhead)));
    }

    private static String fieldJson(String field) throws Exception {
        return jsonBlock("FIELD", Map.of("fieldKey", field));
    }

    private static String jsonBlock(String type, Map<String, ?> props) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("blocks", List.of(Map.of("type", type, "props", props))));
    }
}
