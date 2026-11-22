package org.erp.print.service.impl;

import org.erp.print.domain.design.BlockType;
import org.erp.print.domain.design.DesignBlock;
import org.erp.print.domain.design.DesignSchema;
import org.erp.print.domain.design.TableColumn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设计器组件树 → Thymeleaf HTML 编译器（DOC/14 可视化设计器）
 *
 * <p>HTML 只在后端生成，前端只提交组件树 JSON：
 * 前端不产出不可信 HTML，省掉一处 XSS 面；编译逻辑集中一处便于维护；
 * 产物仍交给 {@link TemplateSanitizer} 二次把关，安全边界与源码模式一致。
 *
 * <p>全部文本输出使用 th:text（默认转义），不使用 th:utext。
 */
@Component
public class TemplateCompiler {

    /** 设计器资源上限；字段、表格数据源与列均按现有变量字典精确校验。 */
    private static final int MAX_BLOCKS = 100;
    private static final int MAX_COLUMNS = 20;
    private static final int MAX_DATA_URI_CHARS = TemplateSanitizer.MAX_DATA_URI_CHARS;

    /**
     * 编译组件树。
     *
     * @param schema 组件树
     * @return 完整 HTML（含 head/style），可直接交给净化器
     */
    public String compile(DesignSchema schema) {
        return compile(schema, null);
    }

    public String compile(DesignSchema schema, String scene) {
        if (schema == null || schema.getBlocks() == null || schema.getBlocks().isEmpty()) {
            throw new IllegalArgumentException("设计器内容为空，请至少添加一个区块");
        }
        if (schema.getBlocks().size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("设计器区块不能超过 " + MAX_BLOCKS + " 个");
        }
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n<style>\n");
        appendBaseStyle(sb, schema);
        sb.append("</style>\n</head>\n<body>\n");
        for (DesignBlock block : schema.getBlocks()) {
            if (block == null || block.getType() == null) {
                continue;
            }
            appendBlock(sb, block, scene);
            if (sb.length() > TemplateSanitizer.MAX_TEMPLATE_CHARS) {
                throw new IllegalArgumentException("编译后的模板体积超限");
            }
        }
        sb.append("</body>\n</html>\n");
        if (sb.length() > TemplateSanitizer.MAX_TEMPLATE_CHARS) {
            throw new IllegalArgumentException("编译后的模板体积超限");
        }
        return sb.toString();
    }

    private void appendBaseStyle(StringBuilder sb, DesignSchema schema) {
        String paper = PageSetup.safePageSize(schema.getPaper());
        int margin = schema.getPageMargin() == null ? 12 : clamp(schema.getPageMargin(), 0, 50);
        int fontSize = schema.getFontSize() == null ? 12 : clamp(schema.getFontSize(), 6, 40);
        String family = safeFontFamily(schema.getFontFamily());
        sb.append("@page { size: ").append(paper).append("; margin: ").append(margin).append("mm; }\n");
        sb.append("body { font-family: ").append(family).append("; font-size: ").append(fontSize).append("px; color: #000; margin: 0; }\n");
        sb.append("table { width: 100%; border-collapse: collapse; }\n");
        sb.append("th, td { border: 1px solid #333; padding: 5px 6px; }\n");
        sb.append("th { background: #f0f0f0; }\n");
        sb.append(".num { text-align: right; }\n");
        sb.append(".meta { display: flex; justify-content: space-between; margin-bottom: 8px; }\n");
        sb.append(".sign { display: flex; justify-content: space-between; margin-top: 30px; }\n");
    }

    private void appendBlock(StringBuilder sb, DesignBlock block, String scene) {
        BlockType type = block.getType();
        Map<String, Object> props = props(block);
        // IMAGE 的尺寸与位置完全由图片自身决定（width + style.offsetPx），
        // 不接受 layoutWidth/offsetX，否则调完图片还要再调一遍区块，两套值必然打架。
        if (type == BlockType.IMAGE) {
            appendImage(sb, block);
            return;
        }
        // 区块占纸宽百分比 + 左偏移：套一层定宽容器，让「只占 60%、靠右」这类排版能落地
        Integer layoutWidth = asNullableInt(props.get("layoutWidth"));
        Integer offsetX = asNullableInt(props.get("offsetX"));
        int width = (layoutWidth != null && layoutWidth > 0) ? clamp(layoutWidth, 10, 100) : 100;
        int offset = (offsetX != null && offsetX > 0) ? clamp(offsetX, 0, 100 - width) : 0;
        boolean wrapped = width < 100 || offset > 0;
        if (wrapped) {
            sb.append("<div style=\"width:").append(width).append("%;");
            if (offset > 0) {
                sb.append("margin-left:").append(offset).append("%;");
            }
            sb.append("\">\n");
        }
        switch (type) {
            case TITLE -> appendTitle(sb, block);
            case TEXT -> appendText(sb, block);
            case FIELD -> appendField(sb, block, scene);
            case TABLE -> appendTable(sb, block, scene);
            case DIVIDER -> appendDivider(sb, block);
            case SIGNATURE -> appendSignature(sb, block);
            case PAGE_BREAK -> sb.append("<div style=\"page-break-after: always;\"></div>\n");
            case SPACER -> appendSpacer(sb, block);
            default -> throw new IllegalArgumentException("不支持的区块类型：" + type);
        }
        if (wrapped) {
            sb.append("</div>\n");
        }
    }

    private void appendTitle(StringBuilder sb, DesignBlock block) {
        Map<String, Object> props = props(block);
        String text = safe(str(props.get("text")), "标 题");
        String subText = str(props.get("subText"));
        sb.append("<h1 style=\"").append(titleStyle(block)).append("\">");
        sb.append(escape(text));
        sb.append("</h1>\n");
        if (subText != null && !subText.isBlank()) {
            sb.append("<div style=\"text-align:center;color:#666;margin-bottom:10px;\">")
              .append(escape(subText)).append("</div>\n");
        }
    }

    private String titleStyle(DesignBlock block) {
        Map<String, Object> style = style(block);
        StringBuilder s = new StringBuilder("text-align:center;font-size:")
            .append(styleInt(style, "fontSize", 20)).append("px;letter-spacing:4px;margin:0 0 8px;");
        appendCommonStyle(s, style);
        return s.toString();
    }

    private void appendText(StringBuilder sb, DesignBlock block) {
        Map<String, Object> props = props(block);
        String text = str(props.get("text"));
        if (text == null || text.isBlank()) {
            return;
        }
        sb.append("<div style=\"").append(textStyle(block)).append("\">")
          .append(escape(text).replace("\n", "<br>")).append("</div>\n");
    }

    private String textStyle(DesignBlock block) {
        Map<String, Object> style = style(block);
        StringBuilder s = new StringBuilder("text-align:")
            .append(safeAlign(str(style.get("textAlign")), "left")).append(';');
        appendCommonStyle(s, style);
        return s.toString();
    }

    /**
     * 字段区块：按变量路径生成 th:text 表达式。
     *
     * <p>日期字段（doc.date / printDate 等）自动套 #dates.format，
     * 否则 java.util.Date 会打印成「Wed Nov 18 18:20:05 CST 2026」这种不可读形式。
     */
    private void appendField(StringBuilder sb, DesignBlock block, String scene) {
        Map<String, Object> props = props(block);
        String fieldKey = str(props.get("fieldKey"));
        String expr = fieldExpression(fieldKey, scene);
        if (expr == null) {
            return;
        }
        String label = str(props.get("label"));
        boolean showLabel = !Boolean.FALSE.equals(props.get("showLabel")) && label != null && !label.isBlank();
        sb.append("<div style=\"").append(fieldStyle(block)).append("\">");
        if (showLabel) {
            sb.append(escape(label)).append("：");
        }
        sb.append("<b th:text=\"").append(expr).append("\"></b>");
        sb.append("</div>\n");
    }

    /**
     * 字段的最终表达式：日期字段包一层格式化，其他原样（含 null）。
     */
    static String fieldExpression(String fieldKey) {
        return fieldExpression(fieldKey, null);
    }

    static String fieldExpression(String fieldKey, String scene) {
        String expr = toExpression(fieldKey, scene);
        if (expr == null) {
            return null;
        }
        if (isDateField(fieldKey)) {
            String path = expr.substring(2, expr.length() - 1);
            return "${#dates.format(" + path + ", 'yyyy-MM-dd')}";
        }
        return expr;
    }

    /**
     * 日期字段判定：PrintContext 里这些字段是 java.util.Date
     */
    static boolean isDateField(String fieldKey) {
        if (fieldKey == null) {
            return false;
        }
        String key = fieldKey.trim();
        return "doc.date".equals(key) || "printDate".equals(key) || key.endsWith(".date");
    }

    private String fieldStyle(DesignBlock block) {
        Map<String, Object> style = style(block);
        StringBuilder s = new StringBuilder("text-align:")
            .append(safeAlign(str(style.get("textAlign")), "left")).append(';');
        appendCommonStyle(s, style);
        return s.toString();
    }

    /**
     * Logo 区块：区块尺寸就是图片尺寸。
     *
     * <p>宽度取 props.width，左距取 style.offsetPx（margin-left），两者共同决定这一块的
     * 位置与大小，不再额外套百分比容器 —— 否则同一张 Logo 有两套尺寸来源。
     * 未设 offsetPx 时退回 textAlign 三档对齐。
     */
    private void appendImage(StringBuilder sb, DesignBlock block) {
        Map<String, Object> props = props(block);
        String src = str(props.get("src"));
        // 允许 null：模板可能先放占位图，客户后补。只接收有限大小的 base64 位图 data URI。
        if (src == null || src.isBlank() || !src.startsWith("data:")) {
            src = "";
        } else if (src.length() > MAX_DATA_URI_CHARS) {
            throw new IllegalArgumentException("图片 data URI 超过允许大小");
        } else if (!src.matches("(?i)^data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/]*={0,2}$")) {
            throw new IllegalArgumentException("图片只允许 PNG/JPEG/GIF/WebP base64 data URI");
        }
        int width = clamp(propsInt(props, "width", 120), 10, 2000);
        // 高度留空 = 按原图比例自适应，只写 width
        Integer height = asNullableInt(props.get("height"));
        if (height != null) {
            height = clamp(height, 10, 2000);
        } else {
            // 设计器已量出原图宽高比时，同步写死高度，保证打印与画布一致
            Double ratio = asNullableDouble(props.get("ratio"));
            if (ratio != null && ratio > 0) {
                height = clamp((int) Math.round(width * ratio), 10, 4000);
            }
        }
        String size = "width:" + width + "px;"
            + (height == null ? "" : "height:" + height + "px;");

        // 自定义定位：offsetPx 即区块自身的左偏移，直接写在最外层，不再套二层容器
        Integer offsetPx = asNullableInt(style(block).get("offsetPx"));
        if (offsetPx != null && offsetPx >= 0) {
            sb.append("<div style=\"margin-left:").append(clamp(offsetPx, 0, 2000)).append("px;\">");
        } else {
            String align = safeAlign(str(style(block).get("textAlign")), "center");
            sb.append("<div style=\"text-align:").append(align).append(";\">");
        }
        if (src.isEmpty()) {
            sb.append("<div style=\"").append(size).append("border:1px dashed #bbb;")
              .append("line-height:40px;color:#999;font-size:12px;text-align:center;\">LOGO</div>");
        } else {
            sb.append("<img src=\"").append(escapeAttr(src)).append("\" alt=\"logo\" style=\"")
              .append(size).append("\" />");
        }
        sb.append("</div>\n");
    }

    private void appendTable(StringBuilder sb, DesignBlock block, String scene) {
        Map<String, Object> props = props(block);
        String dataSource = safe(str(props.get("dataSource")), "items");
        if (!PrintVariables.isAllowedTableSource(scene, dataSource)) {
            throw new IllegalArgumentException("不支持的表格数据源：" + dataSource);
        }
        List<TableColumn> columns = columns(props.get("columns"));
        List<TableColumn> shown = new ArrayList<>();
        for (TableColumn c : columns) {
            if (c != null && c.isShown() && c.getKey() != null && !c.getKey().isBlank()) {
                if (!PrintVariables.isAllowedColumn(scene, dataSource, c.getKey())) {
                    throw new IllegalArgumentException("不支持的表格列：" + c.getKey());
                }
                shown.add(c);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        sb.append("<table>\n<thead>\n<tr>\n");
        for (TableColumn c : shown) {
            sb.append("<th");
            appendColumnStyle(sb, c);
            sb.append('>').append(escape(safe(c.getLabel(), c.getKey()))).append("</th>\n");
        }
        sb.append("</tr>\n</thead>\n<tbody>\n<tr th:each=\"item : ${").append(escapeAttr(dataSource)).append("}\">\n");
        for (TableColumn c : shown) {
            sb.append("<td");
            appendColumnStyle(sb, c);
            sb.append(" th:text=\"").append(cellExpression(dataSource, c.getKey())).append("\"></td>\n");
        }
        sb.append("</tr>\n</tbody>\n</table>\n");
    }

    private void appendColumnStyle(StringBuilder sb, TableColumn c) {
        List<String> styles = new ArrayList<>();
        if (c.getWidth() != null && c.getWidth() > 0 && c.getWidth() <= 100) {
            styles.add("width:" + c.getWidth() + "%");
        }
        String align = str(c.getAlign());
        if (align != null && List.of("left", "center", "right").contains(align)) {
            styles.add("text-align:" + align);
        } else if (c.isNumericColumn()) {
            styles.add("text-align:right");
        }
        if (!styles.isEmpty()) {
            sb.append(" style=\"").append(String.join(";", styles)).append('"');
        }
    }

    /**
     * 单元格表达式：日期字段走 #dates.format，其余直出。
     */
    private String cellExpression(String dataSource, String key) {
        if ("date".equals(key)) {
            return "${#dates.format(item.date, 'yyyy-MM-dd')}";
        }
        return "${item." + escapeAttr(key) + "}";
    }

    private void appendDivider(StringBuilder sb, DesignBlock block) {
        Map<String, Object> props = props(block);
        int thickness = propsInt(props, "thickness", 1);
        String color = safeColor(str(props.get("color")), "#333");
        sb.append("<hr style=\"border:none;border-top:").append(thickness).append("px solid ")
          .append(escapeAttr(color)).append(";margin:10px 0;\" />\n");
    }

    private void appendSignature(StringBuilder sb, DesignBlock block) {
        Map<String, Object> props = props(block);
        List<String> labels = labels(props.get("labels"));
        if (labels.isEmpty()) {
            labels = List.of("制单", "仓管", "客户签收");
        }
        sb.append("<div class=\"sign\">\n");
        for (String label : labels) {
            sb.append("<span>").append(escape(label)).append("：______________</span>\n");
        }
        sb.append("</div>\n");
    }

    private void appendSpacer(StringBuilder sb, DesignBlock block) {
        int height = propsInt(props(block), "height", 12);
        sb.append("<div style=\"height:").append(clamp(height, 0, 300)).append("px;\"></div>\n");
    }

    /* ------------------------------ 变量路径 ------------------------------ */

    /**
     * 字段路径 → Thymeleaf 表达式。仅允许变量字典中的完整路径，拒绝任意表达式注入。
     */
    static String toExpression(String fieldKey) {
        return toExpression(fieldKey, null);
    }

    static String toExpression(String fieldKey, String scene) {
        if (fieldKey == null || fieldKey.isBlank()) {
            return null;
        }
        String key = fieldKey.trim();
        // 语法限制与场景变量字典同时校验，不仅依赖根名称白名单。
        if (!key.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")) {
            throw new IllegalArgumentException("非法的字段路径：" + fieldKey);
        }
        if (!"showProfit".equals(key) && !PrintVariables.isAllowedField(scene, key)) {
            throw new IllegalArgumentException("不支持的字段路径：" + key);
        }
        return "${" + key + "}";
    }

    /* ------------------------------ 工具 ------------------------------ */

    private Map<String, Object> props(DesignBlock block) {
        return block.getProps() == null ? Map.of() : block.getProps();
    }

    private Map<String, Object> style(DesignBlock block) {
        return block.getStyle() == null ? Map.of() : block.getStyle();
    }

    private void appendCommonStyle(StringBuilder s, Map<String, Object> style) {
        Object fs = style.get("fontSize");
        if (fs != null) {
            s.append("font-size:").append(clamp(asInt(fs, 12), 6, 40)).append("px;");
        }
        Object fw = style.get("fontWeight");
        if ("bold".equals(str(fw))) {
            s.append("font-weight:bold;");
        }
        Object color = style.get("color");
        if (color != null && str(color).matches("#[0-9A-Fa-f]{3,8}")) {
            s.append("color:").append(str(color)).append(';');
        }
        Object mt = style.get("marginTop");
        if (mt != null) {
            s.append("margin-top:").append(clamp(asInt(mt, 0), 0, 200)).append("px;");
        }
        Object mb = style.get("marginBottom");
        if (mb != null) {
            s.append("margin-bottom:").append(clamp(asInt(mb, 0), 0, 200)).append("px;");
        }
    }

    @SuppressWarnings("unchecked")
    private List<TableColumn> columns(Object raw) {
        List<TableColumn> list = new ArrayList<>();
        if (raw instanceof List<?> rows) {
            if (rows.size() > MAX_COLUMNS) {
                throw new IllegalArgumentException("表格列不能超过 " + MAX_COLUMNS + " 个");
            }
            for (Object o : rows) {
                if (o instanceof TableColumn c) {
                    list.add(c);
                } else if (o instanceof Map<?, ?> m) {
                    TableColumn c = new TableColumn();
                    c.setKey(str(m.get("key")));
                    c.setLabel(str(m.get("label")));
                    c.setWidth(asNullableInt(m.get("width")));
                    c.setAlign(str(m.get("align")));
                    c.setVisible(m.get("visible") == null || Boolean.TRUE.equals(m.get("visible")));
                    c.setNumeric(Boolean.TRUE.equals(m.get("numeric")));
                    list.add(c);
                }
            }
        }
        return list;
    }

    private List<String> labels(Object raw) {
        List<String> list = new ArrayList<>();
        if (raw instanceof List<?> rows) {
            for (Object o : rows) {
                String v = str(o);
                if (v != null && !v.isBlank()) {
                    list.add(v);
                }
            }
        }
        return list;
    }

    private int propsInt(Map<String, Object> props, String key, int def) {
        Object v = props.get(key);
        return v == null ? def : asInt(v, def);
    }

    private int styleInt(Map<String, Object> style, String key, int def) {
        Object v = style.get(key);
        return v == null ? def : asInt(v, def);
    }

    private int asInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Integer asNullableInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double asNullableDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String safe(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static String safeFontFamily(String family) {
        if (family == null || family.isBlank()
            || !family.matches("[\\p{L}\\p{N} _,\"'.-]+")) {
            return "\"Microsoft YaHei\", \"PingFang SC\", sans-serif";
        }
        return family;
    }

    private static String safeAlign(String align, String def) {
        return align != null && List.of("left", "center", "right").contains(align) ? align : def;
    }

    private static String safeColor(String color, String def) {
        return color != null && color.matches("#[0-9A-Fa-f]{3,8}|[A-Za-z]+") ? color : def;
    }

    /**
     * 转义文本节点。
     */
    static String escape(String v) {
        if (v == null) {
            return "";
        }
        TemplateSanitizer.rejectInlineExpressions(v);
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 转义属性值。
     */
    static String escapeAttr(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
