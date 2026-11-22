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
import org.erp.master.domain.bo.ErpProductBo;
import org.erp.master.domain.bo.ErpRecipeItemBo;
import org.erp.master.domain.vo.ErpProductVo;
import org.erp.master.excel.ProductExcelService;
import org.erp.master.service.IErpProductService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 产品/配比
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/master/products")
public class ErpProductController extends BaseController {

    private final IErpProductService productService;
    private final ProductExcelService excelService;
    private final MasterOptionService optionService;

    @SaCheckPermission("erp:master:product:list")
    @GetMapping("/list")
    public TableDataInfo<ErpProductVo> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) Long supplierId,
                                            PageQuery pageQuery) {
        return productService.selectPage(keyword, type, status, categoryId, supplierId, pageQuery);
    }

    @SaCheckPermission("erp:master:product:query")
    @GetMapping("/{id}")
    public R<ErpProductVo> getInfo(@PathVariable Long id) {
        return R.ok(productService.selectById(id));
    }

    @SaCheckPermission("erp:master:product:query")
    @GetMapping("/options")
    public R<MasterOptionPageVo<ErpProductVo>> options(@RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String code,
                                                      @RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) String type,
                                                      @RequestParam(required = false) Long categoryId,
                                                      @RequestParam int pageNum,
                                                      @RequestParam(defaultValue = MasterOptionQuery.DEFAULT_SIZE) int pageSize) {
        return R.ok(optionService.products(MasterOptionQuery.of(keyword, code, status, pageNum, pageSize), type, categoryId));
    }

    @SaCheckPermission("erp:master:product:query")
    @PostMapping("/options/by-ids")
    public R<List<ErpProductVo>> optionsByIds(@RequestBody MasterOptionIdsBo request) {
        return R.ok(optionService.productsByIds(request.ids()));
    }

    @SaCheckPermission("erp:master:product:add")
    @Log(title = "产品资料", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Valid @RequestBody ErpProductBo bo) {
        productService.insert(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:master:product:edit")
    @Log(title = "产品资料", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<Void> edit(@PathVariable Long id, @Valid @RequestBody ErpProductBo bo) {
        bo.setId(id);
        productService.update(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:master:product:remove")
    @Log(title = "产品资料", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable List<Long> ids) {
        productService.deleteByIds(ids);
        return R.ok();
    }

    @SaCheckPermission("erp:master:product:query")
    @GetMapping("/{id}/recipe")
    public R<List<ErpRecipeItemBo>> getRecipe(@PathVariable Long id) {
        return R.ok(productService.getRecipe(id));
    }

    @SaCheckPermission("erp:master:product:edit")
    @Log(title = "产品配比", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}/recipe")
    public R<Void> saveRecipe(@PathVariable Long id, @RequestBody List<ErpRecipeItemBo> items) {
        productService.saveRecipe(id, items);
        return R.ok();
    }

    /**
     * 导出当前筛选条件下的产品
     */
    @SaCheckPermission("erp:master:product:export")
    @Log(title = "产品资料", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String type,
                       @RequestParam(required = false) Integer status,
                       @RequestParam(required = false) Long categoryId,
                       @RequestParam(required = false) Long supplierId,
                       HttpServletResponse response) {
        excelService.export(keyword, type, status, categoryId, supplierId, response);
    }

    /**
     * 导入产品资料（按编码 upsert）
     */
    @SaCheckPermission("erp:master:product:import")
    @Log(title = "产品资料", businessType = BusinessType.IMPORT)
    @PostMapping(value = "/importData", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ImportResult> importData(@RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "false") boolean updateSupport) throws IOException {
        try {
            return R.ok(excelService.importData(file, updateSupport));
        } catch (ProductExcelService.ImportRollbackException e) {
            return R.ok(e.getResult());
        }
    }

    /**
     * 下载产品导入模板
     */
    @SaCheckPermission("erp:master:product:import")
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        excelService.importTemplate(response);
    }
}
