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
import org.erp.master.domain.ErpWarehouse;
import org.erp.master.domain.bo.ErpWarehouseBo;
import org.erp.master.domain.vo.ErpWarehouseVo;
import org.erp.master.mapper.ErpWarehouseMapper;
import org.erp.master.service.IErpWarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓库资料导入导出（DOC/22 §3）。
 *
 * <p>导入导出共用客户/供应商相同的编码 upsert 与软删除冲突规则。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class WarehouseExcelService {

    /** 单文件行数上限（DOC/22 §3.2）。 */
    static final int MAX_ROWS = 2000;

    /** 导出上限（DOC/22 §3.3）。 */
    static final int EXPORT_LIMIT = 5000;

    private static final String SHEET_NAME = "仓库资料";

    private final IErpWarehouseService warehouseService;
    private final ErpWarehouseMapper warehouseMapper;

    /**
     * 导出当前筛选条件下的全部仓库（最多 {@value #EXPORT_LIMIT} 行）。
     */
    public void export(String keyword, Integer status, HttpServletResponse response) {
        ExcelUtil.exportExcel(exportRows(keyword, status), SHEET_NAME, WarehouseExportVo.class, response);
    }

    /**
     * 下载空白导入模板（表头与导入解析保持一致）。
     */
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(new ArrayList<>(), SHEET_NAME, WarehouseExportVo.class, response);
    }

    /**
     * 解析并导入仓库资料。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importData(MultipartFile file, boolean updateSupport) throws IOException {
        List<ExcelRow<WarehouseImportRow>> rows;
        try (InputStream inputStream = file.getInputStream()) {
            rows = ExcelRowReader.read(inputStream, WarehouseImportRow.class);
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
    ImportResult importRows(List<ExcelRow<WarehouseImportRow>> rows, boolean updateSupport) {
        ImportResult result = new ImportResult();
        if (rows.size() > MAX_ROWS) {
            result.failAll(rows.size(), "单次最多导入" + MAX_ROWS + "行，请拆分文件后重试");
            return result;
        }

        List<ErpWarehouseBo> validBos = new ArrayList<>();
        List<ExcelRow<WarehouseImportRow>> validRows = new ArrayList<>();
        List<Long> existingIds = new ArrayList<>();
        Map<String, ErpWarehouse> activeCache = new HashMap<>();
        Map<String, Long> deletedCache = new HashMap<>();
        Set<String> fileCodes = new HashSet<>();
        boolean hasError = false;

        for (ExcelRow<WarehouseImportRow> excelRow : rows) {
            WarehouseImportRow data = excelRow.data();
            List<String> errors = new ArrayList<>();

            String code = CellValues.optional(data.getCode(), "仓库编码", 32, errors);
            code = CellValues.blank(code) ? null : code;
            String name = CellValues.required(data.getName(), "仓库名称", 100, errors);
            Integer status = CellValues.status(data.getStatus(), errors);
            String remark = CellValues.optional(data.getRemark(), "备注", 500, errors);

            Long existingId = null;
            if (code != null) {
                if (!fileCodes.add(code)) {
                    errors.add("文件内仓库编码重复：" + code);
                }
                ErpWarehouse existing = findActive(code, activeCache);
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

            ErpWarehouseBo bo = new ErpWarehouseBo();
            bo.setCode(code);
            bo.setName(name);
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

        List<ErpWarehouseBo> writeBos = new ArrayList<>();
        List<ExcelRow<WarehouseImportRow>> writeRows = new ArrayList<>();
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
            ErpWarehouseBo bo = writeBos.get(i);
            try {
                if (bo.getId() == null) {
                    warehouseService.insert(bo);
                } else {
                    warehouseService.update(bo);
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
                log.warn("仓库资料导入中止，行 {}：{}", writeRows.get(i).rowNumber(), message);
                return result;
            }
        }

        for (int i = 0; i < writeBos.size(); i++) {
            result.success();
        }
        return result;
    }

    /**
     * 导出映射：状态转为中文，不输出 id/tenantId/delFlag。
     */
    List<WarehouseExportVo> exportRows(String keyword, Integer status) {
        List<ErpWarehouseVo> rows = PageQuery.collectForExport(EXPORT_LIMIT, query ->
            warehouseService.selectPage(keyword, status, query).getRows());
        List<WarehouseExportVo> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (ErpWarehouseVo row : rows) {
            WarehouseExportVo export = new WarehouseExportVo();
            export.setCode(row.getCode());
            export.setName(row.getName());
            export.setStatus(statusLabel(row.getStatus()));
            export.setRemark(row.getRemark());
            result.add(export);
        }
        return result;
    }

    private ErpWarehouse findActive(String code, Map<String, ErpWarehouse> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        ErpWarehouse row = warehouseMapper.selectOne(new LambdaQueryWrapper<ErpWarehouse>()
            .eq(ErpWarehouse::getCode, code));
        cache.put(code, row);
        return row;
    }

    private Long findDeletedId(String code, Map<String, Long> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        Long id = warehouseMapper.selectDeletedIdByCode(code);
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
            super("仓库资料导入失败，整批已回滚");
            this.result = result;
        }

        public ImportResult getResult() {
            return result;
        }
    }
}
