package org.erp.fin.excel;

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
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.domain.vo.FinOpeningVo;
import org.erp.fin.service.IFinOpeningService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 期初往来导入导出（DOC/22 §4）。
 *
 * 导入规则：
 * - 先解析并校验全部行，任意行失败则整批不落库；
 * - 往来类型「应收」映射 AR、「应付」映射 AP；
 * - 期初截止日必填（yyyy-MM-dd），同一往来单位在文件内只允许出现一行；
 * - 校验通过后在一个事务内逐行调用 {@link IFinOpeningService#save}，只保存 DRAFT；
 * - 已确认行（40902）标记整批回滚，错误定位到 Excel 行号；
 * - 单文件最多 2000 行。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class FinOpeningExcelService {

    /** 单文件行数上限（DOC/22 §4.2）。 */
    static final int MAX_ROWS = 2000;

    private static final String SHEET_NAME = "期初往来";

    private static final Map<String, String> PARTY_TYPE_MAPPING = Map.of(
        "应收", "AR",
        "应付", "AP",
        "AR", "AR",
        "AP", "AP"
    );

    private final IFinOpeningService openingService;
    private final ErpCustomerMapper customerMapper;
    private final ErpSupplierMapper supplierMapper;

    /**
     * 导出当前筛选条件下的全部期初往来；listAll 以 5000+1 哨兵限制，超限在写响应前失败。
     *
     * @param partyType AR/AP，可选
     */
    public void export(String partyType, HttpServletResponse response) {
        List<FinOpeningVo> rows = openingService.listAll(partyType);
        ExcelUtil.exportExcel(toExportRows(rows), SHEET_NAME, FinOpeningExportVo.class, response);
    }

    /**
     * 下载空白导入模板（表头与导入解析保持一致）。
     */
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(new ArrayList<>(), SHEET_NAME, FinOpeningImportRow.class, response);
    }

    /**
     * 解析并导入期初往来。
     *
     * 任何失败都抛出 {@link ImportRollbackException} 触发代理事务回滚，由 Controller
     * 捕获后返回逐行错误，避免 rollback-only 事务提交时抛 UnexpectedRollbackException。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importData(MultipartFile file) throws IOException {
        List<ExcelRow<FinOpeningImportRow>> rows;
        try (InputStream inputStream = file.getInputStream()) {
            rows = ExcelRowReader.read(inputStream, FinOpeningImportRow.class);
        } catch (ExcelRowReader.ImportLimitException e) {
            ImportResult result = new ImportResult();
            result.fail(e.rowNumber(), e.getMessage());
            throw new ImportRollbackException(result);
        }
        ImportResult result = importRows(rows);
        if (result.getFailed() > 0) {
            throw new ImportRollbackException(result);
        }
        return result;
    }

    /**
     * 校验并保存数据行。包级可见，便于不启动 Spring 事务直接单测。
     */
    ImportResult importRows(List<ExcelRow<FinOpeningImportRow>> rows) {
        ImportResult result = new ImportResult();
        if (rows.size() > MAX_ROWS) {
            result.failAll(rows.size(), "单次最多导入" + MAX_ROWS + "行，请拆分文件后重试");
            return result;
        }
        // 旧版模板没有「期初截止日」列时该字段恒为空，逐行报"不能为空"会刷屏且误导；
        // 整列全空时给一次性提示，同时覆盖"确实没填"与"模板过期"两种原因
        if (!rows.isEmpty() && rows.stream().allMatch(row -> CellValues.blank(row.data().getOpeningDate()))) {
            result.failAll(rows.size(), "导入文件未填写「期初截止日」；若使用的是旧版模板（无此列），请重新下载最新模板后填写");
            return result;
        }

        List<FinOpeningBo> validBos = new ArrayList<>();
        List<ExcelRow<FinOpeningImportRow>> validRows = new ArrayList<>();
        Map<String, ErpCustomer> customerCache = new HashMap<>();
        Map<String, ErpSupplier> supplierCache = new HashMap<>();
        // 文件内同一往来单位只允许一行，避免 upsert 静默覆盖前一行
        Map<String, Integer> seenParties = new HashMap<>();
        boolean hasError = false;

        for (ExcelRow<FinOpeningImportRow> excelRow : rows) {
            FinOpeningImportRow data = excelRow.data();
            List<String> errors = new ArrayList<>();

            String partyType = CellValues.choice(data.getPartyType(), PARTY_TYPE_MAPPING, "往来类型", true, errors);
            String partyCode = CellValues.required(data.getPartyCode(), "往来单位编码", 32, errors);
            BigDecimal amount = CellValues.decimal(data.getAmount(), "期初金额", 2, true, errors);
            LocalDate openingDate = CellValues.date(data.getOpeningDate(), "期初截止日", true, errors);
            String remark = CellValues.optional(data.getRemark(), "备注", 500, errors);
            if (amount != null && amount.signum() < 0) {
                errors.add("期初金额不能为负");
            }
            if (openingDate != null && openingDate.isAfter(LocalDate.now())) {
                errors.add("期初截止日不能晚于今天");
            }
            if (openingDate != null && openingDate.isBefore(LocalDate.of(1000, 1, 1))) {
                errors.add("期初截止日超出 MySQL DATE 范围");
            }

            Long partyId = null;
            if (partyType != null && partyCode != null) {
                if ("AR".equals(partyType)) {
                    ErpCustomer customer = findCustomer(partyCode, customerCache);
                    if (customer == null) {
                        errors.add("客户编码不存在：" + partyCode);
                    } else {
                        partyId = customer.getId();
                    }
                } else {
                    ErpSupplier supplier = findSupplier(partyCode, supplierCache);
                    if (supplier == null) {
                        errors.add("供应商编码不存在：" + partyCode);
                    } else {
                        partyId = supplier.getId();
                    }
                }
            }

            if (partyType != null && partyId != null) {
                Integer prevRow = seenParties.putIfAbsent(partyType + "|" + partyId, excelRow.rowNumber());
                if (prevRow != null) {
                    errors.add("与第" + prevRow + "行往来单位重复，同一单位只能有一条期初");
                }
            }
            if (!errors.isEmpty()) {
                result.fail(excelRow.rowNumber(), String.join("；", errors));
                hasError = true;
                continue;
            }

            FinOpeningBo bo = new FinOpeningBo();
            bo.setPartyType(partyType);
            bo.setPartyId(partyId);
            bo.setAmount(amount);
            bo.setOpeningDate(openingDate);
            bo.setRemark(remark);
            validBos.add(bo);
            validRows.add(excelRow);
        }

        // 任一行校验失败则整批不落库（DOC/22 §3.2/§4.2）
        if (hasError) {
            return result;
        }

        // 整批事务会一直持有各往来锁；按 AR、AP、ID 固定顺序保存，防止反序文件互锁。
        List<Integer> saveOrder = java.util.stream.IntStream.range(0, validBos.size()).boxed()
            .sorted(java.util.Comparator.<Integer>comparingInt(i -> "AR".equals(validBos.get(i).getPartyType()) ? 0 : 1)
                .thenComparing(i -> validBos.get(i).getPartyId())).toList();
        for (int position = 0; position < saveOrder.size(); position++) {
            int i = saveOrder.get(position);
            try {
                // save 为 upsert，且只写 DRAFT；重复导入即更新
                openingService.save(validBos.get(i));
            } catch (ServiceException e) {
                markRollbackOnly();
                for (int j = 0; j < position; j++) {
                    result.skipped();
                }
                String message = e.getMessage() == null ? "保存失败" : e.getMessage();
                result.fail(validRows.get(i).rowNumber(), message + "（整批已回滚）");
                for (int j = position + 1; j < validRows.size(); j++) {
                    result.skipped();
                }
                log.warn("期初往来导入中止，行 {}：{}", validRows.get(i).rowNumber(), message);
                return result;
            }
        }

        for (int i = 0; i < validBos.size(); i++) {
            result.success();
        }
        return result;
    }

    private ErpCustomer findCustomer(String code, Map<String, ErpCustomer> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        ErpCustomer customer = customerMapper.selectOne(new LambdaQueryWrapper<ErpCustomer>()
            .eq(ErpCustomer::getCode, code));
        cache.put(code, customer);
        return customer;
    }

    private ErpSupplier findSupplier(String code, Map<String, ErpSupplier> cache) {
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        ErpSupplier supplier = supplierMapper.selectOne(new LambdaQueryWrapper<ErpSupplier>()
            .eq(ErpSupplier::getCode, code));
        cache.put(code, supplier);
        return supplier;
    }

    /**
     * VO 转导出行：往来类型转中文，AR 查客户编码、AP 查供应商编码。
     */
    List<FinOpeningExportVo> toExportRows(List<FinOpeningVo> rows) {
        final int batchSize = 200;
        List<FinOpeningExportVo> result = new ArrayList<>(rows.size());
        Map<Long, String> customerCodes = new HashMap<>();
        Map<Long, String> supplierCodes = new HashMap<>();
        List<Long> customerIds = rows.stream()
            .filter(row -> "AR".equals(row.getPartyType()) && row.getPartyId() != null)
            .map(FinOpeningVo::getPartyId).distinct().toList();
        // 保留原非 AR 行取供应商编码的兼容语义；类型显示仍在下方原样转换。
        List<Long> supplierIds = rows.stream()
            .filter(row -> !"AR".equals(row.getPartyType()) && row.getPartyId() != null)
            .map(FinOpeningVo::getPartyId).distinct().toList();
        for (int start = 0; start < customerIds.size(); start += batchSize) {
            for (ErpCustomer customer : customerMapper.selectByIds(
                customerIds.subList(start, Math.min(start + batchSize, customerIds.size())))) {
                customerCodes.put(customer.getId(), customer.getCode());
            }
        }
        for (int start = 0; start < supplierIds.size(); start += batchSize) {
            for (ErpSupplier supplier : supplierMapper.selectByIds(
                supplierIds.subList(start, Math.min(start + batchSize, supplierIds.size())))) {
                supplierCodes.put(supplier.getId(), supplier.getCode());
            }
        }
        for (FinOpeningVo row : rows) {
            FinOpeningExportVo export = new FinOpeningExportVo();
            if ("AR".equals(row.getPartyType())) {
                export.setPartyType("应收");
            } else if ("AP".equals(row.getPartyType())) {
                export.setPartyType("应付");
            } else {
                export.setPartyType(row.getPartyType());
            }
            export.setAmount(row.getAmount());
            export.setOpeningDate(row.getOpeningDate() == null ? null : row.getOpeningDate().toString());
            export.setRemark(row.getRemark());
            if (row.getPartyId() != null) {
                // 缺失记录及 null/空编码只读映射，不 computeIfAbsent，不逐行回查。
                export.setPartyCode(("AR".equals(row.getPartyType()) ? customerCodes : supplierCodes).get(row.getPartyId()));
            }
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
     * 整批回滚信号：携带逐行导入结果，Controller 捕获后作为正常响应返回。
     */
    public static class ImportRollbackException extends RuntimeException {

        private final ImportResult result;

        public ImportRollbackException(ImportResult result) {
            super("期初往来导入失败，整批已回滚");
            this.result = result;
        }

        public ImportResult getResult() {
            return result;
        }
    }
}
