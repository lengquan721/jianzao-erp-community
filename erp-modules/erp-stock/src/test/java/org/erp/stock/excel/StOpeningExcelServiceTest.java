package org.erp.stock.excel;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.excel.ExcelRow;
import org.erp.common.excel.ImportResult;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpWarehouse;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpWarehouseMapper;
import org.erp.stock.domain.bo.StOpeningBo;
import org.erp.stock.domain.vo.StOpeningVo;
import org.erp.stock.service.IStOpeningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 期初库存导入导出单元测试（DOC/22 §8）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class StOpeningExcelServiceTest {

    @Mock
    private IStOpeningService openingService;
    @Mock
    private ErpProductMapper productMapper;
    @Mock
    private ErpWarehouseMapper warehouseMapper;

    private StOpeningExcelService service;

    @BeforeEach
    void setUp() {
        service = new StOpeningExcelService(openingService, productMapper, warehouseMapper);

        ErpProduct product = new ErpProduct();
        product.setId(301L);
        product.setCode("P001");
        product.setName("铝板");
        when(productMapper.selectOne(any(Wrapper.class))).thenReturn(product);

        ErpWarehouse warehouse = new ErpWarehouse();
        warehouse.setId(1L);
        warehouse.setCode("WH001");
        warehouse.setName("主仓");
        when(warehouseMapper.selectOne(any(Wrapper.class))).thenReturn(warehouse);

        when(openingService.save(any(StOpeningBo.class))).thenReturn(new StOpeningVo());
    }

    @Test
    void importRows_success_countsAndSavesAllRows() {
        ImportResult result = service.importRows(rows(
            row("P001", "WH001", "100", "10.5"),
            row("P001", "WH001", "20.125", "3.25")));

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getSuccess());
        assertEquals(0, result.getFailed());
        assertEquals(0, result.getSkipped());

        ArgumentCaptor<StOpeningBo> captor = ArgumentCaptor.forClass(StOpeningBo.class);
        verify(openingService, times(2)).save(captor.capture());
        assertEquals(0, new BigDecimal("100").compareTo(captor.getAllValues().get(0).getQty()));
        assertEquals(0, new BigDecimal("10.5").compareTo(captor.getAllValues().get(0).getUnitCost()));
    }

    @Test
    void importRows_unknownProductCode_failsWithoutSaving() {
        when(productMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ImportResult result = service.importRows(rows(row("UNKNOWN", "WH001", "1", "1")));

        assertEquals(1, result.getFailed());
        assertEquals(0, result.getSuccess());
        assertEquals(2, result.getErrors().get(0).row());
        assertTrue(result.getErrors().get(0).message().contains("产品编码不存在"));
        verify(openingService, never()).save(any(StOpeningBo.class));
    }

    @Test
    void importRows_unknownWarehouseCode_failsWithoutSaving() {
        when(warehouseMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ImportResult result = service.importRows(rows(row("P001", "UNKNOWN", "1", "1")));

        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("仓库编码不存在"));
        verify(openingService, never()).save(any(StOpeningBo.class));
    }

    @Test
    void importRows_negativeOrTooManyDecimalsQty_rejected() {
        ImportResult result = service.importRows(rows(
            row("P001", "WH001", "-1", "1"),
            row("P001", "WH001", "1.2345", "1")));

        assertEquals(2, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("期初数量不能为负"));
        assertTrue(result.getErrors().get(1).message().contains("最多保留3位小数"));
        verify(openingService, never()).save(any(StOpeningBo.class));
    }

    @Test
    void importRows_zeroCostWithQty_rejectedWithRowNumber() {
        ImportResult result = service.importRows(rows(
            row("P001", "WH001", "10", "0"),
            row("P001", "WH001", "0", "0")));

        // 有数量的行必须填成本（DOC/26 §2.4）；数量为 0 的行允许 0 成本
        assertEquals(1, result.getFailed());
        assertEquals(2, result.getErrors().get(0).row());
        assertTrue(result.getErrors().get(0).message().contains("商品「铝板」期初成本必须大于 0"));
        verify(openingService, never()).save(any(StOpeningBo.class));
    }

    @Test
    void importRows_negativeOrTooManyDecimalsUnitCost_rejected() {
        ImportResult result = service.importRows(rows(
            row("P001", "WH001", "1", "-0.1"),
            row("P001", "WH001", "1", "1.23456")));

        assertEquals(2, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("期初成本不能为负"));
        assertTrue(result.getErrors().get(1).message().contains("最多保留4位小数"));
        verify(openingService, never()).save(any(StOpeningBo.class));
    }

    @Test
    void importRows_confirmedRow_rollsBackWholeBatch() {
        when(openingService.save(any(StOpeningBo.class)))
            .thenThrow(new ServiceException("期初已确认锁定，请先反确认", 40902));

        ImportResult result = service.importRows(rows(
            row("P001", "WH001", "1", "1"),
            row("P001", "WH001", "2", "2")));

        // 整批回滚：无成功行，出错行 failure，其余行 skipped
        assertEquals(2, result.getTotal());
        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getSkipped());
        assertTrue(result.getErrors().get(0).message().contains("整批已回滚"));
        verify(openingService, times(1)).save(any(StOpeningBo.class));
    }

    @Test
    void importRows_confirmedRowOnSecondLine_marksFirstLineSkipped() {
        when(openingService.save(any(StOpeningBo.class)))
            .thenReturn(new StOpeningVo())
            .thenThrow(new ServiceException("期初已确认锁定，请先反确认", 40902));

        ImportResult result = service.importRows(rows(
            row("P001", "WH001", "1", "1"),
            row("P001", "WH001", "2", "2")));

        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getSkipped());
        assertEquals(1, result.getFailed());
        assertEquals(3, result.getErrors().get(0).row());
    }

    @Test
    void importRows_overMaxRows_rejected() {
        List<ExcelRow<StOpeningImportRow>> rows = new ArrayList<>();
        for (int i = 0; i < StOpeningExcelService.MAX_ROWS + 1; i++) {
            rows.add(new ExcelRow<>(i + 2, row("P001", "WH001", "1", "1")));
        }

        ImportResult result = service.importRows(rows);

        assertEquals(StOpeningExcelService.MAX_ROWS + 1, result.getFailed());
        verify(openingService, never()).save(any(StOpeningBo.class));
    }

    @Test
    void toExportRows_fillsWarehouseAndAmount() {
        StOpeningVo vo = new StOpeningVo();
        vo.setProductId(301L);
        vo.setProductCode("P001");
        vo.setProductName("铝板");
        vo.setWarehouseId(1L);
        vo.setQty(new BigDecimal("100.000"));
        vo.setUnitCost(new BigDecimal("10.5000"));

        vo.setWarehouseCode("WH001");
        vo.setWarehouseName("主仓");

        List<StOpeningExportVo> exportRows = service.toExportRows(List.of(vo));

        assertEquals(1, exportRows.size());
        StOpeningExportVo export = exportRows.get(0);
        assertEquals("P001", export.getProductCode());
        assertEquals("WH001", export.getWarehouseCode());
        assertEquals("主仓", export.getWarehouseName());
        assertEquals(new BigDecimal("1050.00"), export.getAmount());
        verify(warehouseMapper, never()).selectById(any());
    }

    // ---------- 辅助 ----------

    private StOpeningImportRow row(String productCode, String warehouseCode, String qty, String unitCost) {
        StOpeningImportRow row = new StOpeningImportRow();
        row.setProductCode(productCode);
        row.setWarehouseCode(warehouseCode);
        row.setQty(qty);
        row.setUnitCost(unitCost);
        row.setAmount("999999");
        return row;
    }

    private List<ExcelRow<StOpeningImportRow>> rows(StOpeningImportRow... data) {
        List<ExcelRow<StOpeningImportRow>> rows = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            rows.add(new ExcelRow<>(i + 2, data[i]));
        }
        return rows;
    }
}
