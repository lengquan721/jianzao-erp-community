package org.erp.master.excel;

import org.erp.common.excel.ExcelRow;
import org.erp.common.excel.ImportResult;
import org.erp.master.domain.bo.ErpProductBo;
import org.erp.master.domain.vo.ErpProductCategoryVo;
import org.erp.master.domain.vo.ErpSupplierVo;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpProductCategoryService;
import org.erp.master.service.IErpProductService;
import org.erp.master.service.IErpSupplierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 产品导入的类型/发票类型映射与校验单测。
 */
@Tag("dev")
class ProductExcelServiceTest {

    private IErpProductService productService;
    private IErpProductCategoryService categoryService;
    private IErpSupplierService supplierService;
    private ErpProductMapper productMapper;
    private ProductExcelService excelService;

    @BeforeEach
    void setUp() {
        productService = mock(IErpProductService.class);
        categoryService = mock(IErpProductCategoryService.class);
        supplierService = mock(IErpSupplierService.class);
        productMapper = mock(ErpProductMapper.class);
        excelService = new ProductExcelService(productService, categoryService, supplierService, productMapper);
    }

    @Test
    void chineseTypeAndTaxTypeMappedToCodes() {
        ProductImportRow row = row("成品", "增值税发票");
        row.setCode("P001");
        row.setCostPrice("12.5");

        ImportResult result = excelService.importRows(List.of(new ExcelRow<>(2, row)), false);

        assertEquals(1, result.getSuccess());
        ArgumentCaptor<ErpProductBo> captor = ArgumentCaptor.forClass(ErpProductBo.class);
        verify(productService).insert(captor.capture());
        assertEquals("PRODUCT", captor.getValue().getType());
        assertEquals("VAT_INVOICE", captor.getValue().getTaxType());
        // 参考成本为系统回写的「最近入库成本」，导入不落库（DOC/26 §9.4 / Q11 口径调整）
        assertNull(captor.getValue().getCostPrice());
    }

    @Test
    void rawCodesStillAcceptedAndTaxTypeDefaults() {
        ProductImportRow row = row("MATERIAL", "");
        row.setStatus("0");

        ImportResult result = excelService.importRows(List.of(new ExcelRow<>(2, row)), false);

        assertEquals(1, result.getSuccess());
        ArgumentCaptor<ErpProductBo> captor = ArgumentCaptor.forClass(ErpProductBo.class);
        verify(productService).insert(captor.capture());
        assertEquals("MATERIAL", captor.getValue().getType());
        assertEquals("NO_TAX", captor.getValue().getTaxType());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void invalidTypeRejectedWithoutWrites() {
        ProductImportRow row = row("半成品", "不含税");

        ImportResult result = excelService.importRows(List.of(new ExcelRow<>(2, row)), false);

        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("类型取值无效"));
        verifyNoInteractions(productService);
    }

    @Test
    void negativeCostRejected() {
        ProductImportRow row = row("成品", "不含税");
        row.setCostPrice("-1");

        ImportResult result = excelService.importRows(List.of(new ExcelRow<>(2, row)), false);

        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("参考成本不能为负"));
        verifyNoInteractions(productService);
    }

    @Test
    void exportMapsEnumsToChinese() {
        org.erp.master.domain.vo.ErpProductVo vo = new org.erp.master.domain.vo.ErpProductVo();
        vo.setCode("P001");
        vo.setName("螺母");
        vo.setUnit("个");
        vo.setType("MATERIAL");
        vo.setTaxType("GENERAL_INVOICE");
        vo.setCategoryCode("C01");
        vo.setSupplierCode("S01");
        vo.setStatus(1);
        when(productService.selectPage(any(), any(), any(), any(), any(), any()))
            .thenReturn(org.dromara.common.mybatis.core.page.TableDataInfo.build(List.of(vo)));

        List<ProductExportVo> rows = excelService.exportRows(null, null, null, null, null);

        assertEquals(1, rows.size());
        assertEquals("原料", rows.get(0).getType());
        assertEquals("普通发票", rows.get(0).getTaxType());
        assertEquals("C01", rows.get(0).getCategoryCode());
        assertEquals("S01", rows.get(0).getSupplierCode());
        assertEquals("启用", rows.get(0).getStatus());
    }

    @Test
    void oldTemplateWithoutCategoryColumnsStillReads() throws Exception {
        String[] headers = {"产品编码", "产品名称", "规格", "单位", "类型", "参考成本", "采购价", "发票类型", "运费", "状态", "备注"};
        String[] values = {"P001", "螺母", "M8", "个", "原料", "1.5", "2", "不含税", "0", "启用", ""};
        List<ExcelRow<ProductImportRow>> rows;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("产品资料");
            org.apache.poi.ss.usermodel.Row head = sheet.createRow(0);
            org.apache.poi.ss.usermodel.Row data = sheet.createRow(1);
            for (int i = 0; i < headers.length; i++) {
                head.createCell(i).setCellValue(headers[i]);
                data.createCell(i).setCellValue(values[i]);
            }
            workbook.write(out);
            rows = org.erp.common.excel.ExcelRowReader.read(new java.io.ByteArrayInputStream(out.toByteArray()), ProductImportRow.class);
        }

        assertEquals(1, rows.size());
        assertEquals("P001", rows.get(0).data().getCode());
        assertEquals(2, rows.get(0).rowNumber());
        assertNull(rows.get(0).data().getCategoryCode(), "旧模板无分类列时应为空，导入保持原分类");
    }

    @Test
    void categoryAndSupplierMatchedByCode() {
        // 分类名称允许重名，按 code 匹配才能归到唯一分类（DOC/26 §4.6）
        when(categoryService.selectList(any())).thenReturn(List.of(
            category(11L, "C01", "脱模剂"), category(12L, "C02", "脱模剂")));
        when(supplierService.selectList(any())).thenReturn(List.of(supplier(21L, "S01", "晨光五金")));
        ProductImportRow row = row("成品", "");
        row.setCategoryCode("C02");
        row.setSupplierCode("S01");

        ImportResult result = excelService.importRows(List.of(new ExcelRow<>(2, row)), false);

        assertEquals(1, result.getSuccess());
        ArgumentCaptor<ErpProductBo> captor = ArgumentCaptor.forClass(ErpProductBo.class);
        verify(productService).insert(captor.capture());
        assertEquals(12L, captor.getValue().getCategoryId());
        assertEquals(21L, captor.getValue().getSupplierId());
    }

    @Test
    void unknownCategoryReportsRowError() {
        when(categoryService.selectList(any())).thenReturn(List.of(category(11L, "C01", "脱模剂")));
        ProductImportRow row = row("成品", "");
        row.setCategoryCode("C99");

        ImportResult result = excelService.importRows(List.of(new ExcelRow<>(2, row)), false);

        assertEquals(1, result.getFailed());
        assertEquals(2, result.getErrors().get(0).row());
        assertTrue(result.getErrors().get(0).message().contains("商品分类编码不存在：C99"));
        verifyNoInteractions(productService);
    }

    private ErpProductCategoryVo category(Long id, String code, String name) {
        ErpProductCategoryVo vo = new ErpProductCategoryVo();
        vo.setId(id);
        vo.setCode(code);
        vo.setName(name);
        return vo;
    }

    private ErpSupplierVo supplier(Long id, String code, String name) {
        ErpSupplierVo vo = new ErpSupplierVo();
        vo.setId(id);
        vo.setCode(code);
        vo.setName(name);
        return vo;
    }

    @Test
    void qtyScaleAcceptsChineseAndCode() {
        ProductImportRow chinese = row("成品", "");
        chinese.setQtyScale("2位");
        ProductImportRow code = row("成品", "");
        code.setCode("P002");
        code.setQtyScale("3");

        ImportResult result = excelService.importRows(
            List.of(new ExcelRow<>(2, chinese), new ExcelRow<>(3, code)), false);

        assertEquals(2, result.getSuccess());
        ArgumentCaptor<ErpProductBo> captor = ArgumentCaptor.forClass(ErpProductBo.class);
        verify(productService, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals(2, captor.getAllValues().get(0).getQtyScale());
        assertEquals(3, captor.getAllValues().get(1).getQtyScale());
    }

    @Test
    void qtyScaleInvalidReportsRowError() {
        ProductImportRow row = row("成品", "");
        row.setQtyScale("4位");

        ImportResult result = excelService.importRows(List.of(new ExcelRow<>(2, row)), false);

        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("数量精度取值无效：4位"));
        verifyNoInteractions(productService);
    }

    @Test
    void exportQtyScaleAsChineseLabel() {
        org.erp.master.domain.vo.ErpProductVo vo = new org.erp.master.domain.vo.ErpProductVo();
        vo.setCode("P001");
        vo.setName("脱模剂");
        vo.setUnit("kg");
        vo.setType("MATERIAL");
        vo.setQtyScale(2);
        when(productService.selectPage(any(), any(), any(), any(), any(), any()))
            .thenReturn(org.dromara.common.mybatis.core.page.TableDataInfo.build(List.of(vo)));

        List<ProductExportVo> rows = excelService.exportRows(null, null, null, null, null);

        assertEquals("2位", rows.get(0).getQtyScale());
    }

    @Test
    void overLimitImportIsRejectedBeforeAnyMapperOrServiceCall() {
        List<ExcelRow<ProductImportRow>> rows = new java.util.ArrayList<>();
        for (int index = 0; index < 2001; index++) {
            rows.add(new ExcelRow<>(index + 2, new ProductImportRow()));
        }

        ImportResult result = excelService.importRows(rows, false);

        assertEquals(2001, result.getFailed());
        assertEquals(100, result.getErrors().size());
        verifyNoInteractions(productService, categoryService, supplierService, productMapper);
    }

    private ProductImportRow row(String type, String taxType) {
        ProductImportRow row = new ProductImportRow();
        row.setCode("P001");
        row.setName("螺母");
        row.setUnit("个");
        row.setType(type);
        row.setTaxType(taxType);
        return row;
    }
}
