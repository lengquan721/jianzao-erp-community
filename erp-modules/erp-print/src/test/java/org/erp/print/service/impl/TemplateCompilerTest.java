package org.erp.print.service.impl;

import org.erp.print.domain.design.BlockType;
import org.erp.print.domain.design.DesignBlock;
import org.erp.print.domain.design.DesignSchema;
import org.erp.print.domain.design.TableColumn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组件树 → Thymeleaf HTML 编译测试
 */
@Tag("dev")
class TemplateCompilerTest {

    private final TemplateCompiler compiler = new TemplateCompiler();

    private DesignSchema schema(DesignBlock... blocks) {
        DesignSchema s = new DesignSchema();
        s.setPaper("A4");
        s.setPageMargin(12);
        s.setBlocks(new ArrayList<>(List.of(blocks)));
        return s;
    }

    private DesignBlock block(BlockType type, Map<String, Object> props) {
        DesignBlock b = new DesignBlock();
        b.setId(type.name().toLowerCase());
        b.setType(type);
        b.setProps(props);
        b.setStyle(new HashMap<>());
        return b;
    }

    @Test
    @DisplayName("空组件树抛异常，避免存成空白模板")
    void rejectEmpty() {
        DesignSchema s = new DesignSchema();
        s.setBlocks(new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(s));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(null));
    }

    @Test
    @DisplayName("标题与字段编译出 th:text 绑定")
    void compileTitleAndField() {
        DesignBlock title = block(BlockType.TITLE, new HashMap<>(Map.of("text", "送 货 单", "subText", "第一联")));
        DesignBlock field = block(BlockType.FIELD, new HashMap<>(Map.of("fieldKey", "doc.no", "label", "单号")));
        String html = compiler.compile(schema(title, field));

        assertTrue(html.contains("<h1"), "标题应编译成 h1");
        assertTrue(html.contains("送 货 单"));
        assertTrue(html.contains("第一联"));
        assertTrue(html.contains("th:text=\"${doc.no}\""), "字段应编译成 th:text 表达式");
        assertTrue(html.contains("单号"));
        assertTrue(html.contains("@page"), "应带 @page 规则供分页");
    }

    @Test
    @DisplayName("表格按可见列与顺序输出，日期列带格式化")
    void compileTable() {
        List<TableColumn> cols = new ArrayList<>();
        cols.add(col("name", "商品名称", true));
        cols.add(col("hidden", "隐藏列", false));
        cols.add(col("date", "日期", true));

        Map<String, Object> props = new HashMap<>();
        props.put("dataSource", "items");
        props.put("columns", cols);
        String html = compiler.compile(schema(block(BlockType.TABLE, props)));

        assertTrue(html.contains("th:each=\"item : ${items}\""));
        assertTrue(html.contains("th:text=\"${item.name}\""));
        assertFalse(html.contains("隐藏列"), "visible=false 的列不应输出");
        assertTrue(html.contains("#dates.format(item.date"), "日期列应走日期格式化");
    }

    @Test
    @DisplayName("列宽与对齐写进 style")
    void compileColumnStyle() {
        TableColumn c = col("amount", "金额", true);
        c.setWidth(12);
        c.setAlign("right");
        Map<String, Object> props = new HashMap<>();
        props.put("dataSource", "items");
        props.put("columns", List.of(c));
        String html = compiler.compile(schema(block(BlockType.TABLE, props)));

        assertTrue(html.contains("width:12%"));
        assertTrue(html.contains("text-align:right"));
    }

    @Test
    @DisplayName("字段路径白名单：允许已知根，拒绝任意表达式")
    void enforceFieldWhitelist() {
        assertEquals("${doc.no}", TemplateCompiler.toExpression("doc.no"));
        assertEquals("${partner.name}", TemplateCompiler.toExpression("partner.name"));
        assertEquals("${totals.amountTotal}", TemplateCompiler.toExpression("totals.amountTotal"));

        // 不在白名单的根、以及表达式注入形式都应被拒绝
        assertThrows(IllegalArgumentException.class, () -> TemplateCompiler.toExpression("runtime.exec"));
        assertThrows(IllegalArgumentException.class, () -> TemplateCompiler.toExpression("T(java.lang.Runtime)"));
        assertThrows(IllegalArgumentException.class, () -> TemplateCompiler.toExpression("doc.no} ${'x"));
        // 空字段是「未选字段」，跳过该区块即可，不视为错误
        assertNull(TemplateCompiler.toExpression(""));
        assertNull(TemplateCompiler.toExpression(null));
    }

    @Test
    @DisplayName("文本内容做 HTML 转义，不引入可执行节点")
    void escapeText() {
        DesignBlock text = block(BlockType.TEXT, new HashMap<>(Map.of("text", "<script>alert(1)</script>")));
        String html = compiler.compile(schema(text));
        assertFalse(html.contains("<script>alert"), "尖括号应被转义");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    @DisplayName("图片只接受 data: 协议，外链降级为占位")
    void imageOnlyDataUri() {
        DesignBlock ext = block(BlockType.IMAGE, new HashMap<>(Map.of("src", "http://evil.example/x.png", "width", 100)));
        String html = compiler.compile(schema(ext));
        assertFalse(html.contains("http://evil.example"), "外链图片不应进模板");
        assertTrue(html.contains("LOGO"), "应降级为占位框");

        DesignBlock ok = block(BlockType.IMAGE, new HashMap<>(Map.of("src", "data:image/png;base64,AAA")));
        assertTrue(compiler.compile(schema(ok)).contains("data:image/png;base64,AAA"));
    }

    @Test
    @DisplayName("样式数值做范围钳制，防止写出异常字号")
    void clampStyles() {
        DesignBlock title = block(BlockType.TITLE, new HashMap<>(Map.of("text", "标题")));
        title.getStyle().put("fontSize", 999);
        String html = compiler.compile(schema(title));
        assertTrue(html.contains("font-size:40px"), "字号应被钳到上限 40");
    }

    @Test
    @DisplayName("全部区块类型都能编译出内容")
    void compileAllTypes() {
        Map<String, Object> sign = new HashMap<>();
        sign.put("labels", List.of("制单", "仓管"));
        String html = compiler.compile(schema(
            block(BlockType.TITLE, new HashMap<>(Map.of("text", "标题"))),
            block(BlockType.TEXT, new HashMap<>(Map.of("text", "文本"))),
            block(BlockType.FIELD, new HashMap<>(Map.of("fieldKey", "doc.no", "label", "单号"))),
            block(BlockType.DIVIDER, new HashMap<>(Map.of("thickness", 2))),
            block(BlockType.SIGNATURE, sign),
            block(BlockType.SPACER, new HashMap<>(Map.of("height", 20))),
            block(BlockType.PAGE_BREAK, new HashMap<>())
        ));
        assertTrue(html.contains("<hr"));
        assertTrue(html.contains("制单"));
        assertTrue(html.contains("page-break-after"));
        assertTrue(html.contains("height:20px"));
    }

    @Test
    @DisplayName("日期字段自动格式化，避免输出 Date.toString")
    void formatDateField() {
        assertEquals("${#dates.format(doc.date, 'yyyy-MM-dd')}", TemplateCompiler.fieldExpression("doc.date"));
        assertEquals("${#dates.format(printDate, 'yyyy-MM-dd')}", TemplateCompiler.fieldExpression("printDate"));
        // 非日期字段不加格式化
        assertEquals("${doc.no}", TemplateCompiler.fieldExpression("doc.no"));
        assertEquals("${partner.name}", TemplateCompiler.fieldExpression("partner.name"));

        DesignBlock field = block(BlockType.FIELD, new HashMap<>(Map.of("fieldKey", "doc.date", "label", "日期")));
        String html = compiler.compile(schema(field));
        assertTrue(html.contains("#dates.format(doc.date"));
        assertFalse(html.contains("${doc.date}"), "不应留下未格式化的裸日期绑定");
    }

    @Test
    @DisplayName("Logo 支持独立宽高，高度留空则只输出宽度")
    void imageSizeIndependent() {
        DesignBlock noHeight = block(BlockType.IMAGE, new HashMap<>(
            Map.of("src", "data:image/png;base64,AAA", "width", 180)));
        String html = compiler.compile(schema(noHeight));
        assertTrue(html.contains("width:180px"));
        assertFalse(html.contains("height:180px"), "未指定高度时不应写死高度");

        DesignBlock withHeight = block(BlockType.IMAGE, new HashMap<>(
            Map.of("src", "data:image/png;base64,AAA", "width", 180, "height", 90)));
        String html2 = compiler.compile(schema(withHeight));
        assertTrue(html2.contains("width:180px"));
        assertTrue(html2.contains("height:90px"));
    }

    @Test
    @DisplayName("Logo 对齐跟随样式，默认居中")
    void imageAlign() {
        Map<String, Object> props = new HashMap<>(Map.of("src", "data:image/png;base64,AAA"));
        DesignBlock b = block(BlockType.IMAGE, props);
        assertTrue(compiler.compile(schema(b)).contains("text-align:center"), "默认居中");

        b.getStyle().put("textAlign", "left");
        assertTrue(compiler.compile(schema(b)).contains("text-align:left"));
    }

    @Test
    @DisplayName("区块占纸宽百分比小于 100 时套定宽容器")
    void layoutWidthWraps() {
        Map<String, Object> props = new HashMap<>(Map.of("text", "标题", "layoutWidth", 60));
        DesignBlock b = block(BlockType.TITLE, props);
        String html = compiler.compile(schema(b));
        assertTrue(html.contains("width:60%"), "应输出 60% 定宽容器");
        assertTrue(html.indexOf("width:60%") < html.indexOf("<h1"), "容器应在标题之前");

        // 100% 不加容器，避免无谓嵌套
        props.put("layoutWidth", 100);
        assertFalse(compiler.compile(schema(b)).contains("width:100%"));
    }
    @Test
    @DisplayName("区块左偏移生成 margin-left，且不越界")
    void blockOffset() {
        Map<String, Object> props = new HashMap<>(Map.of("text", "标题", "layoutWidth", 40, "offsetX", 30));
        String html = compiler.compile(schema(block(BlockType.TITLE, props)));
        assertTrue(html.contains("width:40%"), "应限宽 40%");
        assertTrue(html.contains("margin-left:30%"), "应左缩进 30%");

        // 宽度 40 + 偏移 30 = 70，未越界；偏移给到 90 应被收回到 60
        props.put("offsetX", 90);
        assertTrue(compiler.compile(schema(block(BlockType.TITLE, props))).contains("margin-left:60%"),
            "偏移应被钳制到剩余空间");
    }

    @Test
    @DisplayName("区块宽度超范围时钳制")
    void layoutWidthClamped() {
        Map<String, Object> props = new HashMap<>(Map.of("text", "标题", "layoutWidth", 1));
        assertTrue(compiler.compile(schema(block(BlockType.TITLE, props))).contains("width:10%"));
    }

    @Test
    @DisplayName("Logo 有原图宽高比时同步输出高度，保证打印与画布一致")
    void imageRatioDrivesHeight() {
        Map<String, Object> props = new HashMap<>(
            Map.of("src", "data:image/png;base64,AAA", "width", 200, "ratio", 0.5));
        String html = compiler.compile(schema(block(BlockType.IMAGE, props)));
        assertTrue(html.contains("width:200px"), "宽度按设置输出");
        assertTrue(html.contains("height:100px"), "高度按比例算出 200*0.5=100");
    }

    @Test
    @DisplayName("Logo 自定义定位直接改写外层 margin-left，不再套第二层容器")
    void imageCustomOffset() {
        Map<String, Object> props = new HashMap<>(
            Map.of("src", "data:image/png;base64,AAA", "width", 160));
        DesignBlock b = block(BlockType.IMAGE, props);
        b.getStyle().put("textAlign", "center");
        b.getStyle().put("offsetPx", 120);

        String html = compiler.compile(schema(b));
        assertTrue(html.contains("margin-left:120px"), "应输出外层左外边距");
        assertFalse(html.contains("padding-left"), "不应再用内边距实现");
        assertFalse(html.contains("text-align:center"), "旧的对齐应被忽略");
    }

    @Test
    @DisplayName("Logo 定位为 0 时贴左，负值退回对齐模式")
    void imageCustomOffsetEdge() {
        Map<String, Object> props = new HashMap<>(
            Map.of("src", "data:image/png;base64,AAA", "width", 160));

        DesignBlock left = block(BlockType.IMAGE, props);
        left.getStyle().put("offsetPx", 0);
        assertTrue(compiler.compile(schema(left)).contains("margin-left:0px"), "0 表示贴左");

        DesignBlock negative = block(BlockType.IMAGE, props);
        negative.getStyle().put("offsetPx", -50);
        negative.getStyle().put("textAlign", "left");
        String html = compiler.compile(schema(negative));
        assertFalse(html.contains("margin-left:-50px"), "负值不应输出");
        assertTrue(html.contains("text-align:left"), "负值时退回对齐模式");
    }

    @Test
    @DisplayName("Logo 尺寸由图片决定，不受 layoutWidth/offsetX 影响")
    void imageIgnoresLayoutProps() {
        Map<String, Object> props = new HashMap<>(Map.of(
            "src", "data:image/png;base64,AAA", "width", 180,
            "layoutWidth", 30, "offsetX", 40));
        String html = compiler.compile(schema(block(BlockType.IMAGE, props)));
        assertTrue(html.contains("width:180px"), "宽度取图片宽度");
        assertFalse(html.contains("width:30%"), "不应输出区块百分比宽度");
        assertFalse(html.contains("margin-left:40%"), "不应输出区块百分比偏移");
    }

    private TableColumn col(String key, String label, boolean visible) {
        TableColumn c = new TableColumn();
        c.setKey(key);
        c.setLabel(label);
        c.setVisible(visible);
        return c;
    }
}
