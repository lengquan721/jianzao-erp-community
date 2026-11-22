package org.erp.print.service.impl;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纸张设置：paper 值 → @page CSS 规则，并注入渲染结果的 head 末尾（DOC/14 补充）
 *
 * 背景：打印链路是「后端渲染 HTML → iframe window.print()」，纸张只能在 CSS @page 声明。
 * 针式打印机驱动纸张（如 241×140 连续纸）与模板声明的 A4 不一致时，
 * 浏览器会按驱动纸宽对整页做等比缩小（shrink-to-fit），导致针打图案特别小。
 * 让 @page 与实际装纸一致（配合打印对话框选对纸张、缩放 100%），版面即按 1:1 输出。
 *
 * paper 取值（打印模板 paper 字段）：
 *  A4 / A5：标准纸张，页边距沿用内置模板原值；
 *  宽x高（mm，如 241x140、241x93、380x280）：针式/自定义纸，241 宽三联单按走纸用小边距；
 *  AUTO 或未知值：不注入，跟随模板自身 @page（兼容旧数据与手工模板）。
 */
public final class PageSetup {

    /** 针式三联单 241 宽连续纸：241×140 与 241×93 同为链轮走纸，两侧留出走纸位，边距取小值 */
    private static final Set<String> PIN_FEED_PAPERS = Set.of("241x140", "241x93");

    private static final Pattern CUSTOM_SIZE = Pattern.compile("^(\\d+(?:\\.\\d+)?)x(\\d+(?:\\.\\d+)?)$");
    /**
     * 模板是否自带 @page at-rule：只允许 @page 后紧跟「可选伪类 + 可选页名 + {」。
     * 不含任意文本，避免把注释里的「@page 由 PageSetup 注入」误判为规则（内置模板正是这种写法，
     * 误判会让内置模板的纸张注入整体失效）。
     */
    private static final Pattern PAGE_RULE = Pattern.compile("@page\\s*(?::[a-z-]+\\s*)?(?:[a-z_-][\\w-]*\\s*)?\\{", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_END = Pattern.compile("</head>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_START = Pattern.compile("<body[^>]*>", Pattern.CASE_INSENSITIVE);

    private PageSetup() {
    }

    /**
     * paper 值 → @page 规则；null 表示不注入。
     *
     * 模板 HTML 已自带 @page 规则时返回 null（尊重模板作者的纸张与边距设定，
     * 避免注入规则靠级联覆盖掉精调过的 margin，如 947640 三联单模板的 5mm 10mm）。
     */
    public static String pageRule(String paper, String templateHtml) {
        if (templateHtml != null && PAGE_RULE.matcher(templateHtml).find()) {
            return null;
        }
        if (paper == null || paper.isBlank()) {
            return null;
        }
        String p = paper.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "a4" -> "@page { size: A4; margin: 12mm; }";
            case "a5" -> "@page { size: A5; margin: 10mm; }";
            case "auto" -> null;
            default -> customRule(p);
        };
    }

    /**
     * pageRule 包一层 style 标签；pageRule 为 null 时返回 null。
     */
    public static String pageStyle(String paper, String templateHtml) {
        String rule = pageRule(paper, templateHtml);
        return rule == null ? null : "<style>" + rule + "</style>";
    }

    private static String customRule(String paper) {
        Matcher m = CUSTOM_SIZE.matcher(paper);
        if (!m.matches() || !validDimension(m.group(1)) || !validDimension(m.group(2))) {
            return null;
        }
        String margin = PIN_FEED_PAPERS.contains(paper) ? "margin: 5mm 8mm;" : "margin: 8mm;";
        return "@page { size: " + m.group(1) + "mm " + m.group(2) + "mm; " + margin + " }";
    }

    /** Design-schema @page size formatter: only standard paper or bounded numeric millimeter pairs. */
    public static String safePageSize(String paper) {
        if (paper == null || paper.isBlank()) {
            return "A4";
        }
        String normalized = paper.trim().toLowerCase(Locale.ROOT);
        if ("a4".equals(normalized)) {
            return "A4";
        }
        if ("a5".equals(normalized)) {
            return "A5";
        }
        Matcher m = CUSTOM_SIZE.matcher(normalized);
        if (!m.matches() || !validDimension(m.group(1)) || !validDimension(m.group(2))) {
            return "A4";
        }
        return m.group(1) + "mm " + m.group(2) + "mm";
    }

    private static boolean validDimension(String value) {
        try {
            double dimension = Double.parseDouble(value);
            return Double.isFinite(dimension) && dimension >= 40 && dimension <= 2000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 把 style 注入 head 末尾（在 &lt;/head&gt; 前）；无 head 时插到 body 开标签后；
     * 均缺失（纯片段）时前置。style 为 null 时原样返回。
     */
    public static String injectIntoHead(String html, String styleTag) {
        if (styleTag == null || html == null) {
            return html;
        }
        Matcher headEnd = HEAD_END.matcher(html);
        if (headEnd.find()) {
            int idx = headEnd.start();
            return html.substring(0, idx) + styleTag + html.substring(idx);
        }
        Matcher bodyStart = BODY_START.matcher(html);
        if (bodyStart.find()) {
            return html.substring(0, bodyStart.end()) + styleTag + html.substring(bodyStart.end());
        }
        return styleTag + html;
    }
}
