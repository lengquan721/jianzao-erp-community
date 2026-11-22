package org.erp.print.service.impl;

import org.erp.print.domain.design.BlockType;
import org.erp.print.domain.design.DesignBlock;
import org.erp.print.domain.design.DesignSchema;
import org.erp.print.domain.design.TableColumn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end guard tests for source sanitization and design-schema compilation. */
@Tag("dev")
class TemplateSecurityTest {

    private final TemplateSanitizer sanitizer = new TemplateSanitizer();
    private final TemplateCompiler compiler = new TemplateCompiler();

    @Test
    @DisplayName("安全的变量路径、日期/数值格式化、循环与条件表达式可净化并渲染")
    void acceptsSafeExpressionsThroughSanitizerAndRenderer() {
        String source = """
            <html><body>
              <div th:text="${statement.month} + ' 月度'"></div>
              <div th:text="${#dates.format(doc.date, 'yyyy-MM-dd')}"></div>
              <table><tr th:each="item, stat : ${statement.items}">
                <td th:text="${stat.index + 1}"></td>
                <td th:text="${item.price != null} ? ${#numbers.formatDecimal(item.price,1,2)} : '-'"></td>
              </tr></table>
            </body></html>
            """;

        String cleaned = sanitizer.clean(source, "STATEMENT");
        Map<String, Object> model = new HashMap<>();
        model.put("doc", Map.of("date", new Date(0)));
        model.put("statement", Map.of("month", "2026-11", "items",
            List.of(Map.of("price", new BigDecimal("12.5")))));
        String rendered = ThymeleafRenderer.render(cleaned, model);

        assertTrue(rendered.contains("2026-11 月度"));
        assertTrue(rendered.contains("1970-01-01"));
        assertTrue(rendered.contains("12.50"));
    }

    @Test
    @DisplayName("源码模式拒绝未知变量与未登记的嵌套字段")
    void rejectsUnknownVariablePaths() {
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div th:text=\"${intruder.secret}\">x</div>"));
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div th:text=\"${doc.no.class}\">x</div>"));
    }

    @Test
    @DisplayName("源码模式拒绝任意方法调用、类访问及其他表达式对象")
    void rejectsMethodAndClassAccess() {
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div th:text=\"${doc.no.toString()}\">x</div>"));
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div th:text=\"${T(java.lang.Runtime).getRuntime().exec('x')}\">x</div>"));
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div th:text=\"${#ctx.getVariable('doc')}\">x</div>"));
    }

    @Test
    @DisplayName("显式非销售场景拒绝利润字段，老入口保持既有模板兼容")
    void gatesProfitPathsByScene() {
        String source = "<div th:text=\"${profit.grossProfit}\">0</div>";
        assertThrows(IllegalArgumentException.class, () -> sanitizer.clean(source, "PAYMENT"));
        assertTrue(sanitizer.clean(source, "SALE_DELIVERY").contains("profit.grossProfit"));
        assertTrue(sanitizer.clean(source).contains("profit.grossProfit"));
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div th:text=\"${profit.class}\"></div>"));
    }

    @Test
    @DisplayName("设计器允许字典字段并经真实净化与渲染链路输出")
    void compilesAllowedFieldAndRendersAfterCleaning() {
        String compiled = compiler.compile(schema(field("doc.no")), "SALE_DELIVERY");
        String cleaned = sanitizer.clean(compiled, "SALE_DELIVERY");
        String rendered = ThymeleafRenderer.render(cleaned, Map.of("doc", Map.of("no", "XS-001")));
        assertTrue(rendered.contains("XS-001"));
    }

    @Test
    @DisplayName("设计器拒绝未知字段路径、方法样式路径及未授权利润根")
    void rejectsUnsafeDesignerFields() {
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(field("doc.no.toString")), "SALE_DELIVERY"));
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(field("runtime.exec")), "SALE_DELIVERY"));
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(field("profit.costAmount")), "PAYMENT"));
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(field("profit.class"))));
        assertTrue(compiler.compile(schema(field("profit.costAmount"))).contains("${profit.costAmount}"));
        assertTrue(compiler.compile(schema(field("profit.costAmount")), "SALE_DELIVERY")
            .contains("${profit.costAmount}"));
    }

    @Test
    @DisplayName("设计器表格只接受当前场景已登记的数据源和列")
    void rejectsUnknownTableSourcesAndColumns() {
        DesignBlock wrongSource = table("profit.items", "name");
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(wrongSource), "SALE_DELIVERY"));
        DesignBlock wrongColumn = table("items", "getClass");
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(wrongColumn), "SALE_DELIVERY"));
        assertTrue(compiler.compile(schema(table("items", "name")), "SALE_DELIVERY")
            .contains("${item.name}"));
    }

    @Test
    @DisplayName("源码净化器接受长度上限，拒绝超出一个字符")
    void sourceTemplateLengthBoundary() {
        // Jsoup retains a body envelope because body belongs to the existing tag safelist.
        String prefix = "<body><div>";
        String suffix = "</div></body>";
        String atLimit = prefix + "x".repeat(TemplateSanitizer.MAX_TEMPLATE_CHARS - prefix.length() - suffix.length())
            + suffix;
        assertEquals(TemplateSanitizer.MAX_TEMPLATE_CHARS, atLimit.length());
        assertEquals(TemplateSanitizer.MAX_TEMPLATE_CHARS, sanitizer.clean(atLimit).length());
        assertThrows(IllegalArgumentException.class, () -> sanitizer.clean(atLimit + "x"));
    }

    @Test
    @DisplayName("设计器编译器接受输出长度上限，拒绝超出一个字符")
    void designerTemplateLengthBoundary() {
        DesignSchema emptyText = schema(text(""));
        int emptyOutputLength = compiler.compile(emptyText).length();
        int oneCharacterOutputLength = compiler.compile(schema(text("x"))).length();
        int exactTextChars = 1 + TemplateSanitizer.MAX_TEMPLATE_CHARS - oneCharacterOutputLength;
        String exact = compiler.compile(schema(text("x".repeat(exactTextChars))));
        assertEquals(TemplateSanitizer.MAX_TEMPLATE_CHARS, exact.length());
        assertDoesNotThrow(() -> sanitizer.clean(exact));
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(text("x".repeat(exactTextChars + 1)))));
        assertTrue(emptyOutputLength < oneCharacterOutputLength);
    }

    @Test
    @DisplayName("源码图片 data URI 的最大值可用，超过一字符即拒绝")
    void sourceImageDataUriBoundary() {
        String prefix = "data:image/png;base64,";
        String exact = prefix + "A".repeat(350_000 - prefix.length());
        String over = exact + "A";
        assertDoesNotThrow(() -> sanitizer.clean("<img src=\"" + exact + "\">"));
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<img src=\"" + over + "\">"));
    }

    @Test
    @DisplayName("设计器图片只接受有限大小的位图 data URI")
    void designerImageUriPolicy() {
        String prefix = "data:image/png;base64,";
        String exact = prefix + "A".repeat(350_000 - prefix.length());
        assertDoesNotThrow(() -> compiler.compile(schema(image(exact))));
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(image(exact + "A"))));
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(image("data:text/html;base64,PHNjcmlwdD4="))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "${doc.no()}", "${doc.no.toString()}", "${doc.class}", "${printDate.class}",
        "${T(java.lang.Runtime).getRuntime()}", "${#ctx.getVariable('doc')}",
        "${#dates.createNow()}", "${items[0].name}", "${profit}", "${costAmount}",
        "${profit.getClass()}", "${#numbers.formatDecimal(profit.class,1,2)}"
    })
    @DisplayName("拒绝方法/类/索引/未授权利润根，不将表达式交给渲染器")
    void rejectsExecutableOrUnregisteredExpressions(String expression) {
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<b th:text=\"" + expression + "\">fallback</b>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "th:with=\"p=${profit}\"", "th:insert=\"~{external}\"", "th:replace=\"~{external}\"",
        "th:style=\"${doc.no}\"", "th:text=\"__${doc.no}__\""
    })
    @DisplayName("拒绝变量别名/动态模板包含/动态 CSS/表达式预处理")
    void rejectsUnsupportedDynamicDirectives(String attribute) {
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div " + attribute + ">fallback</div>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[[${doc.no}]]", "[(${doc.no})]", "&#91;&#91;${doc.no}&#93;&#93;"})
    @DisplayName("源码文本内联不能绕过属性表达式校验")
    void rejectsTextInlining(String text) {
        assertThrows(IllegalArgumentException.class, () -> sanitizer.clean("<div>" + text + "</div>"));
    }

    @Test
    @DisplayName("设计器静态文本与样式不能注入可执行模板属性")
    void designerRejectsInliningAndClearsStyleInjection() {
        assertThrows(IllegalArgumentException.class,
            () -> compiler.compile(schema(text("[[${doc.class}]]"))));
        DesignBlock field = field("doc.no");
        field.getStyle().put("textAlign", "left;\" th:text=\"${doc.class}");
        DesignSchema schema = schema(field);
        schema.setFontFamily("sans-serif;</style><b th:text=\"${doc.class}\"></b><style>");
        schema.setPaper("A4;</style><b th:text=\"${doc.class}\"></b><style>");
        String compiled = compiler.compile(schema);
        assertTrue(!compiled.contains("${doc.class}"));
        assertTrue(ThymeleafRenderer.render(compiled, Map.of("doc", Map.of("no", "XS-SAFE"))).contains("XS-SAFE"));
    }

    @Test
    @DisplayName("循环变量仅在本行子树有效，列按其数据源校验且不能遮蔽利润根")
    void iteratorScopeDoesNotLeakOrShadowRoots() {
        String loop = "<table><tr th:each=\"item : ${items}\"><td th:text=\"${item.name}\"></td></tr></table>";
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean(loop + "<b th:text=\"${item.name}\"></b>"));
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div th:each=\"profit : ${items}\" th:text=\"${profit.costAmount}\"></div>"));
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<div th:each=\"item : ${statement.items}\" th:text=\"${item.name}\"></div>"));
        assertTrue(sanitizer.clean(loop).contains("${item.name}"));
    }

    @Test
    @DisplayName("区块 100/101 与表格列 20/21 的边界由真实编译器执行")
    void blockAndColumnCountBoundaries() {
        DesignSchema schema = new DesignSchema();
        schema.setBlocks(new ArrayList<>(java.util.Collections.nCopies(100, text("x"))));
        assertDoesNotThrow(() -> compiler.compile(schema));
        schema.getBlocks().add(text("x"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(schema));
        DesignBlock table = table("items", "name");
        TableColumn column = new TableColumn();
        column.setKey("name");
        List<TableColumn> columns = new ArrayList<>(java.util.Collections.nCopies(20, column));
        table.getProps().put("columns", columns);
        assertDoesNotThrow(() -> compiler.compile(schema(table)));
        columns.add(column);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(schema(table)));
    }

    @Test
    @DisplayName("设计器转义膨胀和源码实体膨胀也受最终产物长度限制")
    void rejectsEscapingExpansionBeyondOutputLimit() {
        String escapedText = "&".repeat(TemplateSanitizer.MAX_TEMPLATE_CHARS / 2);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(schema(text(escapedText))));
        assertThrows(IllegalArgumentException.class, () -> sanitizer.clean("<div>" + escapedText + "</div>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN", "RECEIPT", "PAYMENT"})
    @DisplayName("所有打印场景的固定模板和设计预置均能净化并实际渲染")
    void existingFixedTemplatesAndPresetsRemainRenderable(String scene) {
        String fixed = switch (scene) {
            case "SALE_DELIVERY" -> BuiltinTemplates.saleDelivery().getContent();
            case "STATEMENT" -> BuiltinTemplates.statement().getContent();
            case "STATEMENT_RANGE" -> BuiltinTemplates.statementRange().getContent();
            case "PURCHASE_IN" -> BuiltinTemplates.purchaseIn().getContent();
            default -> BuiltinTemplates.receiptPayment().getContent();
        };
        Map<String, Object> model = fixedModel(scene);
        String fixedRendered = ThymeleafRenderer.render(sanitizer.clean(fixed), model);
        assertTrue(fixedRendered.contains("测试客户"));
        assertDoesNotThrow(() -> ThymeleafRenderer.render(sanitizer.clean(fixed, scene), model));
        String designed = compiler.compile(DesignPresets.of(scene), scene);
        assertTrue(ThymeleafRenderer.render(sanitizer.clean(designed, scene), model).contains("测试客户"));
    }

    @Test
    @DisplayName("B04 欠款路径与 B07 利润空值保护兼容，模板不能发明额外成本根")
    void financeAndProfitTemplatePathsRemainCompatible() {
        String source = """
            <div th:text="${totals.unsettledAmount}"></div>
            <div th:text="${totals.debtTotal}"></div>
            <div th:text="${profit == null ? 'ABSENT' : profit.costAmount}"></div>
            """;
        Map<String, Object> model = fixedModel("SALE_DELIVERY");
        String clean = sanitizer.clean(source);
        assertTrue(ThymeleafRenderer.render(clean, model).contains("ABSENT"));
        model.put("profit", Map.of("costAmount", new BigDecimal("158.80")));
        assertTrue(ThymeleafRenderer.render(clean, model).contains("158.80"));
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<b th:text=\"${sale.costAmount}\"></b>"));
    }

    private Map<String, Object> fixedModel(String scene) {
        Map<String, Object> model = new HashMap<>();
        model.put("tenant", Map.of("name", "测试公司"));
        model.put("partner", Map.of("name", "测试客户"));
        model.put("doc", Map.of("no", "XS-SAFE", "date", new Date(0), "type", scene, "account", "现金"));
        model.put("printDate", "2026-11-20");
        model.put("items", List.of(Map.of("index", 1, "name", "产品", "spec", "规格", "unit", "支",
            "qty", 2, "price", new BigDecimal("50.00"), "amount", new BigDecimal("100.00"),
            "no", "XS-SAFE", "date", new Date(0))));
        model.put("totals", Map.of("amountTotal", new BigDecimal("100.00"), "amountUpper", "壹佰元整",
            "unsettledAmount", new BigDecimal("20.00"), "debtTotal", new BigDecimal("80.00")));
        model.put("signatures", Map.of("maker", "制单员", "warehouse", "仓管员"));
        Map<String, Object> statement = new HashMap<>();
        statement.put("month", "2026-11");
        statement.put("periodStart", "2026-11-01");
        statement.put("periodEnd", "2026-11-30");
        statement.put("periodLabel", "2026-11 月度");
        statement.put("opening", 1);
        statement.put("openingEntryAmount", 0);
        statement.put("salesAmount", 2);
        statement.put("returnAmount", 0);
        statement.put("netSalesAmount", 2);
        statement.put("receiptAmount", 3);
        statement.put("refundAmount", 0);
        statement.put("netReceiptAmount", 3);
        statement.put("monthlyUnpaid", -1);
        statement.put("cumulativeSales", 2);
        statement.put("cumulativeReceipts", 3);
        statement.put("cumulativeUnpaid", 0);
        statement.put("totalReceivable", 0);
        statement.put("closing", 0);
        statement.put("items", List.of(Map.of("date", new Date(0), "no", "XS-SAFE",
            "content", "销售", "productName", "产品", "qtyText", "2", "price", new BigDecimal("50.00"),
            "amount", 100, "settled", 80, "unsettled", 20)));
        model.put("statement", statement);
        model.put("showProfit", false);
        model.put("profit", null);
        model.put("copies", 1);
        return model;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "${copies > 0 and not showProfit}", "${doc.no != null ? doc.no : '-'}",
        "${doc.no} ?: '-'", "${showProfit} ? '利润' : '普通'", "${printDate}"
    })
    @DisplayName("允许既有逻辑/空值回落/三元和标量表达式并实际渲染")
    void safeLogicalAndFallbackExpressionsRender(String expression) {
        String clean = sanitizer.clean("<div th:text=\"" + expression + "\"></div>");
        assertDoesNotThrow(() -> ThymeleafRenderer.render(clean, fixedModel("SALE_DELIVERY")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"data:text/html;base64,PHNjcmlwdD4=", "data:image/svg+xml;base64,PHN2Zz4="})
    @DisplayName("源码与设计器一致拒绝可执行或非位图 data URI")
    void sourceImageRejectsNonRasterData(String source) {
        assertThrows(IllegalArgumentException.class, () -> sanitizer.clean("<img src=\"" + source + "\">"));
    }

    @Test
    @DisplayName("style 数据节点中的模板内联同样被拒绝")
    void rejectsInlineExpressionInsideStyleData() {
        assertThrows(IllegalArgumentException.class,
            () -> sanitizer.clean("<style>[(${doc.class})]</style><div>safe</div>"));
    }

    @Test
    @DisplayName("超限源码即使可被净化缩小也必须先拒绝")
    void checksSourceSizeBeforeCleaningAwayDangerousMarkup() {
        String oversized = "<div>safe</div><script>" + "x".repeat(TemplateSanitizer.MAX_TEMPLATE_CHARS) + "</script>";
        assertThrows(IllegalArgumentException.class, () -> sanitizer.clean(oversized));
    }

    private DesignSchema schema(DesignBlock... blocks) {
        DesignSchema schema = new DesignSchema();
        schema.setBlocks(new ArrayList<>(List.of(blocks)));
        return schema;
    }

    private DesignBlock field(String key) {
        return block(BlockType.FIELD, Map.of("fieldKey", key, "label", "字段"));
    }

    private DesignBlock text(String value) {
        return block(BlockType.TEXT, Map.of("text", value));
    }

    private DesignBlock image(String src) {
        return block(BlockType.IMAGE, Map.of("src", src, "width", 100));
    }

    private DesignBlock table(String source, String columnKey) {
        TableColumn column = new TableColumn();
        column.setKey(columnKey);
        column.setLabel("列");
        column.setVisible(true);
        return block(BlockType.TABLE, Map.of("dataSource", source, "columns", List.of(column)));
    }

    private DesignBlock block(BlockType type, Map<String, Object> props) {
        DesignBlock block = new DesignBlock();
        block.setType(type);
        block.setProps(new HashMap<>(props));
        block.setStyle(new HashMap<>());
        return block;
    }
}
