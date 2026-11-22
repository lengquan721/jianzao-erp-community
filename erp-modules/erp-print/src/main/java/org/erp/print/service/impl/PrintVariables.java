package org.erp.print.service.impl;

import org.erp.print.domain.vo.PrintVariableVo;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板变量字典：设计器字段列表的数据来源
 *
 * <p>按场景给出可用变量。字段 key 与 {@link PrintContext} 的属性路径一一对应，
 * 保证「设计器里能选到的字段」都能在渲染期取到值。
 */
public final class PrintVariables {

    private PrintVariables() {
    }

    /**
     * 按场景返回可用变量。
     *
     * @param scene 场景，未知场景返回通用变量
     */
    public static List<PrintVariableVo> of(String scene) {
        List<PrintVariableVo> list = new ArrayList<>();
        // 通用：单据主信息
        list.add(new PrintVariableVo("doc.no", "单号", "XS202611180001", "单据"));
        list.add(new PrintVariableVo("doc.date", "单据日期", "2026-11-18", "单据"));
        list.add(new PrintVariableVo("doc.remark", "备注", "示例备注", "单据"));
        list.add(new PrintVariableVo("doc.status", "单据状态", "CONFIRMED", "单据"));
        list.add(new PrintVariableVo("printDate", "打印日期", "2026-11-18", "单据"));

        // 往来单位
        list.add(new PrintVariableVo("partner.name", "往来单位名称", "示例机械有限公司", "往来单位"));
        list.add(new PrintVariableVo("partner.contact", "联系人", "张三", "往来单位"));
        list.add(new PrintVariableVo("partner.phone", "联系电话", "13800000000", "往来单位"));
        list.add(new PrintVariableVo("partner.address", "地址", "示例市示例区示例路 1 号", "往来单位"));

        // 公司信息
        list.add(new PrintVariableVo("tenant.name", "公司名称", "示例加工厂", "公司"));
        list.add(new PrintVariableVo("tenant.title", "公司抬头", "示例加工厂", "公司"));
        list.add(new PrintVariableVo("tenant.phone", "公司电话", "0571-88888888", "公司"));
        list.add(new PrintVariableVo("tenant.address", "公司地址", "示例市示例区", "公司"));
        list.add(new PrintVariableVo("tenant.logoUrl", "公司 Logo 地址", "", "公司"));

        // 合计
        list.add(new PrintVariableVo("totals.qtyTotal", "数量合计", "10", "合计"));
        list.add(new PrintVariableVo("totals.amountTotal", "金额合计", "500.00", "合计"));
        list.add(new PrintVariableVo("totals.amountUpper", "金额大写", "伍佰元整", "合计"));
        list.add(new PrintVariableVo("totals.goodsAmount", "货品小计（不含运杂费）", "500.00", "合计"));
        list.add(new PrintVariableVo("totals.freightAmount", "运杂费", "0.00", "合计"));
        // 欠款（销售/收款场景）
        if ("SALE_DELIVERY".equals(scene) || "RECEIPT".equals(scene)
            || "STATEMENT".equals(scene) || "STATEMENT_RANGE".equals(scene)) {
            list.add(new PrintVariableVo("totals.unsettledAmount", "本单未收", "500.00", "欠款"));
            list.add(new PrintVariableVo("totals.debtTotal", "累计欠款", "2200.00", "欠款"));
        }
        if ("PURCHASE_IN".equals(scene) || "PAYMENT".equals(scene)) {
            list.add(new PrintVariableVo("totals.unsettledAmount", "本单未付", "500.00", "欠款"));
            list.add(new PrintVariableVo("totals.debtTotal", "累计欠款", "2200.00", "欠款"));
        }

        // 签章
        list.add(new PrintVariableVo("signatures.maker", "制单人", "管理员", "签章"));
        list.add(new PrintVariableVo("signatures.warehouse", "仓管", "", "签章"));
        list.add(new PrintVariableVo("signatures.customer", "客户签收", "", "签章"));

        // 收付款专有
        if ("RECEIPT".equals(scene) || "PAYMENT".equals(scene)) {
            list.add(new PrintVariableVo("doc.account", "结算账户", "微信", "单据"));
            list.add(new PrintVariableVo("doc.type", "单据类型", scene, "单据"));
        }

        // 对账单专有
        if ("STATEMENT".equals(scene) || "STATEMENT_RANGE".equals(scene)) {
            list.add(new PrintVariableVo("statement.month", "账单月份（月度）", "2026-11", "对账"));
            list.add(new PrintVariableVo("statement.periodStart", "对账开始日", "2026-11-01", "对账"));
            list.add(new PrintVariableVo("statement.periodEnd", "对账结束日", "2026-11-30", "对账"));
            list.add(new PrintVariableVo("statement.periodLabel", "对账期间", "2026-11 月度", "对账"));
            list.add(new PrintVariableVo("statement.opening", "期初余额（期初+本期变动计算基数）", "2000.00", "对账"));
            list.add(new PrintVariableVo("statement.openingEntryAmount", "本期建账期初", "0.00", "对账"));
            list.add(new PrintVariableVo("statement.salesAmount", "本期销售（不含退货）", "500.00", "对账"));
            list.add(new PrintVariableVo("statement.returnAmount", "本期退货", "0.00", "对账"));
            list.add(new PrintVariableVo("statement.netSalesAmount", "本期销售净额（销售-退货）", "500.00", "对账"));
            list.add(new PrintVariableVo("statement.receiptAmount", "本期收款", "300.00", "对账"));
            list.add(new PrintVariableVo("statement.refundAmount", "本期退款", "0.00", "对账"));
            list.add(new PrintVariableVo("statement.netReceiptAmount", "本期净收款（收款-退款）", "300.00", "对账"));
            list.add(new PrintVariableVo("statement.monthlyUnpaid", "本期净发生额", "200.00", "对账"));
            list.add(new PrintVariableVo("statement.cumulativeSales", "累计销售净额", "2500.00", "对账"));
            list.add(new PrintVariableVo("statement.cumulativeReceipts", "累计净收款", "300.00", "对账"));
            list.add(new PrintVariableVo("statement.cumulativeUnpaid", "所选期间期末余额", "2200.00", "对账"));
            list.add(new PrintVariableVo("statement.totalReceivable", "所选期间期末应收", "2200.00", "对账"));
            list.add(new PrintVariableVo("statement.closing", "期末余额（不含当前核销回溯）", "2200.00", "对账"));
        }

        // 利润（送货单 showProfit=true 时）
        if ("SALE_DELIVERY".equals(scene)) {
            list.add(new PrintVariableVo("profit.costAmount", "成本金额", "158.80", "利润"));
            list.add(new PrintVariableVo("profit.grossProfit", "毛利", "341.20", "利润"));
            list.add(new PrintVariableVo("profit.grossMargin", "毛利率", "68.24", "利润"));
        }
        return list;
    }

    /**
     * 按场景返回可选的表格数据源。
     */
    public static List<PrintVariableVo> tableSources(String scene) {
        List<PrintVariableVo> list = new ArrayList<>();
        if ("STATEMENT".equals(scene) || "STATEMENT_RANGE".equals(scene)) {
            list.add(new PrintVariableVo("statement.items", "对账明细", "所选期间的单据行", "表格"));
        } else {
            list.add(new PrintVariableVo("items", "单据明细", "单据的商品行", "表格"));
        }
        return list;
    }

    /**
     * 按数据源返回可选的列定义（key → 中文名）。
     *
     * @param dataSource items / statement.items
     */
    public static List<PrintVariableVo> columns(String dataSource) {
        return columns("SALE_DELIVERY", dataSource);
    }

    public static List<PrintVariableVo> columns(String scene, String dataSource) {
        List<PrintVariableVo> list = new ArrayList<>();
        if ("statement.items".equals(dataSource)) {
            list.add(new PrintVariableVo("date", "日期", "2026-11-05", "列"));
            list.add(new PrintVariableVo("no", "单号", "XS202611050001", "列"));
            list.add(new PrintVariableVo("content", "摘要", "销售/收款/退货/退款", "列"));
            list.add(new PrintVariableVo("productName", "商品名称", "料管", "列"));
            list.add(new PrintVariableVo("qtyText", "数量", "10", "列"));
            list.add(new PrintVariableVo("price", "单价", "50.00", "列"));
            list.add(new PrintVariableVo("amount", "本期变动", "500.00", "列"));
            list.add(new PrintVariableVo("settled", "已收（旧模板兼容，留空）", "", "列"));
            list.add(new PrintVariableVo("unsettled", "未结（旧模板兼容，留空）", "", "列"));
        } else if ("RECEIPT".equals(scene) || "PAYMENT".equals(scene)) {
            list.add(new PrintVariableVo("no", "关联单号", "XS202611050001", "列"));
            list.add(new PrintVariableVo("date", "日期", "2026-11-05", "列"));
            list.add(new PrintVariableVo("amount", "金额", "500.00", "列"));
        } else {
            list.add(new PrintVariableVo("index", "序号", "1", "列"));
            list.add(new PrintVariableVo("name", "商品名称", "料管", "列"));
            list.add(new PrintVariableVo("spec", "规格", "Φ25×2m", "列"));
            list.add(new PrintVariableVo("unit", "单位", "支", "列"));
            list.add(new PrintVariableVo("qty", "数量", "10", "列"));
            list.add(new PrintVariableVo("price", "单价", "50.00", "列"));
            list.add(new PrintVariableVo("amount", "金额", "500.00", "列"));
            list.add(new PrintVariableVo("taxType", "税类型", "NO_TAX", "列"));
            list.add(new PrintVariableVo("remark", "备注", "", "列"));
        }
        return list;
    }

    public static boolean hasField(String scene, String key) {
        return of(scene).stream().anyMatch(field -> field.getKey().equals(key));
    }

    public static boolean hasTableSource(String scene, String source) {
        return tableSources(scene).stream().anyMatch(item -> item.getKey().equals(source));
    }

    public static boolean hasColumn(String scene, String source, String key) {
        return columns(scene, source).stream().anyMatch(item -> item.getKey().equals(key));
    }

    /**
     * 精确复用现有变量字典，而不是允许某个根下的任意属性。
     * 老调用未传 scene 时兼容五种已有场景；利润数据的实际授权仍由 B07 打印服务取数前校验。
     */
    public static boolean isAllowedField(String scene, String key) {
        if (scene != null && !scene.isBlank()) {
            return hasField(scene, key);
        }
        return List.of("SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN", "RECEIPT", "PAYMENT")
            .stream().anyMatch(candidate -> hasField(candidate, key));
    }

    public static boolean isAllowedTableSource(String scene, String source) {
        if (scene != null && !scene.isBlank()) {
            return hasTableSource(scene, source);
        }
        return List.of("SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN", "RECEIPT", "PAYMENT")
            .stream().anyMatch(candidate -> hasTableSource(candidate, source));
    }

    public static boolean isAllowedColumn(String scene, String source, String key) {
        if (scene != null && !scene.isBlank()) {
            return hasTableSource(scene, source) && hasColumn(scene, source, key);
        }
        return List.of("SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN", "RECEIPT", "PAYMENT")
            .stream().anyMatch(candidate -> hasTableSource(candidate, source)
                && hasColumn(candidate, source, key));
    }
}
