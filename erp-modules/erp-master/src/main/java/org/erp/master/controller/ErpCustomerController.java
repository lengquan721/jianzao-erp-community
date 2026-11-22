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
import org.erp.master.domain.bo.ErpCustomerBo;
import org.erp.master.domain.vo.ErpCustomerVo;
import org.erp.master.excel.CustomerExcelService;
import org.erp.master.service.IErpCustomerService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 客户资料
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/master/customers")
public class ErpCustomerController extends BaseController {

    private final IErpCustomerService customerService;
    private final CustomerExcelService excelService;
    private final MasterOptionService optionService;

    @SaCheckPermission("erp:master:customer:list")
    @GetMapping("/list")
    public TableDataInfo<ErpCustomerVo> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer status,
                                             PageQuery pageQuery) {
        return customerService.selectPage(keyword, status, pageQuery);
    }

    @SaCheckPermission("erp:master:customer:query")
    @GetMapping("/{id}")
    public R<ErpCustomerVo> getInfo(@PathVariable Long id) {
        return R.ok(customerService.selectById(id));
    }

    @SaCheckPermission("erp:master:customer:query")
    @GetMapping("/options")
    public R<MasterOptionPageVo<ErpCustomerVo>> options(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String code,
                                                       @RequestParam(required = false) Integer status,
                                                       @RequestParam int pageNum,
                                                       @RequestParam(defaultValue = MasterOptionQuery.DEFAULT_SIZE) int pageSize) {
        return R.ok(optionService.customers(MasterOptionQuery.of(keyword, code, status, pageNum, pageSize)));
    }

    @SaCheckPermission("erp:master:customer:query")
    @PostMapping("/options/by-ids")
    public R<List<ErpCustomerVo>> optionsByIds(@RequestBody MasterOptionIdsBo request) {
        return R.ok(optionService.customersByIds(request.ids()));
    }

    @SaCheckPermission("erp:master:customer:add")
    @Log(title = "客户资料", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Valid @RequestBody ErpCustomerBo bo) {
        customerService.insert(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:master:customer:edit")
    @Log(title = "客户资料", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{id}")
    public R<Void> edit(@PathVariable Long id, @Valid @RequestBody ErpCustomerBo bo) {
        bo.setId(id);
        customerService.update(bo);
        return R.ok();
    }

    @SaCheckPermission("erp:master:customer:remove")
    @Log(title = "客户资料", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable List<Long> ids) {
        customerService.deleteByIds(ids);
        return R.ok();
    }

    /**
     * 导出当前筛选条件下的客户
     */
    @SaCheckPermission("erp:master:customer:export")
    @Log(title = "客户资料", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer status,
                       HttpServletResponse response) {
        excelService.export(keyword, status, response);
    }

    /**
     * 导入客户资料（按编码 upsert）
     */
    @SaCheckPermission("erp:master:customer:import")
    @Log(title = "客户资料", businessType = BusinessType.IMPORT)
    @PostMapping(value = "/importData", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ImportResult> importData(@RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "false") boolean updateSupport) throws IOException {
        try {
            return R.ok(excelService.importData(file, updateSupport));
        } catch (CustomerExcelService.ImportRollbackException e) {
            return R.ok(e.getResult());
        }
    }

    /**
     * 下载客户导入模板
     */
    @SaCheckPermission("erp:master:customer:import")
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        excelService.importTemplate(response);
    }
}
