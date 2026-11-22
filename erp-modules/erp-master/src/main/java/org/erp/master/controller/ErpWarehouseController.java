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
import org.erp.master.domain.bo.ErpWarehouseBo;
import org.erp.master.domain.vo.ErpWarehouseVo;
import org.erp.master.excel.WarehouseExcelService;
import org.erp.master.service.IErpWarehouseService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 仓库资料
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/master/warehouses")
public class ErpWarehouseController extends BaseController {

    private final IErpWarehouseService warehouseService;
    private final WarehouseExcelService excelService;
    private final MasterOptionService optionService;

    @SaCheckPermission("erp:master:warehouse:list")
    @GetMapping("/list")
    public TableDataInfo<ErpWarehouseVo> list(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer status,
                                              PageQuery pageQuery) {
        return warehouseService.selectPage(keyword, status, pageQuery);
    }

    @SaCheckPermission("erp:master:warehouse:list")
    @GetMapping("/options")
    public R<MasterOptionPageVo<ErpWarehouseVo>> options(@RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String code,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam int pageNum,
                                                        @RequestParam(defaultValue = MasterOptionQuery.DEFAULT_SIZE) int pageSize) {
        return R.ok(optionService.warehouses(MasterOptionQuery.of(keyword, code, status, pageNum, pageSize)));
    }

    @SaCheckPermission("erp:master:warehouse:list")
    @PostMapping("/options/by-ids")
    public R<List<ErpWarehouseVo>> optionsByIds(@RequestBody MasterOptionIdsBo request) {
        return R.ok(optionService.warehousesByIds(request.ids()));
    }

    @SaCheckPermission("erp:master:warehouse:add")
    @Log(title = "仓库资料", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Valid @RequestBody ErpWarehouseBo bo) {
        warehouseService.insert(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:master:warehouse:edit")
    @Log(title = "仓库资料", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<Void> edit(@PathVariable Long id, @Valid @RequestBody ErpWarehouseBo bo) {
        bo.setId(id);
        warehouseService.update(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:master:warehouse:remove")
    @Log(title = "仓库资料", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable List<Long> ids) {
        warehouseService.deleteByIds(ids);
        return R.ok();
    }

    /**
     * 导出当前筛选条件下的仓库
     */
    @SaCheckPermission("erp:master:warehouse:export")
    @Log(title = "仓库资料", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer status,
                       HttpServletResponse response) {
        excelService.export(keyword, status, response);
    }

    /**
     * 导入仓库资料（按编码 upsert）
     */
    @SaCheckPermission("erp:master:warehouse:import")
    @Log(title = "仓库资料", businessType = BusinessType.IMPORT)
    @PostMapping(value = "/importData", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ImportResult> importData(@RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "false") boolean updateSupport) throws IOException {
        try {
            return R.ok(excelService.importData(file, updateSupport));
        } catch (WarehouseExcelService.ImportRollbackException e) {
            return R.ok(e.getResult());
        }
    }

    /**
     * 下载仓库导入模板
     */
    @SaCheckPermission("erp:master:warehouse:import")
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        excelService.importTemplate(response);
    }
}
