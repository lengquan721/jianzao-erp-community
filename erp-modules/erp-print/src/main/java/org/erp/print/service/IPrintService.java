package org.erp.print.service;

/**
 * 打印渲染服务（DOC/12 §9、DOC/14）
 *
 * 渲染返回完整 HTML（含内联 CSS），前端 iframe 加载后 window.print()。
 */
public interface IPrintService {

    /**
     * 送货单渲染。showProfit=true 还需独立权限 erp:print:profit，服务端在取数前校验；
     * false 时不读取真实成本/毛利，亦不构造利润上下文。copies 为打印份数（联单）。
     */
    String renderSaleDelivery(Long saleId, Long templateId, boolean showProfit, int copies);

    /**
     * 对账单月度渲染。保留旧 month 参数调用约定。
     */
    String renderStatement(Long customerId, String month, Long templateId);

    /**
     * 对账单渲染：month 为空时按当前月；startDate 与 endDate 同时传入时优先执行闭区间。
     * 区间日期须为 yyyy-MM-dd，跨度最多 366 个自然日（含首尾）。
     */
    String renderStatement(Long customerId, String month, String startDate, String endDate, Long templateId);

    /**
     * 采购入库单渲染
     *
     * @param purchaseId 采购入库单ID
     * @param templateId 指定模板，null 走场景默认/内置兜底
     * @return 完整 HTML
     */
    String renderPurchaseIn(Long purchaseId, Long templateId);

    /**
     * 收款单渲染。
     */
    String renderReceipt(Long receiptId, Long templateId);

    /**
     * 付款单渲染。
     */
    String renderPayment(Long paymentId, Long templateId);

    /**
     * 模板编辑预览：旧调用兼容，不指定额外纸张。
     */
    String preview(String scene, String content, Long customerId, boolean showProfit);

    /**
     * 模板编辑预览：paper 仅接受 PageSetup 支持的标准或受限自定义纸张。
     */
    String preview(String scene, String content, Long customerId, boolean showProfit, String paper);

    /**
     * 预览设计器组件树：编译成 HTML 后用示例数据渲染
     *
     * @param scene      场景
     * @param schemaJson 组件树 JSON
     * @param showProfit 是否渲染利润区
     * @return 渲染后的 HTML
     */
    String previewDesign(String scene, String schemaJson, boolean showProfit);
}
