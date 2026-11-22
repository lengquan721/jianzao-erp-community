package org.erp.trade.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.erp.common.security.ConfirmPermission;
import org.erp.trade.domain.bo.ErpSaleReturnBo;
import org.erp.trade.domain.vo.ErpSaleReturnVo;
import org.erp.trade.service.IErpSaleReturnService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 销售退货（DOC/24 §3.1）
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/sale-returns")
public class ErpSaleReturnController extends BaseController {

    private final IErpSaleReturnService returnService;

    @SaCheckPermission("erp:sale-return:list")
    @GetMapping("/list")
    public TableDataInfo<ErpSaleReturnVo> list(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long customerId,
                                               @RequestParam(required = false) String status,
                                               PageQuery pageQuery) {
        return returnService.selectPage(keyword, customerId, status, pageQuery);
    }

    @SaCheckPermission("erp:sale-return:query")
    @GetMapping("/{id}")
    public R<ErpSaleReturnVo> getInfo(@PathVariable Long id) {
        return R.ok(returnService.selectById(id));
    }

    @SaCheckPermission("erp:sale-return:list")
    @GetMapping("/returnable-orders")
    public R<List<ErpSaleReturnVo>> returnableOrders(@RequestParam(required = false) Long customerId) {
        return R.ok(returnService.returnableOrders(customerId));
    }

    @SaCheckPermission("erp:sale-return:list")
    @GetMapping("/original-items/{saleId}")
    public R<List<ErpSaleReturnVo.ItemVo>> originalItems(@PathVariable Long saleId) {
        return R.ok(returnService.originalItems(saleId));
    }

    @SaCheckPermission("erp:sale-return:add")
    @Log(title = "销售退货", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<ErpSaleReturnVo> add(@Valid @RequestBody ErpSaleReturnBo bo) {
        ConfirmPermission.check("erp:sale-return:audit", bo.getConfirm());
        ConfirmPermission.check("erp:finance:receipt:confirm", Boolean.TRUE.equals(bo.getConfirm())
            && Boolean.TRUE.equals(bo.getNeedRefund()) && bo.getRefundAmount() != null
            && bo.getRefundAmount().signum() > 0);
        return R.ok(returnService.insert(bo));
    }

    @SaCheckPermission("erp:sale-return:edit")
    @Log(title = "销售退货", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<ErpSaleReturnVo> edit(@PathVariable Long id, @Valid @RequestBody ErpSaleReturnBo bo) {
        ConfirmPermission.check("erp:sale-return:audit", bo.getConfirm());
        ConfirmPermission.check("erp:finance:receipt:confirm", Boolean.TRUE.equals(bo.getConfirm())
            && Boolean.TRUE.equals(bo.getNeedRefund()) && bo.getRefundAmount() != null
            && bo.getRefundAmount().signum() > 0);
        return R.ok(returnService.update(id, bo));
    }

    @SaCheckPermission("erp:sale-return:audit")
    @Log(title = "销售退货确认", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        returnService.confirm(id);
        return R.ok();
    }

    @SaCheckPermission("erp:sale-return:withdraw")
    @Log(title = "销售退货撤回", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/withdraw")
    public R<Void> withdraw(@PathVariable Long id) {
        returnService.withdraw(id);
        return R.ok();
    }

    @SaCheckPermission("erp:sale-return:void")
    @Log(title = "销售退货作废", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/void")
    public R<Void> voidBill(@PathVariable Long id) {
        returnService.voidBill(id);
        return R.ok();
    }

    /**
     * 删除草稿销售退货单：仅 DRAFT 可删，主表与明细均逻辑删除，单号不回收。
     *
     * @param id 销售退货单ID
     * @return 操作结果
     */
    @SaCheckPermission("erp:sale-return:remove")
    @Log(title = "销售退货", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        returnService.deleteDraft(id);
        return R.ok();
    }
}
