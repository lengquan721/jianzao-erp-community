package org.erp.print.service.impl;

import org.erp.print.domain.design.BlockType;
import org.erp.print.domain.design.DesignBlock;
import org.erp.print.domain.design.DesignSchema;
import org.erp.print.domain.design.TableColumn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设计器预置版式：与 {@link BuiltinTemplates} 的 4 套内置模板视觉一致，外加采购入库单
 *
 * <p>用途：新建模板时一键套用作为起点，客户在已有版式上微调，
 * 比面对空白画布更容易上手；同时验证设计器的表达能力能覆盖现有内置版式。
 */
public final class DesignPresets {

    private DesignPresets() {
    }

    /**
     * 按场景返回预置组件树；未知场景返回通用送货单版式。
     */
    public static DesignSchema of(String scene) {
        return switch (scene == null ? "" : scene) {
            case "STATEMENT" -> statement();
            case "STATEMENT_RANGE" -> statementRange();
            case "PURCHASE_IN" -> purchaseIn();
            case "RECEIPT", "PAYMENT" -> receiptPayment();
            default -> saleDelivery();
        };
    }

    public static DesignSchema saleDelivery() {
        DesignSchema s = newSchema("A4", 12);
        s.getBlocks().add(title("送 货 单", null));
        s.getBlocks().add(field("tenant.name", "公司", true));
        s.getBlocks().add(field("doc.no", "单号", true));
        s.getBlocks().add(field("partner.name", "客户", true));
        s.getBlocks().add(field("doc.date", "日期", true));
        s.getBlocks().add(table("items", itemColumns(), true));
        s.getBlocks().add(field("totals.amountTotal", "合计金额", true));
        s.getBlocks().add(field("totals.amountUpper", "金额大写", true));
        s.getBlocks().add(field("totals.unsettledAmount", "本次未收", true));
        s.getBlocks().add(field("totals.debtTotal", "累计欠款", true));
        s.getBlocks().add(signature(List.of("制单", "仓管", "客户签收")));
        return s;
    }

    public static DesignSchema statement() {
        DesignSchema s = newSchema("A4", 12);
        s.getBlocks().add(title("对 账 单", null));
        s.getBlocks().add(field("partner.name", "客户", true));
        s.getBlocks().add(field("statement.month", "账单月份", true));
        s.getBlocks().add(field("statement.opening", "期初余额", true));
        s.getBlocks().add(field("statement.openingEntryAmount", "本期建账期初", true));
        s.getBlocks().add(field("statement.salesAmount", "本期销售", true));
        s.getBlocks().add(field("statement.returnAmount", "本期退货", true));
        s.getBlocks().add(field("statement.receiptAmount", "本期收款", true));
        s.getBlocks().add(field("statement.refundAmount", "本期退款", true));
        s.getBlocks().add(field("statement.closing", "期末余额", true));
        s.getBlocks().add(table("statement.items", statementColumns(), true));
        s.getBlocks().add(field("totals.debtTotal", "累计欠款", true));
        s.getBlocks().add(signature(List.of("制单", "客户确认")));
        return s;
    }

    /** Range counterpart keeps the same component-tree framework and exposes period-specific totals. */
    public static DesignSchema statementRange() {
        DesignSchema s = newSchema("A4", 12);
        s.getBlocks().add(title("对 账 单", null));
        s.getBlocks().add(field("partner.name", "客户", true));
        s.getBlocks().add(field("statement.periodLabel", "对账期间", true));
        s.getBlocks().add(field("statement.opening", "期初应收", true));
        s.getBlocks().add(field("statement.netSalesAmount", "本期销售净额", true));
        s.getBlocks().add(field("statement.netReceiptAmount", "本期净收款", true));
        s.getBlocks().add(field("statement.monthlyUnpaid", "本期净发生额", true));
        s.getBlocks().add(field("statement.cumulativeSales", "累计销售净额", true));
        s.getBlocks().add(field("statement.cumulativeReceipts", "累计净收款", true));
        s.getBlocks().add(field("statement.cumulativeUnpaid", "期间期末余额", true));
        s.getBlocks().add(table("statement.items", statementColumns(), true));
        s.getBlocks().add(field("totals.debtTotal", "当前实时未核销余额", true));
        s.getBlocks().add(signature(List.of("制单", "客户确认")));
        return s;
    }

    public static DesignSchema purchaseIn() {
        DesignSchema s = newSchema("A4", 12);
        s.getBlocks().add(title("采 购 入 库 单", null));
        s.getBlocks().add(field("tenant.name", "公司", true));
        s.getBlocks().add(field("doc.no", "单号", true));
        s.getBlocks().add(field("partner.name", "供应商", true));
        s.getBlocks().add(field("doc.date", "日期", true));
        s.getBlocks().add(table("items", itemColumns(), true));
        s.getBlocks().add(field("totals.amountTotal", "合计金额", true));
        s.getBlocks().add(field("totals.amountUpper", "金额大写", true));
        s.getBlocks().add(field("totals.unsettledAmount", "本次未付", true));
        s.getBlocks().add(field("totals.debtTotal", "累计欠款", true));
        s.getBlocks().add(signature(List.of("制单", "仓管", "供应商确认")));
        return s;
    }

    public static DesignSchema receiptPayment() {
        DesignSchema s = newSchema("A5", 10);
        s.getBlocks().add(title("收 款 单", null));
        s.getBlocks().add(field("doc.no", "单号", true));
        s.getBlocks().add(field("doc.date", "日期", true));
        s.getBlocks().add(field("partner.name", "往来单位", true));
        s.getBlocks().add(field("doc.account", "结算账户", true));
        s.getBlocks().add(table("items", allocColumns(), true));
        s.getBlocks().add(field("totals.amountTotal", "合计金额", true));
        s.getBlocks().add(field("totals.amountUpper", "金额大写", true));
        s.getBlocks().add(signature(List.of("制单", "客户/供应商确认")));
        return s;
    }

    /* ------------------------------ 列定义 ------------------------------ */

    private static List<TableColumn> itemColumns() {
        List<TableColumn> cols = new ArrayList<>();
        cols.add(col("index", "序号", 6, "center", false));
        cols.add(col("name", "商品名称", null, "left", false));
        cols.add(col("spec", "规格", 16, "left", false));
        cols.add(col("unit", "单位", 8, "center", false));
        cols.add(col("qty", "数量", 10, "right", false));
        cols.add(col("price", "单价", 12, "right", false));
        cols.add(col("amount", "金额", 12, "right", false));
        return cols;
    }

    private static List<TableColumn> statementColumns() {
        List<TableColumn> cols = new ArrayList<>();
        cols.add(col("date", "日期", 11, "center", false));
        cols.add(col("no", "单号", 16, "left", false));
        cols.add(col("content", "摘要", 12, "left", false));
        cols.add(col("productName", "商品名称", null, "left", false));
        cols.add(col("qtyText", "数量", 9, "right", false));
        cols.add(col("price", "单价", 9, "right", false));
        cols.add(col("amount", "发生额", 14, "right", false));
        return cols;
    }

    private static List<TableColumn> allocColumns() {
        List<TableColumn> cols = new ArrayList<>();
        cols.add(col("no", "关联单据", null, "left", false));
        cols.add(col("date", "日期", 26, "center", false));
        cols.add(col("amount", "金额", 24, "right", false));
        return cols;
    }

    private static TableColumn col(String key, String label, Integer width, String align, boolean numeric) {
        TableColumn c = new TableColumn();
        c.setKey(key);
        c.setLabel(label);
        c.setWidth(width);
        c.setAlign(align);
        c.setVisible(true);
        c.setNumeric(numeric);
        return c;
    }

    /* ------------------------------ 区块构造 ------------------------------ */

    private static DesignSchema newSchema(String paper, int margin) {
        DesignSchema s = new DesignSchema();
        s.setPaper(paper);
        s.setPageMargin(margin);
        s.setFontFamily("\"Microsoft YaHei\", \"PingFang SC\", sans-serif");
        s.setFontSize(12);
        s.setBlocks(new ArrayList<>());
        return s;
    }

    private static DesignBlock base(BlockType type, Map<String, Object> props) {
        DesignBlock b = new DesignBlock();
        b.setId(type.name().toLowerCase() + "-" + Integer.toHexString(System.identityHashCode(props)));
        b.setType(type);
        b.setProps(props);
        b.setStyle(new LinkedHashMap<>());
        return b;
    }

    private static DesignBlock title(String text, String subText) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", text);
        if (subText != null) {
            props.put("subText", subText);
        }
        DesignBlock b = base(BlockType.TITLE, props);
        b.getStyle().put("fontSize", 20);
        return b;
    }

    private static DesignBlock field(String fieldKey, String label, boolean showLabel) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fieldKey", fieldKey);
        props.put("label", label);
        props.put("showLabel", showLabel);
        return base(BlockType.FIELD, props);
    }

    private static DesignBlock table(String dataSource, List<TableColumn> columns, boolean showHeader) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("dataSource", dataSource);
        props.put("columns", columns);
        props.put("showHeader", showHeader);
        props.put("bordered", true);
        return base(BlockType.TABLE, props);
    }

    private static DesignBlock signature(List<String> labels) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("labels", labels);
        return base(BlockType.SIGNATURE, props);
    }
}
