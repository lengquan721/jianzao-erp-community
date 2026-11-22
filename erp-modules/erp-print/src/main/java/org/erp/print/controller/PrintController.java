package org.erp.print.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.erp.print.domain.bo.PrintTemplateBo;
import org.erp.print.domain.design.DesignSchema;
import org.erp.print.domain.vo.PrintTemplateVo;
import org.erp.print.service.IPrintService;
import org.erp.print.service.IPrintTemplateService;
import org.erp.print.service.impl.DesignPresets;
import org.erp.print.service.impl.PrintVariables;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 打印模板管理 + 渲染（DOC/12 §9）
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/print")
public class PrintController extends BaseController {

    private final IPrintTemplateService templateService;
    private final IPrintService printService;

    /**
     * 设计器变量字典：按场景返回可用字段、表格数据源与列定义
     */
    @SaCheckPermission("erp:print:template:list")
    @GetMapping("/design/variables")
    public R<Map<String, Object>> variables(@RequestParam(required = false) String scene) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fields", PrintVariables.of(scene));
        data.put("tableSources", PrintVariables.tableSources(scene));
        data.put("columns", Map.of(
            "items", PrintVariables.columns("items"),
            "statement.items", PrintVariables.columns("statement.items")
        ));
        return R.ok(data);
    }

    /**
     * 设计器预置版式：按场景返回组件树，作为新建模板的起点
     */
    @SaCheckPermission("erp:print:template:list")
    @GetMapping("/design/presets")
    public R<DesignSchema> presets(@RequestParam(required = false) String scene) {
        return R.ok(DesignPresets.of(scene));
    }

    /**
     * 模板列表
     */
    @SaCheckPermission("erp:print:template:list")
    @GetMapping("/templates")
    public TableDataInfo<PrintTemplateVo> templates(@RequestParam(required = false) String scene,
                                                    @RequestParam(required = false) String keyword,
                                                    PageQuery pageQuery) {
        return templateService.selectPage(scene, keyword, pageQuery);
    }

    /**
     * 模板详情
     */
    @SaCheckPermission("erp:print:template:list")
    @GetMapping("/templates/{id}")
    public R<PrintTemplateVo> template(@PathVariable Long id) {
        return R.ok(templateService.selectById(id));
    }

    /**
     * 新增模板
     */
    @SaCheckPermission("erp:print:template:edit")
    @PostMapping("/templates")
    public R<PrintTemplateVo> add(@Valid @RequestBody PrintTemplateBo bo) {
        return R.ok(templateService.insert(bo));
    }

    /**
     * 编辑模板（version+1）
     */
    @SaCheckPermission("erp:print:template:edit")
    @PutMapping("/templates/{id}")
    public R<PrintTemplateVo> edit(@PathVariable Long id, @Valid @RequestBody PrintTemplateBo bo) {
        return R.ok(templateService.update(id, bo));
    }

    /**
     * 停用模板（软删除保留审计）
     */
    @SaCheckPermission("erp:print:template:edit")
    @DeleteMapping("/templates/{id}")
    public R<Void> disable(@PathVariable Long id) {
        templateService.disable(id);
        return R.ok();
    }

    /**
     * 设为场景默认
     */
    @SaCheckPermission("erp:print:template:edit")
    @PostMapping("/templates/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        templateService.setDefault(id);
        return R.ok();
    }

    /**
     * 预览：用示例数据渲染编辑中的模板内容，直接返回 HTML（与渲染接口一致，前端按纯文本透传）
     */
    @SaCheckPermission("erp:print:template:preview")
    @PostMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String preview(@RequestParam String scene,
                          @RequestBody String content,
                          @RequestParam(required = false) Long customerId,
                          @RequestParam(defaultValue = "false") boolean showProfit,
                          @RequestParam(required = false) String paper) {
        return printService.preview(scene, content, customerId, showProfit, paper);
    }

    /**
     * 预览设计器组件树：后端编译成 HTML 后用示例数据渲染
     *
     * <p>设计器不需要把组件树先落库再预览，也不需要在浏览器里拼 HTML。
     */
    @SaCheckPermission("erp:print:template:preview")
    @PostMapping(value = "/preview/design", produces = MediaType.TEXT_HTML_VALUE)
    public String previewDesign(@RequestParam String scene,
                                @RequestBody String schemaJson,
                                @RequestParam(defaultValue = "false") boolean showProfit) {
        return printService.previewDesign(scene, schemaJson, showProfit);
    }

    /**
     * 送货单渲染（copies 为打印份数/联单数，默认 1）。
     * showProfit 默认 false；true 还须由服务层校验独立权限 erp:print:profit，普通渲染权限不替代它。
     */
    @SaCheckPermission("erp:print:render")
    @GetMapping(value = "/SALE_DELIVERY/{saleId}", produces = MediaType.TEXT_HTML_VALUE)
    public String saleDelivery(@PathVariable Long saleId,
                               @RequestParam(required = false) Long templateId,
                               @RequestParam(defaultValue = "false") boolean showProfit,
                               @RequestParam(defaultValue = "1") int copies) {
        return printService.renderSaleDelivery(saleId, templateId, showProfit, copies);
    }

    /**
     * 对账单渲染：month 为月度账期；startDate + endDate 为自定义闭区间（同时给出时优先）
     */
    @SaCheckPermission("erp:print:render")
    @GetMapping(value = "/STATEMENT", produces = MediaType.TEXT_HTML_VALUE)
    public String statement(@RequestParam Long customerId,
                            @RequestParam(required = false) String month,
                            @RequestParam(required = false) String startDate,
                            @RequestParam(required = false) String endDate,
                            @RequestParam(required = false) Long templateId) {
        return printService.renderStatement(customerId, month, startDate, endDate, templateId);
    }

    /**
     * 采购入库单渲染
     */
    @SaCheckPermission("erp:print:render")
    @GetMapping(value = "/PURCHASE_IN/{purchaseId}", produces = MediaType.TEXT_HTML_VALUE)
    public String purchaseIn(@PathVariable Long purchaseId,
                             @RequestParam(required = false) Long templateId) {
        return printService.renderPurchaseIn(purchaseId, templateId);
    }

    /**
     * 收款单渲染
     */
    @SaCheckPermission("erp:print:render")
    @GetMapping(value = "/RECEIPT/{receiptId}", produces = MediaType.TEXT_HTML_VALUE)
    public String receipt(@PathVariable Long receiptId,
                          @RequestParam(required = false) Long templateId) {
        return printService.renderReceipt(receiptId, templateId);
    }

    /**
     * 付款单渲染
     */
    @SaCheckPermission("erp:print:render")
    @GetMapping(value = "/PAYMENT/{paymentId}", produces = MediaType.TEXT_HTML_VALUE)
    public String payment(@PathVariable Long paymentId,
                          @RequestParam(required = false) Long templateId) {
        return printService.renderPayment(paymentId, templateId);
    }
}
