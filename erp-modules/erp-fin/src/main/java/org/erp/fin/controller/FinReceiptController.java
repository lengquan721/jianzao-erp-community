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
import org.erp.fin.domain.bo.FinReceiptBo;
import org.erp.fin.domain.vo.FinReceiptVo;
import org.erp.fin.service.IFinReceiptService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 收款单
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/finance/receipts")
public class FinReceiptController extends BaseController {

    private final IFinReceiptService receiptService;

    @SaCheckPermission("erp:finance:receipt:list")
    @GetMapping("/list")
    public TableDataInfo<FinReceiptVo> list(@RequestParam(required = false) Long customerId,
                                            @RequestParam(required = false) String status,
                                            PageQuery pageQuery) {
        return receiptService.selectPage(customerId, status, pageQuery);
    }

    @SaCheckPermission("erp:finance:receipt:query")
    @GetMapping("/{id}")
    public R<FinReceiptVo> getInfo(@PathVariable Long id) {
        return R.ok(receiptService.selectById(id));
    }

    @SaCheckPermission("erp:finance:receipt:add")
    @Log(title = "收款单", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<FinReceiptVo> add(@Valid @RequestBody FinReceiptBo bo) {
        ConfirmPermission.check("erp:finance:receipt:confirm", bo.getConfirm());
        return R.ok(receiptService.insert(bo));
    }

    @SaCheckPermission("erp:finance:receipt:edit")
    @Log(title = "收款单", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<FinReceiptVo> edit(@PathVariable Long id, @Valid @RequestBody FinReceiptBo bo) {
        ConfirmPermission.check("erp:finance:receipt:confirm", bo.getConfirm());
        return R.ok(receiptService.update(id, bo));
    }

    @SaCheckPermission("erp:finance:receipt:confirm")
    @Log(title = "收款单确认", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        receiptService.confirm(id);
        return R.ok();
    }

    @SaCheckPermission("erp:finance:receipt:withdraw")
    @Log(title = "收款单撤回", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/withdraw")
    public R<Void> withdraw(@PathVariable Long id) {
        receiptService.withdraw(id);
        return R.ok();
    }

    @SaCheckPermission("erp:finance:receipt:void")
    @Log(title = "收款单作废", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/void")
    public R<Void> voidBill(@PathVariable Long id) {
        receiptService.voidBill(id);
        return R.ok();
    }

    /**
     * 删除草稿收款单（逻辑删除；仅 DRAFT 可删，单号不回收）
     *
     * @param id 收款单ID
     * @return 操作结果
     */
    @SaCheckPermission("erp:finance:receipt:remove")
    @Log(title = "收款单", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        receiptService.deleteDraft(id);
        return R.ok();
    }
}
