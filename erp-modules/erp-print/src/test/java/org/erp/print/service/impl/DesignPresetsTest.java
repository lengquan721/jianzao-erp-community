package org.erp.print.service.impl;

import org.erp.print.domain.design.DesignBlock;
import org.erp.print.domain.design.DesignSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预置版式测试：所有打印场景的起点版式都必须可用
 */
@Tag("dev")
class DesignPresetsTest {

    private final TemplateCompiler compiler = new TemplateCompiler();
    private final TemplateSanitizer sanitizer = new TemplateSanitizer();

    @ParameterizedTest
    @ValueSource(strings = {"SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN", "RECEIPT", "PAYMENT"})
    @DisplayName("每个场景的预置版式都能编译并应用设计器编辑")
    void presetCompilesForEveryScene(String scene) {
        DesignSchema schema = DesignPresets.of(scene);
        assertNotNull(schema);
        assertFalse(schema.getBlocks().isEmpty(), scene + " 预置版式不应为空");

        String html = assertDoesNotThrow(() -> compiler.compile(schema));
        assertTrue(html.contains("@page"));
        assertTrue(html.contains("th:text"), scene + " 预置版式应绑定变量");
        // 经净化器后仍保留 Thymeleaf 指令，说明不会把模板洗成死 HTML
        String cleaned = sanitizer.clean(html);
        assertTrue(cleaned.contains("th:text"), scene + " 净化后应保留变量绑定");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN", "RECEIPT", "PAYMENT"})
    @DisplayName("预置版式的字段都取自变量字典，不存在选不到的字段")
    void presetFieldsExistInDictionary(String scene) {
        DesignSchema schema = DesignPresets.of(scene);
        for (DesignBlock block : schema.getBlocks()) {
            if (block.getType() == org.erp.print.domain.design.BlockType.FIELD) {
                String key = String.valueOf(block.getProps().get("fieldKey"));
                boolean known = PrintVariables.of(scene).stream().anyMatch(v -> v.getKey().equals(key));
                assertTrue(known, scene + " 的预置字段 " + key + " 应存在于变量字典");
            }
        }
    }

    @Test
    @DisplayName("表格预置列与列字典一致")
    void presetTableColumnsMatchDictionary() {
        DesignSchema schema = DesignPresets.saleDelivery();
        DesignBlock table = schema.getBlocks().stream()
            .filter(b -> b.getType() == org.erp.print.domain.design.BlockType.TABLE)
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var columns = (java.util.List<org.erp.print.domain.design.TableColumn>) table.getProps().get("columns");
        var dictKeys = PrintVariables.columns("items").stream().map(v -> v.getKey()).toList();
        for (var col : columns) {
            assertTrue(dictKeys.contains(col.getKey()), "预置列 " + col.getKey() + " 应存在于列字典");
        }
    }

    @Test
    @DisplayName("未知场景回落到送货单版式，不抛异常")
    void unknownSceneFallsBack() {
        assertNotNull(DesignPresets.of("UNKNOWN"));
        assertNotNull(DesignPresets.of(null));
    }

    @Test
    @DisplayName("对账单预置版式使用对账明细数据源")
    void statementPresetUsesStatementSource() {
        DesignSchema schema = DesignPresets.statement();
        boolean hasStatementTable = schema.getBlocks().stream()
            .anyMatch(b -> b.getType() == org.erp.print.domain.design.BlockType.TABLE
                && "statement.items".equals(b.getProps().get("dataSource")));
        assertTrue(hasStatementTable);
    }
}
