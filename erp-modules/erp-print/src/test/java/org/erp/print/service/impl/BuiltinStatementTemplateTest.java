package org.erp.print.service.impl;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置对账单模板渲染测试：保留单价及数量精度，并按实际事件输出摘要与有符号发生额。
 */
@Tag("dev")
class BuiltinStatementTemplateTest {

    @Test
    @DisplayName("内置对账单渲染单价列")
    void rendersUnitPriceColumn() {
        PrintContext.StatementItem sale = new PrintContext.StatementItem();
        sale.setDate(new Date());
        sale.setNo("XS202611050001");
        sale.setContent("销售");
        sale.setProductName("汤勺铝芯");
        sale.setQty(new BigDecimal("10"));
        sale.setQtyText("10");
        sale.setPrice(new BigDecimal("28"));
        sale.setAmount(new BigDecimal("280.00"));
        sale.setSettled(BigDecimal.ZERO);
        sale.setUnsettled(new BigDecimal("280.00"));

        // 收款行无单价，模板应输出 '-'
        PrintContext.StatementItem receipt = new PrintContext.StatementItem();
        receipt.setDate(new Date());
        receipt.setNo("SK202611050001");
        receipt.setContent("收款");
        receipt.setAmount(new BigDecimal("-100.00"));
        receipt.setSettled(BigDecimal.ZERO);
        receipt.setUnsettled(new BigDecimal("100.00"));

        PrintContext.Statement statement = new PrintContext.Statement();
        statement.setMonth("2026-11");
        statement.setOpening(BigDecimal.ZERO);
        statement.setPreviousReceivable(BigDecimal.ZERO);
        statement.setOpeningEntryAmount(new BigDecimal("0.00"));
        statement.setTotalReceivable(new BigDecimal("250.00"));
        statement.setSalesAmount(new BigDecimal("280.00"));
        statement.setReturnAmount(new BigDecimal("0.00"));
        statement.setReceiptAmount(new BigDecimal("100.00"));
        statement.setRefundAmount(new BigDecimal("0.00"));
        statement.setMonthlyUnpaid(new BigDecimal("180.00"));
        statement.setCumulativeSales(new BigDecimal("280.00"));
        statement.setCumulativeReceipts(new BigDecimal("100.00"));
        statement.setClosing(new BigDecimal("180.00"));
        statement.setCumulativeUnpaid(new BigDecimal("180.00"));
        statement.setItems(List.of(sale, receipt));

        PrintContext.Tenant tenant = new PrintContext.Tenant();
        tenant.setName("成都鑫盛合科技");

        Map<String, Object> model = Map.of(
            "statement", statement,
            "partner", Map.of("name", "成都冒火"),
            "tenant", tenant,
            "printDate", "2026-11-07");

        String html = ThymeleafRenderer.render(BuiltinTemplates.statement().getContent(), model);

        var document = Jsoup.parse(html);
        assertEquals("2026-11 月度", document.selectFirst(".sub").text());
        // 月度内置模板按发生额展示期初、本期各类业务与期末，不依赖旧摘要文案或 CSS 类名。
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("期初应收：", "0");
        expected.put("本期建账期初：", "0.00");
        expected.put("本期销售：", "280.00");
        expected.put("本期退货：", "0.00");
        expected.put("本期收款：", "100.00");
        expected.put("本期退款：", "0.00");
        expected.put("本期净发生额：", "180.00");
        expected.put("期末应收：", "180.00");
        expected.put("累计销售净额：", "280.00");
        expected.put("累计净收款：", "100.00");
        expected.put("累计未付金额：", "180.00");
        expected.forEach((label, value) -> {
            var fields = document.select("body > div > div").stream()
                .filter(field -> field.ownText().startsWith(label)).toList();
            assertEquals(1, fields.size(), label + "应有且仅有一个汇总值");
            assertEquals(value, fields.get(0).selectFirst("span, b").text(), label);
        });
        assertEquals(List.of("制单：成都鑫盛合科技", "客户确认：______________"),
            document.select(".sign > span").eachText());
        assertEquals(List.of("日期", "单号", "摘要", "商品名称", "数量", "单价", "发生额"),
            document.select("thead th").eachText());
        var rows = document.select("tbody tr");
        assertEquals(List.of("销售", "收款"), rows.stream().map(row -> row.select("td").get(2).text()).toList(),
            "摘要必须取自实际事件，不能将收款也写成销售");
        assertEquals(List.of("280.00", "-100.00"), rows.stream().map(row -> row.select("td").get(6).text()).toList(),
            "销售增加应收、收款减少应收，明细和与本期净发生一致");
        BigDecimal signedMovements = rows.stream().map(row -> new BigDecimal(row.select("td").get(6).text()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, statement.getClosing().subtract(statement.getOpening()).compareTo(signedMovements),
            "期末应收必须等于期初加有符号发生额，不能误用兼容字段 totalReceivable");
        assertEquals(List.of("28.00", "-"), rows.stream().map(row -> row.select("td").get(5).text()).toList(),
            "销售单价格式化为两位小数，无单价的收款行显示 -");
        assertEquals(List.of("10", "-"), rows.stream().map(row -> row.select("td").get(4).text()).toList(),
            "整数商品数量不带小数位，无数量的收款行显示 -");
        assertFalse(html.contains("10.000"), "整数商品不应再显示三位小数");
    }

    @Test
    @DisplayName("区间模板输出对账期间与本期口径")
    void rendersRangePeriodLabels() {
        PrintContext.Statement statement = new PrintContext.Statement();
        statement.setPeriodStart("2026-09-01");
        statement.setPeriodEnd("2026-10-31");
        statement.setPeriodLabel("2026-09-01 至 2026-10-31");
        statement.setOpening(new BigDecimal("110.00"));
        statement.setPreviousReceivable(new BigDecimal("110.00"));
        statement.setSalesAmount(new BigDecimal("500.00"));
        statement.setReturnAmount(new BigDecimal("20.00"));
        statement.setNetSalesAmount(new BigDecimal("480.00"));
        statement.setReceiptAmount(new BigDecimal("40.00"));
        statement.setRefundAmount(BigDecimal.ZERO);
        statement.setNetReceiptAmount(new BigDecimal("40.00"));
        statement.setMonthlyUnpaid(new BigDecimal("440.00"));
        statement.setCumulativeSales(new BigDecimal("570.00"));
        statement.setCumulativeReceipts(new BigDecimal("70.00"));
        statement.setCumulativeUnpaid(new BigDecimal("550.00"));
        statement.setClosing(new BigDecimal("550.00"));
        statement.setTotalReceivable(new BigDecimal("550.00"));
        statement.setItems(List.of());

        PrintContext.Tenant tenant = new PrintContext.Tenant();
        tenant.setName("成都鑫盛合科技");

        Map<String, Object> model = Map.of(
            "statement", statement,
            "partner", Map.of("name", "成都冒火"),
            "tenant", tenant,
            "totals", Map.of("debtTotal", new BigDecimal("999.00")),
            "printDate", "2026-11-18");

        String html = ThymeleafRenderer.render(BuiltinTemplates.statementRange().getContent(), model);

        assertTrue(html.contains("对账期间：<span>2026-09-01 至 2026-10-31</span>"), "应输出对账期间");
        assertTrue(html.contains("本期销售金额：<span>480.00</span>"));
        assertTrue(html.contains("本期收款金额：<span>40.00</span>"));
        assertTrue(html.contains("本期未付金额：<span>440.00</span>"));
        assertTrue(html.contains("期末应收：<b>550.00</b> 元"));
        assertFalse(html.contains("当月销售金额"), "区间模板不应出现月度口径文案");
    }
}
