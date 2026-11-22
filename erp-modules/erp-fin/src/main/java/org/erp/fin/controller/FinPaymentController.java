package org.erp.fin.controller;

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
import org.erp.fin.domain.bo.FinPaymentBo;
import org.erp.fin.domain.vo.FinPaymentVo;
import org.erp.fin.service.IFinPaymentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 付款单
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/finance/payments")
public class FinPaymentController extends BaseController {

    private final IFinPaymentService paymentService;

    @SaCheckPermission("erp:finance:payment:list")
    @GetMapping("/list")
    public TableDataInfo<FinPaymentVo> list(@RequestParam(required = false) Long supplierId,
                                            @RequestParam(required = false) String status,
                                            PageQuery pageQuery) {
        return paymentService.selectPage(supplierId, status, pageQuery);
    }

    @SaCheckPermission("erp:finance:payment:query")
    @GetMapping("/{id}")
    public R<FinPaymentVo> getInfo(@PathVariable Long id) {
        return R.ok(paymentService.selectById(id));
    }

    @SaCheckPermission("erp:finance:payment:add")
    @Log(title = "付款单", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<FinPaymentVo> add(@Valid @RequestBody FinPaymentBo bo) {
        ConfirmPermission.check("erp:finance:payment:confirm", bo.getConfirm());
        return R.ok(paymentService.insert(bo));
    }

    @SaCheckPermission("erp:finance:payment:edit")
    @Log(title = "付款单", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<FinPaymentVo> edit(@PathVariable Long id, @Valid @RequestBody FinPaymentBo bo) {
        ConfirmPermission.check("erp:finance:payment:confirm", bo.getConfirm());
        return R.ok(paymentService.update(id, bo));
    }

    @SaCheckPermission("erp:finance:payment:confirm")
    @Log(title = "付款单确认", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        paymentService.confirm(id);
        return R.ok();
    }

    @SaCheckPermission("erp:finance:payment:withdraw")
    @Log(title = "付款单撤回", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/withdraw")
    public R<Void> withdraw(@PathVariable Long id) {
        paymentService.withdraw(id);
        return R.ok();
    }

    @SaCheckPermission("erp:finance:payment:void")
    @Log(title = "付款单作废", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/void")
    public R<Void> voidBill(@PathVariable Long id) {
        paymentService.voidBill(id);
        return R.ok();
    }

    /**
     * 删除草稿付款单（逻辑删除；仅 DRAFT 可删，单号不回收）
     *
     * @param id 付款单ID
     * @return 操作结果
     */
    @SaCheckPermission("erp:finance:payment:remove")
    @Log(title = "付款单", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        paymentService.deleteDraft(id);
        return R.ok();
    }
}
