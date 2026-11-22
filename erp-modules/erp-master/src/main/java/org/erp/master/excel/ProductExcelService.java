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
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.bo.ErpProductBo;
import org.erp.master.domain.vo.ErpProductCategoryVo;
import org.erp.master.domain.vo.ErpProductVo;
import org.erp.master.domain.vo.ErpSupplierVo;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpProductCategoryService;
import org.erp.master.service.IErpProductService;
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
 * 产品资料导入导出（DOC/22 §3）。
 *
 * <p>类型（成品/原料）、发票类型（不含税/增值税发票/普通发票）与状态同时接受中文
 * 和原始码，不依赖 sys_dict。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ProductExcelService {

    /** 单文件行数上限（DOC/22 §3.2）。 */
    static final int MAX_ROWS = 2000;

    /** 导出上限（DOC/22 §3.3）。 */
    static final int EXPORT_LIMIT = 5000;

    private static final String SHEET_NAME = "产品资料";

    private static final Map<String, String> TYPE_MAPPING = Map.of(
        "成品", "PRODUCT",
        "原料", "MATERIAL",
        "PRODUCT", "PRODUCT",
        "MATERIAL", "MATERIAL"
    );

    private static final Map<String, String> TAX_TYPE_MAPPING = Map.of(
        "不含税", "NO_TAX",
        "增值税发票", "VAT_INVOICE",
        "普通发票", "GENERAL_INVOICE",
        "NO_TAX", "NO_TAX",
        "VAT_INVOICE", "VAT_INVOICE",
        "GENERAL_INVOICE", "GENERAL_INVOICE"
    );

    /** 数量精度：中文与原始码并存（DOC/26 §10） */
    private static final Map<String, String> QTY_SCALE_MAPPING = Map.of(
        "整数", "0",
        "1位", "1",
        "2位", "2",
        "3位", "3",
        "0", "0",
        "1", "1",
        "2", "2",
        "3", "3"
    );

    private final IErpProductService productService;
    private final IErpProductCategoryService categoryService;
    private final IErpSupplierService supplierService;
    private final ErpProductMapper productMapper;

    /**
     * 导出当前筛选条件下的全部产品（最多 {@value #EXPORT_LIMIT} 行）。
     */
    public void export(String keyword, String type, Integer status, Long categoryId, Long supplierId,
                       HttpServletResponse response) {
        ExcelUtil.exportExcel(exportRows(keyword, type, status, categoryId, supplierId), SHEET_NAME, ProductExportVo.class, response);
    }

    /**
     * 下载空白导入模板（表头与导入解析保持一致）。
     */
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(new ArrayList<>(), SHEET_NAME, ProductExportVo.class, response);
    }

    /**
     * 解析并导入产品资料。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importData(MultipartFile file, boolean updateSupport) throws IOException {
        List<ExcelRow<ProductImportRow>> rows;
        try (InputStream inputStream = file.getInputStream()) {
            rows = ExcelRowReader.read(inputStream, ProductImportRow.class);
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
    ImportResult importRows(List<ExcelRow<ProductImportRow>> rows, boolean updateSupport) {
        ImportResult result = new ImportResult();
        if (rows.size() > MAX_ROWS) {
            result.failAll(rows.size(), "单次最多导入" + MAX_ROWS + "行，请拆分文件后重试");
            return result;
        }

        List<ErpProductBo> validBos = new ArrayList<>();
        List<ExcelRow<ProductImportRow>> validRows = new ArrayList<>();
        List<Long> existingIds = new ArrayList<>();
        Map<String, ErpProduct> activeCache = new HashMap<>();
        Map<String, Long> deletedCache = new HashMap<>();
        Set<String> fileCodes = new HashSet<>();
        boolean hasError = false;

        // 分类/供应商按编码匹配（DOC/26 §4.6）：名称无唯一约束，按名称取第一条会静默归错
        Map<String, Long> categoryIds = new HashMap<>();
        for (ErpProductCategoryVo category : categoryService.selectList(null)) {
            categoryIds.putIfAbsent(category.getCode(), category.getId());
        }
        Map<String, Long> supplierIds = new HashMap<>();
        for (ErpSupplierVo supplier : supplierService.selectList(null)) {
            supplierIds.putIfAbsent(supplier.getCode(), supplier.getId());
        }

        for (ExcelRow<ProductImportRow> excelRow : rows) {
            ProductImportRow data = excelRow.data();
            List<String> errors = new ArrayList<>();

            String code = CellValues.optional(data.getCode(), "产品编码", 32, errors);
            code = CellValues.blank(code) ? null : code;
            String name = CellValues.required(data.getName(), "产品名称", 100, errors);
            String spec = CellValues.optional(data.getSpec(), "规格", 100, errors);
            String unit = CellValues.required(data.getUnit(), "单位", 16, errors);
            String type = CellValues.choice(data.getType(), TYPE_MAPPING, "类型", true, errors);
            String categoryCode = CellValues.optional(data.getCategoryCode(), "商品分类编码", 32, errors);
            String supplierCode = CellValues.optional(data.getSupplierCode(), "供应商编码", 32, errors);
            String qtyScaleCode = CellValues.choice(data.getQtyScale(), QTY_SCALE_MAPPING, "数量精度", false, errors);
            BigDecimal costPrice = CellValues.decimal(data.getCostPrice(), "参考成本", 18, 4, false, errors);
            BigDecimal purchasePrice = CellValues.decimal(data.getPurchasePrice(), "采购价", 18, 4, false, errors);
            String taxType = CellValues.choice(data.getTaxType(), TAX_TYPE_MAPPING, "发票类型", false, errors);
            BigDecimal freight = CellValues.decimal(data.getFreight(), "运费", 18, 4, false, errors);
            Integer status = CellValues.status(data.getStatus(), errors);
            String remark = CellValues.optional(data.getRemark(), "备注", 500, errors);

            if (taxType == null) {
                taxType = "NO_TAX";
            }
            if (costPrice != null && costPrice.signum() < 0) {
                errors.add("参考成本不能为负");
            }
            if (purchasePrice != null && purchasePrice.signum() < 0) {
                errors.add("采购价不能为负");
            }
            if (freight != null && freight.signum() < 0) {
                errors.add("运费不能为负");
            }

            ErpProduct existing = null;
            Long existingId = null;
            if (code != null) {
                if (!fileCodes.add(code)) {
                    errors.add("文件内产品编码重复：" + code);
                }
                existing = findActive(code, activeCache);
                if (existing != null) {
                    existingId = existing.getId();
                } else if (findDeletedId(code, deletedCache) != null) {
                    errors.add("编码已被删除记录占用，请恢复或改用其他编码：" + code);
                }
            }

            // 分类/供应商留空：新增按空处理，更新保持原值（整表导入不应误清空既有归类）
            Long categoryId = null;
            if (categoryCode != null) {
                categoryId = categoryIds.get(categoryCode);
                if (categoryId == null) {
                    errors.add("商品分类编码不存在：" + categoryCode);
                }
            } else if (existing != null) {
                categoryId = existing.getCategoryId();
            }
            Long supplierId = null;
            if (supplierCode != null) {
                supplierId = supplierIds.get(supplierCode);
                if (supplierId == null) {
                    errors.add("供应商编码不存在：" + supplierCode);
                }
            } else if (existing != null) {
                supplierId = existing.getSupplierId();
            }

            // 数量精度留空：新增按数据库默认 0（整数），更新保持原值
            Integer qtyScale = qtyScaleCode == null ? null : Integer.valueOf(qtyScaleCode);
            if (qtyScale == null && existing != null) {
                qtyScale = existing.getQtyScale();
            }

            if (!errors.isEmpty()) {
                result.fail(excelRow.rowNumber(), String.join("；", errors));
                hasError = true;
                continue;
            }

            ErpProductBo bo = new ErpProductBo();
            bo.setCode(code);
            bo.setName(name);
            bo.setSpec(spec);
            bo.setUnit(unit);
            bo.setType(type);
            bo.setCategoryId(categoryId);
            bo.setSupplierId(supplierId);
            bo.setQtyScale(qtyScale);
            // 参考成本为系统回写的「最近入库成本」，导入只做格式校验不落库（DOC/26 §9.4 / Q11）
            bo.setPurchasePrice(purchasePrice);
            bo.setTaxType(taxType);
            bo.setFreight(freight);
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

        List<ErpProductBo> writeBos = new ArrayList<>();
        List<ExcelRow<ProductImportRow>> writeRows = new ArrayList<>();
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
            ErpProductBo bo = writeBos.get(i);
            try {
                if (bo.getId() == null) {
                    productService.insert(bo);
                } else {
                    productService.update(bo);
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
                log.warn("产品资料导入中止，行 {}：{}", writeRows.get(i).rowNumber(), message);
                return result;
            }
        }

        for (int i = 0; i < writeBos.size(); i++) {
            result.success();
        }
        return result;
    }

    /**
     * 导出映射：类型/发票类型/状态转为中文，不输出 id/tenantId/delFlag。
     *
     * @param keyword    编码/名称/规格模糊匹配
     * @param type       成品/原料，null=全部
     * @param status     状态，null=全部
     * @param categoryId 分类ID，null=全部
     * @param supplierId 供应商ID，null=全部
     * @return 导出行
     */
    List<ProductExportVo> exportRows(String keyword, String type, Integer status, Long categoryId, Long supplierId) {
        List<ErpProductVo> rows = PageQuery.collectForExport(EXPORT_LIMIT, query ->
            productService.selectPage(keyword, type, status, categoryId, supplierId, query).getRows());
        List<ProductExportVo> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (ErpProductVo row : rows) {
            ProductExportVo export = new ProductExportVo();
            export.setCode(row.getCode());
            export.setName(row.getName());
            export.setSpec(row.getSpec());
            export.setUnit(row.getUnit());
            export.setType(typeLabel(row.getType()));
            export.setCategoryCode(row.getCategoryCode());
            export.setSupplierCode(row.getSupplierCode());
            export.setQtyScale(qtyScaleLabel(row.getQtyScale()));
            export.setCostPrice(row.getCostPrice());
            export.setPurchasePrice(row.getPurchasePrice());
            export.setTaxType(taxTypeLabel(row.getTaxType()));
            export.setFreight(row.getFreight());
            export.setStatus(statusLabel(row.getStatus()));
            export.setRemark(row.getRemark());
            result.add(export);
        }
        return result;
    }

    private ErpProduct findActive(String code, Map<String, ErpProduct> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        ErpProduct row = productMapper.selectOne(new LambdaQueryWrapper<ErpProduct>()
            .eq(ErpProduct::getCode, code));
        cache.put(code, row);
        return row;
    }

    private Long findDeletedId(String code, Map<String, Long> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        Long id = productMapper.selectDeletedIdByCode(code);
        id = id == null || id <= 0 ? null : id;
        cache.put(code, id);
        return id;
    }

    static String typeLabel(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "PRODUCT" -> "成品";
            case "MATERIAL" -> "原料";
            default -> type;
        };
    }

    static String taxTypeLabel(String taxType) {
        if (taxType == null) {
            return "";
        }
        return switch (taxType) {
            case "NO_TAX" -> "不含税";
            case "VAT_INVOICE" -> "增值税发票";
            case "GENERAL_INVOICE" -> "普通发票";
            default -> taxType;
        };
    }

    static String statusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        return status == 1 ? "启用" : "停用";
    }

    /**
     * 数量精度导出文案：0 导「整数」，1~3 导「N位」。
     *
     * @param qtyScale 数量精度位数
     * @return 导出文案，空值按整数
     */
    static String qtyScaleLabel(Integer qtyScale) {
        if (qtyScale == null || qtyScale <= 0) {
            return "整数";
        }
        return qtyScale + "位";
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
            super("产品资料导入失败，整批已回滚");
            this.result = result;
        }

        public ImportResult getResult() {
            return result;
        }
    }
}
