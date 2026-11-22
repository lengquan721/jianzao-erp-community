package org.erp.master.excel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.erp.common.excel.CellValues;
import org.erp.common.excel.ExcelRow;
import org.erp.common.excel.ExcelRowReader;
import org.erp.common.excel.ImportResult;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.domain.bo.ErpSupplierBo;
import org.erp.master.domain.vo.ErpSupplierVo;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.service.IErpSupplierService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 供应商资料导入导出（DOC/22 §3）。
 *
 * <p>规则与客户一致；期初应付不参与导入。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SupplierExcelService {

    /** 单文件行数上限（DOC/22 §3.2）。 */
    static final int MAX_ROWS = 2000;

    /** 导出上限（DOC/22 §3.3）。 */
    static final int EXPORT_LIMIT = 5000;

    private static final String SHEET_NAME = "供应商资料";
    private static final BigDecimal MAX_TAX_RATE = new BigDecimal("100");

    private final IErpSupplierService supplierService;
    private final ErpSupplierMapper supplierMapper;

    /**
     * 导出当前筛选条件下的全部供应商（最多 {@value #EXPORT_LIMIT} 行）。
     */
    public void export(String keyword, Integer status, HttpServletResponse response) {
        ExcelUtil.exportExcel(exportRows(keyword, status), SHEET_NAME, SupplierExportVo.class, response);
    }

    /**
     * 下载空白导入模板（表头与导入解析保持一致）。
     */
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(new ArrayList<>(), SHEET_NAME, SupplierExportVo.class, response);
    }

    /**
     * 解析并导入供应商资料。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importData(MultipartFile file, boolean updateSupport) throws IOException {
        List<ExcelRow<SupplierImportRow>> rows;
        try (InputStream inputStream = file.getInputStream()) {
            rows = ExcelRowReader.read(inputStream, SupplierImportRow.class);
        } catch (ExcelRowReader.ImportLimitException e) {
            ImportResult rejected = new ImportResult();
            rejected.fail(e.rowNumber(), e.getMessage());
            throw new ImportRollbackException(rejected);
        }
        ImportResult result = importRows(rows, updateSupport);
        if (result.getFailed() > 0) {
            throw new ImportRollbackException(result);
        }
        return result;
    }

    /**
     * 校验并保存数据行。包级可见，便于不启动 Spring 事务直接单测。
     */
    ImportResult importRows(List<ExcelRow<SupplierImportRow>> rows, boolean updateSupport) {
        ImportResult result = new ImportResult();
        if (rows.size() > MAX_ROWS) {
            result.failAll(rows.size(), "单次最多导入" + MAX_ROWS + "行，请拆分文件后重试");
            return result;
        }

        List<ErpSupplierBo> validBos = new ArrayList<>();
        List<ExcelRow<SupplierImportRow>> validRows = new ArrayList<>();
        List<Long> existingIds = new ArrayList<>();
        Map<String, ErpSupplier> activeCache = new HashMap<>();
        Map<String, Long> deletedCache = new HashMap<>();
        Set<String> fileCodes = new HashSet<>();
        boolean hasError = false;

        for (ExcelRow<SupplierImportRow> excelRow : rows) {
            SupplierImportRow data = excelRow.data();
            List<String> errors = new ArrayList<>();

            String code = CellValues.optional(data.getCode(), "供应商编码", 32, errors);
            code = CellValues.blank(code) ? null : code;
            String name = CellValues.required(data.getName(), "供应商名称", 100, errors);
            String contact = CellValues.optional(data.getContact(), "联系人", 50, errors);
            String phone = CellValues.optional(data.getPhone(), "电话", 30, errors);
            String address = CellValues.optional(data.getAddress(), "地址", 200, errors);
            String taxNo = CellValues.optional(data.getTaxNo(), "税号", 50, errors);
            BigDecimal taxRate = CellValues.decimal(data.getTaxRate(), "默认税率", 8, 4, false, errors);
            Integer status = CellValues.status(data.getStatus(), errors);
            String remark = CellValues.optional(data.getRemark(), "备注", 500, errors);

            if (taxRate != null && (taxRate.signum() < 0 || taxRate.compareTo(MAX_TAX_RATE) > 0)) {
                errors.add("默认税率必须在0~100之间");
            }

            Long existingId = null;
            if (code != null) {
                if (!fileCodes.add(code)) {
                    errors.add("文件内供应商编码重复：" + code);
                }
                ErpSupplier existing = findActive(code, activeCache);
                if (existing != null) {
                    existingId = existing.getId();
                } else if (findDeletedId(code, deletedCache) != null) {
                    errors.add("编码已被删除记录占用，请恢复或改用其他编码：" + code);
                }
            }

            if (!errors.isEmpty()) {
                result.fail(excelRow.rowNumber(), String.join("；", errors));
                hasError = true;
                continue;
            }

            ErpSupplierBo bo = new ErpSupplierBo();
            bo.setCode(code);
            bo.setName(name);
            bo.setContact(contact);
            bo.setPhone(phone);
            bo.setAddress(address);
            bo.setTaxNo(taxNo);
            bo.setTaxRate(taxRate);
            bo.setStatus(status);
            bo.setRemark(remark);

            validBos.add(bo);
            validRows.add(excelRow);
            existingIds.add(existingId);
        }

        // 任一行校验失败则整批不落库（DOC/22 §3.2），合法行计为未导入
        if (hasError) {
            for (int i = 0; i < validRows.size(); i++) {
                result.skipped();
            }
            return result;
        }

        List<ErpSupplierBo> writeBos = new ArrayList<>();
        List<ExcelRow<SupplierImportRow>> writeRows = new ArrayList<>();
        for (int i = 0; i < validBos.size(); i++) {
            Long existingId = existingIds.get(i);
            if (existingId != null && !updateSupport) {
                result.skipped();
                continue;
            }
            if (existingId != null) {
                validBos.get(i).setId(existingId);
            }
            writeBos.add(validBos.get(i));
            writeRows.add(validRows.get(i));
        }

        for (int i = 0; i < writeBos.size(); i++) {
            ErpSupplierBo bo = writeBos.get(i);
            try {
                if (bo.getId() == null) {
                    supplierService.insert(bo);
                } else {
                    supplierService.update(bo);
                }
            } catch (ServiceException e) {
                markRollbackOnly();
                for (int j = 0; j < i; j++) {
                    result.skipped();
                }
                String message = e.getMessage() == null ? "保存失败" : e.getMessage();
                result.fail(writeRows.get(i).rowNumber(), message + "（整批已回滚）");
                for (int j = i + 1; j < writeBos.size(); j++) {
                    result.skipped();
                }
                log.warn("供应商资料导入中止，行 {}：{}", writeRows.get(i).rowNumber(), message);
                return result;
            }
        }

        for (int i = 0; i < writeBos.size(); i++) {
            result.success();
        }
        return result;
    }

    /**
     * 导出映射：状态转为中文，不输出 id/tenantId/delFlag/期初应付。
     */
    List<SupplierExportVo> exportRows(String keyword, Integer status) {
        List<ErpSupplierVo> rows = PageQuery.collectForExport(EXPORT_LIMIT, query ->
            supplierService.selectPage(keyword, status, query).getRows());
        List<SupplierExportVo> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (ErpSupplierVo row : rows) {
            SupplierExportVo export = new SupplierExportVo();
            export.setCode(row.getCode());
            export.setName(row.getName());
            export.setContact(row.getContact());
            export.setPhone(row.getPhone());
            export.setAddress(row.getAddress());
            export.setTaxNo(row.getTaxNo());
            export.setTaxRate(row.getTaxRate());
            export.setStatus(statusLabel(row.getStatus()));
            export.setRemark(row.getRemark());
            result.add(export);
        }
        return result;
    }

    private ErpSupplier findActive(String code, Map<String, ErpSupplier> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        ErpSupplier row = supplierMapper.selectOne(new LambdaQueryWrapper<ErpSupplier>()
            .eq(ErpSupplier::getCode, code));
        cache.put(code, row);
        return row;
    }

    private Long findDeletedId(String code, Map<String, Long> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        Long id = supplierMapper.selectDeletedIdByCode(code);
        id = id == null || id <= 0 ? null : id;
        cache.put(code, id);
        return id;
    }

    static String statusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        return status == 1 ? "启用" : "停用";
    }

    private void markRollbackOnly() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    /**
     * 整批回滚信号：携带逐行导入结果，Controller 捕获后作为正常响应返回。
     */
    public static class ImportRollbackException extends RuntimeException {

        private final ImportResult result;

        public ImportRollbackException(ImportResult result) {
            super("供应商资料导入失败，整批已回滚");
            this.result = result;
        }

        public ImportResult getResult() {
            return result;
        }
    }
}
