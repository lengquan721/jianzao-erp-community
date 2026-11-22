package org.erp.print.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纸张设置测试：paper → @page 规则映射、head 注入位置、针式纸防缩小
 */
@Tag("dev")
class PageSetupTest {

    @Test
    @DisplayName("A4/A5 输出标准 @page，边距沿用原内置模板值")
    void standardPapers() {
        assertEquals("@page { size: A4; margin: 12mm; }", PageSetup.pageRule("A4", null));
        assertEquals("@page { size: A5; margin: 10mm; }", PageSetup.pageRule("a5", null));
    }

    @Test
    @DisplayName("三联单 241x140 / 241x93 输出针式纸规则，走纸边距取小值")
    void pinFeedPaper() {
        String rule = PageSetup.pageRule("241x140", null);
        assertEquals("@page { size: 241mm 140mm; margin: 5mm 8mm; }", rule);
        // style 包裹后可直接注入 head
        assertEquals("<style>" + rule + "</style>", PageSetup.pageStyle("241x140", null));
        // 241×93 是更常见的三联单规格，与 241×140 共用小边距，避免默认 8mm 挤占可打印区
        assertEquals("@page { size: 241mm 93mm; margin: 5mm 8mm; }", PageSetup.pageRule("241x93", null));
    }

    @Test
    @DisplayName("其他自定义纸按 宽x高 mm 输出，设计器纸张值转成有效CSS且范围受限")
    void customPaper() {
        assertEquals("@page { size: 380mm 280mm; margin: 8mm; }", PageSetup.pageRule("380x280", null));
        assertEquals("241mm 93mm", PageSetup.safePageSize("241x93"));
        assertEquals("A4", PageSetup.safePageSize("<style>bad</style>"));
        assertNull(PageSetup.pageRule("999999x1", null));
    }

    @Test
    @DisplayName("AUTO/空白/未知格式不注入，跟随模板自身 @page")
    void autoAndUnknownReturnNull() {
        assertNull(PageSetup.pageRule("AUTO", null));
        assertNull(PageSetup.pageRule(null, null));
        assertNull(PageSetup.pageRule("  ", null));
        assertNull(PageSetup.pageRule("XYZ", null));
        assertNull(PageSetup.pageStyle("XYZ", null));
    }

    @Test
    @DisplayName("模板自带 @page 时不注入：尊重模板精调的纸张与边距（如 947640 三联单 5mm 10mm）")
    void templateWithOwnPageRuleNotInjected() {
        String own = "@page { size: 241mm 140mm; margin: 5mm 10mm; }";
        assertNull(PageSetup.pageRule("241x140", "<style>" + own + "</style>"));
        assertNull(PageSetup.pageStyle("241x140", "<html><head><style>" + own + "</style></head></html>"));
        // paper 与自带 @page 纸张不一致时同样尊重模板（A4 纸字段、241 版面是模板作者的明确选择）
        assertNull(PageSetup.pageRule("A4", "<style>@page{size:241mm 140mm}</style>"));
    }

    @Test
    @DisplayName("@page 支持 :first 伪类与具名页，仍识别为自带规则")
    void pageRuleWithPseudoAndName() {
        assertNull(PageSetup.pageRule("A4", "<style>@page :first { margin: 0; }</style>"));
        assertNull(PageSetup.pageRule("A4", "<style>@page invoice { margin: 5mm; }</style>"));
    }

    @Test
    @DisplayName("注释里的「@page」字样不算自带规则：内置模板仍按 paper 注入")
    void commentMentioningPageNotTreatedAsRule() {
        String builtin = BuiltinTemplates.saleDelivery().getContent();
        assertTrue(builtin.contains("@page"));
        // 内置模板注释「@page 由 PageSetup 按模板 paper 注入」曾被旧正则误判为自带规则，导致注入整体失效
        assertEquals("@page { size: A4; margin: 12mm; }", PageSetup.pageRule("A4", builtin));
    }

    @Test
    @DisplayName("注入位置：head 末尾，靠级联覆盖模板自身 @page")
    void injectBeforeHeadClose() {
        String html = "<html><head><style>@page { size: A4; }</style></head><body><p>x</p></body></html>";
        String out = PageSetup.injectIntoHead(html, "<style>@page { size: 241mm 140mm; }</style>");
        assertTrue(out.contains("<style>@page { size: 241mm 140mm; }</style></head>"));
        assertEquals(1, count(out, "@page { size: 241mm 140mm; }"));
    }

    @Test
    @DisplayName("无 head 时插到 body 开标签后；纯片段前置")
    void injectFallbacks() {
        String bodyOnly = "<body><p>x</p></body>";
        assertTrue(PageSetup.injectIntoHead(bodyOnly, "<style>p{}</style>")
            .startsWith("<body><style>p{}</style>"));
        assertTrue(PageSetup.injectIntoHead("<p>x</p>", "<style>p{}</style>")
            .startsWith("<style>p{}</style><p>"));
    }

    @Test
    @DisplayName("style 为 null 或 html 为 null 时原样返回")
    void nullSafety() {
        assertEquals("<p>x</p>", PageSetup.injectIntoHead("<p>x</p>", null));
        assertNull(PageSetup.injectIntoHead(null, "<style>p{}</style>"));
    }

    @Test
    @DisplayName("联单输出：注入的 @page 在 head 只保留一份，每联共享同一纸张规则")
    void copiesKeepSinglePageRule() {
        String html = "<html><head><style>table{width:100%}</style></head><body><p>单</p></body></html>";
        String withPage = PageSetup.injectIntoHead(html, PageSetup.pageStyle("241x140", null));
        String out = ThymeleafRenderer.renderWithCopies(withPage, 3);
        assertEquals(3, count(out, "class=\"print-copy\""));
        assertEquals(1, count(out, "@page { size: 241mm 140mm; margin: 5mm 8mm; }"));
        assertFalse(out.contains("size: A4"));
    }

    private static int count(String hay, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = hay.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
