package org.erp.master.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.erp.common.excel.ImportResult;
import org.erp.master.domain.bo.MasterOptionIdsBo;
import org.erp.master.domain.bo.MasterOptionQuery;
import org.erp.master.domain.vo.MasterOptionPageVo;
import org.erp.master.service.MasterOptionService;
import org.erp.master.domain.bo.ErpSupplierBo;
import org.erp.master.domain.vo.ErpSupplierVo;
import org.erp.master.excel.SupplierExcelService;
import org.erp.master.service.IErpSupplierService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 供应商资料
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/master/suppliers")
public class ErpSupplierController extends BaseController {

    private final IErpSupplierService supplierService;
    private final SupplierExcelService excelService;
    private final MasterOptionService optionService;

    @SaCheckPermission("erp:master:supplier:list")
    @GetMapping("/list")
    public TableDataInfo<ErpSupplierVo> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer status,
                                             PageQuery pageQuery) {
        return supplierService.selectPage(keyword, status, pageQuery);
    }

    @SaCheckPermission("erp:master:supplier:query")
    @GetMapping("/{id}")
    public R<ErpSupplierVo> getInfo(@PathVariable Long id) {
        return R.ok(supplierService.selectById(id));
    }

    @SaCheckPermission("erp:master:supplier:query")
    @GetMapping("/options")
    public R<MasterOptionPageVo<ErpSupplierVo>> options(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String code,
                                                       @RequestParam(required = false) Integer status,
                                                       @RequestParam int pageNum,
                                                       @RequestParam(defaultValue = MasterOptionQuery.DEFAULT_SIZE) int pageSize) {
        return R.ok(optionService.suppliers(MasterOptionQuery.of(keyword, code, status, pageNum, pageSize)));
    }

    @SaCheckPermission("erp:master:supplier:query")
    @PostMapping("/options/by-ids")
    public R<List<ErpSupplierVo>> optionsByIds(@RequestBody MasterOptionIdsBo request) {
        return R.ok(optionService.suppliersByIds(request.ids()));
    }

    @SaCheckPermission("erp:master:supplier:add")
    @Log(title = "供应商资料", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Valid @RequestBody ErpSupplierBo bo) {
        supplierService.insert(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:master:supplier:edit")
    @Log(title = "供应商资料", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<Void> edit(@PathVariable Long id, @Valid @RequestBody ErpSupplierBo bo) {
        bo.setId(id);
        supplierService.update(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:master:supplier:remove")
    @Log(title = "供应商资料", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable List<Long> ids) {
        supplierService.deleteByIds(ids);
        return R.ok();
    }

    /**
     * 导出当前筛选条件下的供应商
     */
    @SaCheckPermission("erp:master:supplier:export")
    @Log(title = "供应商资料", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer status,
                       HttpServletResponse response) {
        excelService.export(keyword, status, response);
    }

    /**
     * 导入供应商资料（按编码 upsert）
     */
    @SaCheckPermission("erp:master:supplier:import")
    @Log(title = "供应商资料", businessType = BusinessType.IMPORT)
    @PostMapping(value = "/importData", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ImportResult> importData(@RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "false") boolean updateSupport) throws IOException {
        try {
            return R.ok(excelService.importData(file, updateSupport));
        } catch (SupplierExcelService.ImportRollbackException e) {
            return R.ok(e.getResult());
        }
    }

    /**
     * 下载供应商导入模板
     */
    @SaCheckPermission("erp:master:supplier:import")
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        excelService.importTemplate(response);
    }
}
