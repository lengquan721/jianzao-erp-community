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
import org.erp.fin.domain.bo.FinOtherFlowBo;
import org.erp.fin.domain.vo.FinOtherFlowVo;
import org.erp.fin.service.IFinOtherFlowService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 其他收支
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/finance/other-flows")
public class FinOtherFlowController extends BaseController {

    private final IFinOtherFlowService flowService;

    @SaCheckPermission("erp:finance:other-flow:list")
    @GetMapping("/list")
    public TableDataInfo<FinOtherFlowVo> list(@RequestParam(required = false) String flowType,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String status,
                                              PageQuery pageQuery) {
        return flowService.selectPage(flowType, category, status, pageQuery);
    }

    @SaCheckPermission("erp:finance:other-flow:query")
    @GetMapping("/{id}")
    public R<FinOtherFlowVo> getInfo(@PathVariable Long id) {
        return R.ok(flowService.selectById(id));
    }

    @SaCheckPermission("erp:finance:other-flow:add")
    @Log(title = "其他收支", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<FinOtherFlowVo> add(@Valid @RequestBody FinOtherFlowBo bo) {
        ConfirmPermission.check("erp:finance:other-flow:confirm", bo.getConfirm());
        return R.ok(flowService.insert(bo));
    }

    @SaCheckPermission("erp:finance:other-flow:edit")
    @Log(title = "其他收支", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<FinOtherFlowVo> edit(@PathVariable Long id, @Valid @RequestBody FinOtherFlowBo bo) {
        ConfirmPermission.check("erp:finance:other-flow:confirm", bo.getConfirm());
        return R.ok(flowService.update(id, bo));
    }

    @SaCheckPermission("erp:finance:other-flow:confirm")
    @Log(title = "其他收支确认", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        flowService.confirm(id);
        return R.ok();
    }

    @SaCheckPermission("erp:finance:other-flow:withdraw")
    @Log(title = "其他收支撤回", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/withdraw")
    public R<Void> withdraw(@PathVariable Long id) {
        flowService.withdraw(id);
        return R.ok();
    }

    @SaCheckPermission("erp:finance:other-flow:void")
    @Log(title = "其他收支作废", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/void")
    public R<Void> voidBill(@PathVariable Long id) {
        flowService.voidBill(id);
        return R.ok();
    }

    /**
     * 删除草稿其他收支单（逻辑删除；仅 DRAFT 可删，单号不回收）
     *
     * @param id 其他收支单ID
     * @return 操作结果
     */
    @SaCheckPermission("erp:finance:other-flow:remove")
    @Log(title = "其他收支", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        flowService.deleteDraft(id);
        return R.ok();
    }
}
