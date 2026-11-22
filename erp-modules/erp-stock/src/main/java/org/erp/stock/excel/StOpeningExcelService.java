package org.erp.stock.excel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.excel.utils.ExcelUtil;
import org.erp.common.excel.CellValues;
import org.erp.common.excel.ExcelRow;
import org.erp.common.excel.ExcelRowReader;
import org.erp.common.excel.ImportResult;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpWarehouse;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpWarehouseMapper;
import org.erp.stock.domain.bo.StOpeningBo;
import org.erp.stock.domain.vo.StOpeningVo;
import org.erp.stock.service.IStOpeningService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 期初库存导入导出（DOC/22 §4）。
 *
 * 导入规则：
 * - 先解析并校验全部行，任意行失败则整批不落库；
 * - 校验通过后在一个事务内逐行调用 {@link IStOpeningService#save}，只保存 DRAFT；
 * - 已确认行（40902）会标记整批回滚，错误行定位到 Excel 行号；
 * - 单文件最多 2000 行。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class StOpeningExcelService {

    /** 单文件行数上限（DOC/22 §4.2）。 */
    static final int MAX_ROWS = 2000;

    private static final String SHEET_NAME = "期初库存";

    private final IStOpeningService openingService;
    private final ErpProductMapper productMapper;
    private final ErpWarehouseMapper warehouseMapper;

    /**
     * 导出当前筛选条件下的全部期初库存。
     *
     * @param warehouseId 仓库 ID，可选
     */
    public void export(Long warehouseId, HttpServletResponse response) {
        List<StOpeningVo> rows = openingService.listForExport(warehouseId);
        ExcelUtil.exportExcel(toExportRows(rows), SHEET_NAME, StOpeningExportVo.class, response);
    }

    /**
     * 下载空白导入模板（表头与导入解析保持一致）。
     */
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(new ArrayList<>(), SHEET_NAME, StOpeningImportRow.class, response);
    }

    /**
     * 解析并导入期初库存。
     *
     * 任何失败都抛出 {@link ImportRollbackException} 让代理事务回滚，由 Controller
     * 捕获后返回带逐行错误的 {@link ImportResult}，避免 rollback-only 事务在提交时
     * 抛 UnexpectedRollbackException。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importData(MultipartFile file) throws IOException {
        List<ExcelRow<StOpeningImportRow>> rows;
        try (InputStream inputStream = file.getInputStream()) {
            rows = ExcelRowReader.read(inputStream, StOpeningImportRow.class);
        }
        ImportResult result = importRows(rows);
        if (result.getFailed() > 0) {
            throw new ImportRollbackException(result);
        }
        return result;
    }

    /**
     * 校验并保存数据行。包级可见，便于不启动 Spring 事务直接单测。
     *
     * @param rows Excel 数据行（含原始行号）
     */
    ImportResult importRows(List<ExcelRow<StOpeningImportRow>> rows) {
        ImportResult result = new ImportResult();
        if (rows.size() > MAX_ROWS) {
            result.failAll(rows.size(), "单次最多导入" + MAX_ROWS + "行，请拆分文件后重试");
            return result;
        }

        List<StOpeningBo> validBos = new ArrayList<>();
        List<ExcelRow<StOpeningImportRow>> validRows = new ArrayList<>();
        Map<String, ErpProduct> productCache = new HashMap<>();
        Map<String, ErpWarehouse> warehouseCache = new HashMap<>();
        boolean hasError = false;

        for (ExcelRow<StOpeningImportRow> excelRow : rows) {
            StOpeningBo bo = parseRow(excelRow, productCache, warehouseCache, result);
            if (bo == null) {
                hasError = true;
                continue;
            }
            validBos.add(bo);
            validRows.add(excelRow);
        }

        // 任一行校验失败则整批不落库（DOC/22 §3.2/§4.2）
        if (hasError) {
            return result;
        }

        for (int i = 0; i < validBos.size(); i++) {
            try {
                // save 为 upsert，且只写 DRAFT；重复导入即更新
                openingService.save(validBos.get(i));
            } catch (ServiceException e) {
                markRollbackOnly();
                // 整批回滚：已处理行不计成功，未处理行统一标记跳过
                for (int j = 0; j < i; j++) {
                    result.skipped();
                }
                String message = e.getMessage() == null ? "保存失败" : e.getMessage();
                result.fail(validRows.get(i).rowNumber(), message + "（整批已回滚）");
                for (int j = i + 1; j < validRows.size(); j++) {
                    result.skipped();
                }
                log.warn("期初库存导入中止，行 {}：{}", validRows.get(i).rowNumber(), message);
                return result;
            }
        }

        for (int i = 0; i < validBos.size(); i++) {
            result.success();
        }
        return result;
    }

    private ErpProduct findProduct(String code, Map<String, ErpProduct> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        ErpProduct product = productMapper.selectOne(new LambdaQueryWrapper<ErpProduct>()
            .eq(ErpProduct::getCode, code));
        cache.put(code, product);
        return product;
    }

    private ErpWarehouse findWarehouse(String code, Map<String, ErpWarehouse> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        ErpWarehouse warehouse = warehouseMapper.selectOne(new LambdaQueryWrapper<ErpWarehouse>()
            .eq(ErpWarehouse::getCode, code));
        cache.put(code, warehouse);
        return warehouse;
    }

    /**
     * 名称由有界导出查询联查，避免每仓各查一次。金额 = 期初数量 × 期初成本。
     */
    List<StOpeningExportVo> toExportRows(List<StOpeningVo> rows) {
        List<StOpeningExportVo> result = new ArrayList<>(rows.size());
        for (StOpeningVo row : rows) {
            StOpeningExportVo export = new StOpeningExportVo();
            export.setProductCode(row.getProductCode());
            export.setProductName(row.getProductName());
            export.setWarehouseCode(row.getWarehouseCode());
            export.setWarehouseName(row.getWarehouseName());
            export.setQty(row.getQty());
            export.setUnitCost(row.getUnitCost());

            BigDecimal qty = row.getQty() == null ? BigDecimal.ZERO : row.getQty();
            BigDecimal unitCost = row.getUnitCost() == null ? BigDecimal.ZERO : row.getUnitCost();
            export.setAmount(qty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP));
            result.add(export);
        }
        return result;
    }

    /**
     * 标记当前事务回滚。单测无事务上下文时跳过，生产环境由 @Transactional 保证生效。
     */
    private void markRollbackOnly() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    /**
     * 解析并校验单行数据：数量/成本、产品/仓库编码、成本锚点（DOC/26 §2.4）。
     * 有错误时直接把行号与原因写入导入结果。
     *
     * @param excelRow       Excel 数据行（含原始行号）
     * @param productCache   产品编码缓存
     * @param warehouseCache 仓库编码缓存
     * @param result         导入结果（失败行在此记录）
     * @return 校验通过的 Bo；本行存在错误时返回 null
     */
    private StOpeningBo parseRow(ExcelRow<StOpeningImportRow> excelRow, Map<String, ErpProduct> productCache,
                                 Map<String, ErpWarehouse> warehouseCache, ImportResult result) {
        StOpeningImportRow data = excelRow.data();
        List<String> errors = new ArrayList<>();

        String productCode = CellValues.required(data.getProductCode(), "产品编码", 32, errors);
        String warehouseCode = CellValues.required(data.getWarehouseCode(), "仓库编码", 32, errors);
        BigDecimal qty = CellValues.decimal(data.getQty(), "期初数量", 3, true, errors);
        BigDecimal unitCost = CellValues.decimal(data.getUnitCost(), "期初成本", 4, true, errors);
        if (qty != null && qty.signum() < 0) {
            errors.add("期初数量不能为负");
        }
        if (unitCost != null && unitCost.signum() < 0) {
            errors.add("期初成本不能为负");
        }

        ErpProduct product = null;
        if (productCode != null) {
            product = findProduct(productCode, productCache);
            if (product == null) {
                errors.add("产品编码不存在：" + productCode);
            }
        }
        ErpWarehouse warehouse = null;
        if (warehouseCode != null) {
            warehouse = findWarehouse(warehouseCode, warehouseCache);
            if (warehouse == null) {
                errors.add("仓库编码不存在：" + warehouseCode);
            }
        }
        // 成本锚点校验：有数量的行必须有成本，否则导入后 avg_cost=0，出库成本全为 0
        if (product != null && qty != null && qty.signum() > 0 && unitCost != null && unitCost.signum() <= 0) {
            errors.add("商品「" + product.getName() + "」期初成本必须大于 0");
        }

        if (!errors.isEmpty()) {
            result.fail(excelRow.rowNumber(), String.join("；", errors));
            return null;
        }
        StOpeningBo bo = new StOpeningBo();
        bo.setProductId(product.getId());
        bo.setWarehouseId(warehouse.getId());
        bo.setQty(qty);
        bo.setUnitCost(unitCost);
        return bo;
    }

    /**
     * 整批回滚信号：携带逐行导入结果，Controller 捕获后作为正常响应返回。
     */
    public static class ImportRollbackException extends RuntimeException {

        private final ImportResult result;

        public ImportRollbackException(ImportResult result) {
            super("期初库存导入失败，整批已回滚");
            this.result = result;
        }

        public ImportResult getResult() {
            return result;
        }
    }
}
