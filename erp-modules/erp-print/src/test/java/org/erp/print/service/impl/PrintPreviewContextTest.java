package org.erp.print.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模板预览使用示例数据，并遵守各场景的变量白名单。 */
@Tag("dev")
class PrintPreviewContextTest {

    @Test
    @DisplayName("对账单预览渲染示例期初余额、明细单号和单价")
    void previewStatementTemplateWithSampleContext() {
        // preview 只用示例上下文渲染，不访问数据库；mapper 依赖传 null 即可
        PrintServiceImpl service = new PrintServiceImpl(null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, new TemplateCompiler(), new ObjectMapper());
        String tpl = """
            <div th:text="${statement.opening}"></div>
            <table><tbody><tr th:each="item : ${statement.items}">
              <td th:text="${item.no}"></td>
              <td th:text="${item.price}"></td>
            </tr></tbody></table>
            """;
        String html = service.preview("STATEMENT", tpl, null, false);
        var doc = Jsoup.parse(html);
        assertEquals("2000.00", doc.select("body > div").text(), "应渲染示例期初余额");
        var rows = doc.select("table > tbody > tr");
        assertEquals(1, rows.size(), "应渲染一行示例对账明细");
        assertEquals(List.of("XS202610050001", "50.00"), rows.get(0).select("td").eachText(),
            "应渲染示例明细的单号与单价");
    }

    @Test
    @DisplayName("预览能渲染小计、运杂费、本单未收与累计欠款")
    void previewTotalsExtras() {
        PrintServiceImpl service = new PrintServiceImpl(null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, new TemplateCompiler(), new ObjectMapper());
        String tpl = """
            <div th:text="${totals.goodsAmount}">0.00</div>
            <div th:text="${totals.freightAmount}">0.00</div>
            <div th:text="${totals.unsettledAmount}">0.00</div>
            <div th:text="${totals.debtTotal}">0.00</div>
            """;
        String html = service.preview("SALE_DELIVERY", tpl, null, false);
        assertTrue(html.contains("2200.00"), "应渲染示例累计欠款");
        assertTrue(html.contains("500.00"), "应渲染示例小计与本单未收");
    }

    @Test
    @DisplayName("源码预览注入受限纸张规则，自带安全@page时不重复覆盖")
    void sourcePreviewAppliesPaperOnlyWhenTemplateHasNoPageRule() {
        PrintServiceImpl service = new PrintServiceImpl(null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, new TemplateCompiler(), new ObjectMapper());
        String source = "<html><head><style>body { color: black; }</style></head><body><p>预览</p></body></html>";
        String html = service.preview("SALE_DELIVERY", source, null, false, "241x93");
        assertTrue(html.contains("@page { size: 241mm 93mm; margin: 5mm 8mm; }"));
        String ownRule = "<html><head><style>@page { size: 241mm 140mm; margin: 5mm 10mm; }</style></head>"
            + "<body><p>预览</p></body></html>";
        String preserved = service.preview("SALE_DELIVERY", ownRule, null, false, "A4");
        assertTrue(preserved.contains("size: 241mm 140mm; margin: 5mm 10mm;"));
        assertFalse(preserved.contains("size: A4"));
        String design = service.previewDesign("SALE_DELIVERY",
            "{\"paper\":\"241x93\",\"blocks\":[{\"type\":\"TITLE\",\"props\":{\"text\":\"三联单\"}}]}", false);
        assertEquals(1, occurrences(design, "@page { size: 241mm 93mm; margin: 12mm; }"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("欠款与小计字段在设计器里可选，且编译成 th:text")
    void debtFieldsSelectableInDesigner() {
        java.util.List<String> keys = PrintVariables.of("SALE_DELIVERY").stream()
            .map(org.erp.print.domain.vo.PrintVariableVo::getKey).toList();
        assertTrue(keys.contains("totals.debtTotal"), "送货单应可选累计欠款");
        assertTrue(keys.contains("totals.unsettledAmount"), "送货单应可选本单未收");
        assertTrue(keys.contains("totals.goodsAmount"), "应可选货品小计");
        assertTrue(keys.contains("totals.freightAmount"), "应可选运杂费");
        // 采购场景用「未付」措辞，但 key 相同
        assertTrue(PrintVariables.of("PURCHASE_IN").stream()
            .map(org.erp.print.domain.vo.PrintVariableVo::getKey).toList()
            .contains("totals.debtTotal"), "采购单应可选累计欠款");
    }
}
