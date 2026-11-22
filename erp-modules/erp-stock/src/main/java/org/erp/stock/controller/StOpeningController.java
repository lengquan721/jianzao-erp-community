package org.erp.stock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.erp.common.excel.ImportResult;
import org.erp.stock.domain.bo.StOpeningBo;
import org.erp.stock.domain.bo.StockPage;
import org.erp.stock.domain.vo.StOpeningVo;
import org.erp.stock.excel.StOpeningExcelService;
import org.erp.stock.service.IStOpeningService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 期初库存
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/stock/opening")
public class StOpeningController extends BaseController {

    private final IStOpeningService openingService;
    private final StOpeningExcelService excelService;

    @SaCheckPermission("erp:stock:opening")
    @GetMapping("/list")
    public TableDataInfo<StOpeningVo> list(@RequestParam(required = false) Long warehouseId,
                                           @RequestParam int pageNum,
                                           @RequestParam(defaultValue = StockPage.DEFAULT_SIZE) int pageSize) {
        return openingService.pageDetails(warehouseId, pageNum, pageSize);
    }

    @SaCheckPermission("erp:stock:opening")
    @Log(title = "期初库存录入", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<StOpeningVo> save(@Valid @RequestBody StOpeningBo bo) {
        return R.ok(openingService.save(bo));
    }

    @SaCheckPermission("erp:stock:opening")
    @Log(title = "期初库存确认", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        openingService.confirm(id);
        return R.ok();
    }

    @SaCheckPermission("erp:stock:opening")
    @Log(title = "期初库存反确认", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/unconfirm")
    public R<Void> unconfirm(@PathVariable Long id) {
        openingService.unconfirm(id);
        return R.ok();
    }

    /**
     * 删除草稿期初库存：仅 DRAFT 可删，逻辑删除（del_flag=1），不影响库存与成本。
     *
     * @param id 期初行ID
     * @return 操作结果
     */
    @SaCheckPermission("erp:stock:opening:remove")
    @Log(title = "期初库存", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        openingService.deleteDraft(id);
        return R.ok();
    }

    /**
     * 导出过滤后的期初库存，与 UI 页码无关，超过 5000 条拒绝。
     */
    @SaCheckPermission("erp:stock:opening:export")
    @Log(title = "期初库存导出", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(@RequestParam(required = false) Long warehouseId, HttpServletResponse response) {
        excelService.export(warehouseId, response);
    }

    /**
     * 导入期初库存；仅保存 DRAFT，不自动确认
     */
    @SaCheckPermission("erp:stock:opening:import")
    @Log(title = "期初库存", businessType = BusinessType.IMPORT)
    @PostMapping(value = "/importData", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ImportResult> importData(@RequestPart("file") MultipartFile file) throws IOException {
        try {
            return R.ok(excelService.importData(file));
        } catch (StOpeningExcelService.ImportRollbackException e) {
            return R.ok(e.getResult());
        }
    }

    /**
     * 下载期初库存导入模板
     */
    @SaCheckPermission("erp:stock:opening:import")
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        excelService.importTemplate(response);
    }
}
