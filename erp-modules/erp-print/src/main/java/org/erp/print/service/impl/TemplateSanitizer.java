package org.erp.print.service.impl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 模板白名单清洗：只保留打印展示所需 HTML 与受限 Thymeleaf 表达式。 */
@Component
public class TemplateSanitizer {

    /** 源码与设计器产物共用上限，按 Java 字符数计；上限本身可用。 */
    public static final int MAX_TEMPLATE_CHARS = 500_000;
    static final int MAX_DATA_URI_CHARS = 350_000;
    private static final int MAX_EXPRESSION_CHARS = 2_048;
    private static final String[] FORBIDDEN_DIRECTIVES = {
        "th:utext", "th:attr", "th:href", "th:src", "th:with",
        "th:insert", "th:replace", "th:block", "th:style"
    };
    private static final Pattern REAL_TAG = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)[\\s/>]");
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^{}]*)}");
    private static final String VARIABLE_PATH = "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*";
    private static final Pattern EACH_EXPRESSION = Pattern.compile(
        "^\\s*([A-Za-z][A-Za-z0-9_]*)(?:\\s*,\\s*([A-Za-z][A-Za-z0-9_]*))?"
            + "\\s*:\\s*\\$\\{\\s*(" + VARIABLE_PATH + ")\\s*}\\s*$");
    private static final Pattern DATE_FORMAT = Pattern.compile(
        "#dates\\.format\\(\\s*(" + VARIABLE_PATH + ")\\s*,\\s*'yyyy-MM-dd'\\s*\\)");
    private static final Pattern DECIMAL_FORMAT = Pattern.compile(
        "#numbers\\.formatDecimal\\(\\s*(" + VARIABLE_PATH + ")\\s*,\\s*1\\s*,\\s*2\\s*\\)");
    private static final Pattern TOKEN = Pattern.compile(
        "\\s+|'(?:[^'\\\\]|\\\\.)*'|" + VARIABLE_PATH
            + "|\\d+(?:\\.\\d+)?|==|!=|<=|>=|&&|\\|\\||\\?:|[()+*/%<>?:!\\-]");
    private static final Set<String> OPERATORS = Set.of(
        "and", "or", "not", "eq", "ne", "lt", "le", "gt", "ge", "div", "mod");
    private static final Set<String> RESERVED_ROOTS = Set.of(
        "tenant", "doc", "partner", "items", "totals", "statement", "profit", "signatures",
        "printDate", "showProfit", "copies", "true", "false", "null");
    private static final Pattern DATA_IMAGE = Pattern.compile(
        "(?i)^data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/]*={0,2}$");

    private final Safelist safelist;

    public TemplateSanitizer() {
        this.safelist = new Safelist()
            .addTags("html", "head", "body", "title", "meta", "style", "div", "span", "p", "br", "hr",
                "h1", "h2", "h3", "h4", "table", "thead", "tbody", "tfoot", "tr", "th", "td",
                "ul", "ol", "li", "b", "strong", "i", "em", "u", "small", "sub", "sup",
                "img", "section", "header", "footer", "font")
            .addAttributes(":all", "style", "class", "width", "height", "align", "valign",
                "colspan", "rowspan", "border", "cellpadding", "cellspacing", "title")
            // 不允许变量赋值、动态包含、动态 CSS 或未转义输出；不增加任何原有标签/指令。
            .addAttributes(":all", "th:text", "th:if", "th:unless", "th:each",
                "th:value", "th:class", "th:switch", "th:case")
            .addAttributes("table", "frame", "rules")
            .addAttributes("meta", "charset")
            .addAttributes("img", "src", "alt")
            .addProtocols("img", "src", "data")
            .addEnforcedAttribute("meta", "http-equiv", "Content-Type");
    }

    /** 老调用未传场景时兼容原有固定模板；实际利润数据仍由 B07 服务权限收口。 */
    public String clean(String html) {
        return clean(html, null);
    }

    /** 清洗源码；显式传入场景时使用该场景的现有变量/列字典。 */
    public String clean(String html, String scene) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("模板内容不能为空");
        }
        if (html.length() > MAX_TEMPLATE_CHARS) {
            throw new IllegalArgumentException("模板源码体积超限");
        }
        String lowerHtml = html.toLowerCase(Locale.ROOT);
        for (String directive : FORBIDDEN_DIRECTIVES) {
            if (lowerHtml.contains(directive)) {
                throw new IllegalArgumentException("模板禁止使用 " + directive);
            }
        }
        Matcher tagMatcher = REAL_TAG.matcher(html);
        boolean hasRealTag = false;
        while (tagMatcher.find()) {
            String tag = tagMatcher.group(1).toLowerCase(Locale.ROOT);
            if (!"body".equals(tag) && !"html".equals(tag) && !"head".equals(tag)) {
                hasRealTag = true;
                break;
            }
        }
        if (!hasRealTag) {
            throw new IllegalArgumentException("模板内容不含有效 HTML 标签，疑似被转成纯文本，请粘贴模板源码");
        }
        String cleaned = Jsoup.clean(html, "", this.safelist, new Document.OutputSettings().prettyPrint(false));
        if (cleaned.length() > MAX_TEMPLATE_CHARS) {
            throw new IllegalArgumentException("清洗后的模板体积超限");
        }
        Document doc = Jsoup.parse(cleaned);
        for (Element element : doc.getAllElements()) {
            // Thymeleaf 默认也会处理文本内联；不允许绕过 th:* 表达式验证。
            rejectInlineExpressions(element.ownText());
            rejectInlineExpressions(element.data());
            for (var attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = attribute.getValue().toLowerCase(Locale.ROOT);
                if (name.startsWith("on") || value.contains("javascript:") || value.contains("expression(")) {
                    throw new IllegalArgumentException("模板禁止事件属性、javascript: 或 CSS expression");
                }
            }
        }
        validateExpressions(doc, scene, new ExpressionScope(), 0);
        for (Element image : doc.select("img[src]")) {
            String src = image.attr("src");
            if (!src.isEmpty() && (src.length() > MAX_DATA_URI_CHARS || !DATA_IMAGE.matcher(src).matches())) {
                throw new IllegalArgumentException("模板图片 data URI 类型或大小无效");
            }
        }
        return cleaned;
    }

    static void rejectInlineExpressions(String text) {
        if (text.contains("[[") || text.contains("[(")) {
            throw new IllegalArgumentException("模板请使用受限 th:text，不支持文本内联表达式");
        }
    }

    private void validateExpressions(Element element, String scene, ExpressionScope inherited, int depth) {
        if (depth > 100) {
            throw new IllegalArgumentException("模板 HTML 嵌套过深");
        }
        ExpressionScope scope = inherited.copy();
        if (element.hasAttr("th:each")) {
            Matcher each = EACH_EXPRESSION.matcher(element.attr("th:each"));
            if (!each.matches()) {
                throw new IllegalArgumentException("th:each 只允许绑定已登记的表格数据源");
            }
            String item = each.group(1);
            String status = each.group(2);
            String source = each.group(3);
            if (!PrintVariables.isAllowedTableSource(scene, source)
                || isReservedAlias(item) || (status != null && isReservedAlias(status))
                || item.equals(status) || scope.contains(item) || (status != null && scope.contains(status))) {
                throw new IllegalArgumentException("th:each 的变量名或数据源不受支持");
            }
            scope.iterators.put(item, source);
            if (status != null) {
                scope.statusAliases.add(status);
            }
        }
        for (var attribute : element.attributes()) {
            String name = attribute.getKey().toLowerCase(Locale.ROOT);
            if (name.startsWith("th:") && !"th:each".equals(name)) {
                validateAttribute(name, attribute.getValue(), scene, scope);
            }
        }
        for (Element child : element.children()) {
            validateExpressions(child, scene, scope, depth + 1);
        }
    }

    private void validateAttribute(String attribute, String value, String scene, ExpressionScope scope) {
        if (value.length() > MAX_EXPRESSION_CHARS || value.contains("__")) {
            throw new IllegalArgumentException("模板表达式过长或使用了表达式预处理");
        }
        Matcher interpolation = INTERPOLATION.matcher(value);
        StringBuffer outer = new StringBuffer();
        boolean dynamic = false;
        while (interpolation.find()) {
            dynamic = true;
            validateExpression(interpolation.group(1), scene, scope, true);
            interpolation.appendReplacement(outer, "0");
        }
        interpolation.appendTail(outer);
        // Thymeleaf 的固定 class token 与 switch 默认分支不是模型变量。
        if (!dynamic && (("th:class".equals(attribute) && value.matches("[A-Za-z0-9_ .-]+"))
            || ("th:case".equals(attribute) && "*".equals(value)))) {
            return;
        }
        validateExpression(outer.toString(), scene, scope, false);
    }

    private void validateExpression(String expression, String scene, ExpressionScope scope, boolean variables) {
        List<String> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < expression.length()) {
            Matcher utility = DATE_FORMAT.matcher(expression).region(cursor, expression.length());
            if (!utility.lookingAt()) {
                utility = DECIMAL_FORMAT.matcher(expression).region(cursor, expression.length());
            }
            if (variables && utility.lookingAt()) {
                if (!isAllowedPath(utility.group(1), scene, scope)) {
                    throw new IllegalArgumentException("模板格式化函数的变量路径不受支持");
                }
                tokens.add("value");
                cursor = utility.end();
                continue;
            }
            Matcher token = TOKEN.matcher(expression).region(cursor, expression.length());
            if (!token.lookingAt()) {
                throw new IllegalArgumentException("模板禁止任意方法、类、索引或未登记的表达式语法");
            }
            String part = token.group();
            cursor = token.end();
            char first = part.charAt(0);
            if (Character.isWhitespace(first)) {
                continue;
            }
            if (first == '\'' || Character.isDigit(first) || Set.of("true", "false", "null").contains(part)) {
                tokens.add("value");
            } else if (Character.isLetter(first) && !OPERATORS.contains(part)) {
                // 仅允许已有利润模板使用 profit == null / profit != null 做空值保护，不允许输出整个对象。
                boolean profitNullCheck = "profit".equals(part)
                    && PrintVariables.isAllowedField(scene, "profit.costAmount")
                    && expression.substring(cursor).matches("(?s)^\\s*(==|!=)\\s*null\\b.*");
                if (!variables || (!profitNullCheck && !isAllowedPath(part, scene, scope))) {
                    throw new IllegalArgumentException("模板变量不在当前场景白名单中：" + part);
                }
                tokens.add("value");
            } else {
                tokens.add(part);
            }
        }
        new ExpressionSyntax(tokens).validate();
    }

    private boolean isAllowedPath(String path, String scene, ExpressionScope scope) {
        int dot = path.indexOf('.');
        String root = dot < 0 ? path : path.substring(0, dot);
        if (scope.iterators.containsKey(root)) {
            return dot > 0 && PrintVariables.isAllowedColumn(scene, scope.iterators.get(root), path.substring(dot + 1));
        }
        if (scope.statusAliases.contains(root)) {
            return dot > 0 && Set.of("index", "count", "size", "even", "odd", "first", "last")
                .contains(path.substring(dot + 1));
        }
        return "showProfit".equals(path) || "copies".equals(path) || PrintVariables.isAllowedField(scene, path);
    }

    private boolean isReservedAlias(String name) {
        return RESERVED_ROOTS.contains(name) || OPERATORS.contains(name);
    }

    private static final class ExpressionScope {
        private final Map<String, String> iterators = new HashMap<>();
        private final Set<String> statusAliases = new HashSet<>();

        private boolean contains(String name) {
            return iterators.containsKey(name) || statusAliases.contains(name);
        }

        private ExpressionScope copy() {
            ExpressionScope copy = new ExpressionScope();
            copy.iterators.putAll(iterators);
            copy.statusAliases.addAll(statusAliases);
            return copy;
        }
    }

    /** 只校验打印展示用运算符语法，不执行用户表达式；不向 Thymeleaf 暴露新的能力。 */
    private static final class ExpressionSyntax {
        private static final Map<String, Integer> PRECEDENCE = Map.ofEntries(
            Map.entry("or", 1), Map.entry("||", 1), Map.entry("and", 2), Map.entry("&&", 2),
            Map.entry("==", 3), Map.entry("!=", 3), Map.entry("eq", 3), Map.entry("ne", 3),
            Map.entry("<", 4), Map.entry(">", 4), Map.entry("<=", 4), Map.entry(">=", 4),
            Map.entry("lt", 4), Map.entry("le", 4), Map.entry("gt", 4), Map.entry("ge", 4),
            Map.entry("+", 5), Map.entry("-", 5), Map.entry("*", 6), Map.entry("/", 6),
            Map.entry("%", 6), Map.entry("div", 6), Map.entry("mod", 6));
        private final List<String> tokens;
        private int cursor;
        private int depth;

        private ExpressionSyntax(List<String> tokens) {
            this.tokens = tokens;
        }

        private void validate() {
            conditional();
            if (cursor != tokens.size()) {
                invalid();
            }
        }

        private void conditional() {
            if (++depth > 64) {
                invalid();
            }
            binary(1);
            if (take("?:")) {
                conditional();
            } else if (take("?")) {
                conditional();
                if (!take(":")) {
                    invalid();
                }
                conditional();
            }
            depth--;
        }

        private void binary(int minimum) {
            primary();
            while (cursor < tokens.size()) {
                int precedence = PRECEDENCE.getOrDefault(tokens.get(cursor), 0);
                if (precedence < minimum) {
                    return;
                }
                cursor++;
                binary(precedence + 1);
            }
        }

        private void primary() {
            while (take("!") || take("not") || take("+") || take("-")) {
                // 连续一元操作不递归，避免用户输入放大 Java 栈。
            }
            if (take("(")) {
                conditional();
                if (!take(")")) {
                    invalid();
                }
            } else if (!take("value")) {
                invalid();
            }
        }

        private boolean take(String token) {
            if (cursor < tokens.size() && token.equals(tokens.get(cursor))) {
                cursor++;
                return true;
            }
            return false;
        }

        private void invalid() {
            throw new IllegalArgumentException("模板表达式语法不受支持");
        }
    }
}
