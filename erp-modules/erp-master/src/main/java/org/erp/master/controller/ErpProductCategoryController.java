package org.erp.master.controller;

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
import org.erp.master.domain.bo.MasterOptionIdsBo;
import org.erp.master.domain.bo.MasterOptionQuery;
import org.erp.master.domain.vo.MasterOptionPageVo;
import org.erp.master.service.MasterOptionService;
import org.erp.master.domain.bo.ErpProductCategoryBo;
import org.erp.master.domain.vo.ErpProductCategoryVo;
import org.erp.master.service.IErpProductCategoryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品分类（维护入口内嵌商品资料页，DOC/26 §4.2）
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/master/product-categories")
public class ErpProductCategoryController extends BaseController {

    private final IErpProductCategoryService productCategoryService;
    private final MasterOptionService optionService;

    @SaCheckPermission("erp:product-category:list")
    @GetMapping("/list")
    public TableDataInfo<ErpProductCategoryVo> list(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Integer status,
                                                    PageQuery pageQuery) {
        return productCategoryService.selectPage(keyword, status, pageQuery);
    }

    /**
     * 分类远程下拉：按页返回未删除分类，默认仍含停用，调用方继续翻页而不是截断。
     * 商品列表筛选需要能选到停用分类（存量商品仍归该组），表单可传 status=1。
     */
    @SaCheckPermission("erp:product-category:list")
    @GetMapping("/options")
    public R<MasterOptionPageVo<ErpProductCategoryVo>> options(@RequestParam(required = false) String keyword,
                                                             @RequestParam(required = false) String code,
                                                             @RequestParam(required = false) Integer status,
                                                             @RequestParam int pageNum,
                                                             @RequestParam(defaultValue = MasterOptionQuery.DEFAULT_SIZE) int pageSize) {
        return R.ok(optionService.categories(MasterOptionQuery.of(keyword, code, status, pageNum, pageSize)));
    }

    @SaCheckPermission("erp:product-category:list")
    @PostMapping("/options/by-ids")
    public R<List<ErpProductCategoryVo>> optionsByIds(@RequestBody MasterOptionIdsBo request) {
        return R.ok(optionService.categoriesByIds(request.ids()));
    }

    @SaCheckPermission("erp:product-category:query")
    @GetMapping("/{id}")
    public R<ErpProductCategoryVo> getInfo(@PathVariable Long id) {
        return R.ok(productCategoryService.selectById(id));
    }

    @SaCheckPermission("erp:product-category:add")
    @Log(title = "商品分类", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Valid @RequestBody ErpProductCategoryBo bo) {
        productCategoryService.insert(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:product-category:edit")
    @Log(title = "商品分类", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<Void> edit(@PathVariable Long id, @Valid @RequestBody ErpProductCategoryBo bo) {
        bo.setId(id);
        productCategoryService.update(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:product-category:remove")
    @Log(title = "商品分类", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable List<Long> ids) {
        productCategoryService.deleteByIds(ids);
        return R.ok();
    }
}
