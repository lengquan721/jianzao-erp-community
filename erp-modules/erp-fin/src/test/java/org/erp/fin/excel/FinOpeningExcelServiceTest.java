package org.erp.fin.excel;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.excel.ExcelRow;
import org.erp.common.excel.ImportResult;
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.domain.vo.FinOpeningVo;
import org.erp.fin.service.IFinOpeningService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 期初往来导入导出单元测试（DOC/22 §8）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class FinOpeningExcelServiceTest {

    @Mock
    private IFinOpeningService openingService;
    @Mock
    private ErpCustomerMapper customerMapper;
    @Mock
    private ErpSupplierMapper supplierMapper;

    private FinOpeningExcelService service;

    @BeforeEach
    void setUp() {
        service = new FinOpeningExcelService(openingService, customerMapper, supplierMapper);

        ErpCustomer customer = new ErpCustomer();
        customer.setId(9L);
        customer.setCode("C001");
        customer.setName("示例机械");
        when(customerMapper.selectOne(any(Wrapper.class))).thenReturn(customer);

        ErpSupplier supplier = new ErpSupplier();
        supplier.setId(10L);
        supplier.setCode("S001");
        supplier.setName("示例钢材");
        when(supplierMapper.selectOne(any(Wrapper.class))).thenReturn(supplier);

        when(openingService.save(any(FinOpeningBo.class))).thenReturn(new FinOpeningVo());
    }

    @Test
    void importRows_success_mapsPartyTypeAndCounts() {
        ImportResult result = service.importRows(rows(
            row("应收", "C001", "2000.00", "期初应收"),
            row("应付", "S001", "800.50", null)));

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getSuccess());
        assertEquals(0, result.getFailed());

        ArgumentCaptor<FinOpeningBo> captor = ArgumentCaptor.forClass(FinOpeningBo.class);
        verify(openingService, times(2)).save(captor.capture());
        assertEquals("AR", captor.getAllValues().get(0).getPartyType());
        assertEquals(Long.valueOf(9L), captor.getAllValues().get(0).getPartyId());
        assertEquals("AP", captor.getAllValues().get(1).getPartyType());
        assertEquals(Long.valueOf(10L), captor.getAllValues().get(1).getPartyId());
        assertEquals(0, new BigDecimal("800.50").compareTo(captor.getAllValues().get(1).getAmount()));
    }

    @Test
    void importRows_unknownCustomerCode_failsWithoutSaving() {
        when(customerMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ImportResult result = service.importRows(rows(row("应收", "UNKNOWN", "1", null)));

        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("客户编码不存在"));
        verify(openingService, never()).save(any(FinOpeningBo.class));
    }

    @Test
    void importRows_unknownSupplierCode_failsWithoutSaving() {
        when(supplierMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ImportResult result = service.importRows(rows(row("应付", "UNKNOWN", "1", null)));

        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("供应商编码不存在"));
        verify(openingService, never()).save(any(FinOpeningBo.class));
    }

    @Test
    void importRows_invalidPartyType_rejected() {
        ImportResult result = service.importRows(rows(row("其他", "C001", "1", null)));

        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("往来类型取值无效"));
        verify(openingService, never()).save(any(FinOpeningBo.class));
    }

    @Test
    void importRows_negativeOrTooManyDecimalsAmount_rejected() {
        ImportResult result = service.importRows(rows(
            row("应收", "C001", "-1", null),
            row("应收", "C001", "1.234", null)));

        assertEquals(2, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("期初金额不能为负"));
        assertTrue(result.getErrors().get(1).message().contains("最多保留2位小数"));
        verify(openingService, never()).save(any(FinOpeningBo.class));
    }

    @Test
    void importRows_confirmedRow_rollsBackWholeBatch() {
        when(openingService.save(any(FinOpeningBo.class)))
            .thenThrow(new ServiceException("期初已确认锁定，请先反确认", 40902));

        ImportResult result = service.importRows(rows(
            row("应收", "C001", "100", null),
            row("应付", "S001", "200", null)));

        assertEquals(2, result.getTotal());
        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getSkipped());
        assertTrue(result.getErrors().get(0).message().contains("整批已回滚"));
        verify(openingService, times(1)).save(any(FinOpeningBo.class));
    }

    @Test
    void toExportRows_mapsPartyTypeAndCode() {
        FinOpeningVo ar = new FinOpeningVo();
        ar.setPartyType("AR");
        ar.setPartyId(9L);
        ar.setAmount(new BigDecimal("2000.00"));
        ar.setOpeningDate(java.time.LocalDate.of(2026, 9, 30));
        ar.setRemark("期初应收");

        FinOpeningVo ap = new FinOpeningVo();
        ap.setPartyType("AP");
        ap.setPartyId(10L);
        ap.setAmount(new BigDecimal("800.50"));

        ErpCustomer customer = new ErpCustomer();
        customer.setId(9L);
        customer.setCode("C001");
        when(customerMapper.selectByIds(List.of(9L))).thenReturn(List.of(customer));

        ErpSupplier supplier = new ErpSupplier();
        supplier.setId(10L);
        supplier.setCode("S001");
        when(supplierMapper.selectByIds(List.of(10L))).thenReturn(List.of(supplier));

        List<FinOpeningExportVo> exportRows = service.toExportRows(List.of(ar, ap));

        assertEquals(2, exportRows.size());
        assertEquals("应收", exportRows.get(0).getPartyType());
        assertEquals("C001", exportRows.get(0).getPartyCode());
        assertEquals("2026-09-30", exportRows.get(0).getOpeningDate());
        assertEquals("应付", exportRows.get(1).getPartyType());
        assertEquals("S001", exportRows.get(1).getPartyCode());
    }

    @Test
    void toExportRows_batches201DistinctIdsPerTypeWithoutDroppingOrReorderingRows() {
        List<FinOpeningVo> rows = new ArrayList<>();
        for (long id = 1; id <= 201; id++) {
            rows.add(exportRow("AP", id));
            rows.add(exportRow("AR", id));
        }
        rows.add(exportRow("AR", 201L));
        rows.add(exportRow("AP", 201L));
        when(customerMapper.selectByIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().sorted((left, right) -> Long.compare(right, left))
                .map(id -> customer(id, "C" + id)).toList();
        });
        when(supplierMapper.selectByIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().sorted((left, right) -> Long.compare(right, left))
                .map(id -> supplier(id, "S" + id)).toList();
        });

        List<FinOpeningExportVo> exported = service.toExportRows(rows);

        assertEquals(404, exported.size());
        for (int index = 0; index < rows.size(); index++) {
            FinOpeningVo source = rows.get(index);
            FinOpeningExportVo target = exported.get(index);
            boolean ar = "AR".equals(source.getPartyType());
            assertEquals((ar ? "C" : "S") + source.getPartyId(), target.getPartyCode());
            assertEquals(ar ? "应收" : "应付", target.getPartyType());
            assertSame(source.getAmount(), target.getAmount(), "不能转为 double、舍入或改变行顺序");
            assertEquals(source.getRemark(), target.getRemark());
        }
        ArgumentCaptor<Collection<Long>> customerIds = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<Long>> supplierIds = ArgumentCaptor.forClass(Collection.class);
        verify(customerMapper, times(2)).selectByIds(customerIds.capture());
        verify(supplierMapper, times(2)).selectByIds(supplierIds.capture());
        List<Long> firstBatch = LongStream.rangeClosed(1, 200).boxed().toList();
        assertEquals(firstBatch, List.copyOf(customerIds.getAllValues().get(0)));
        assertEquals(firstBatch, List.copyOf(supplierIds.getAllValues().get(0)));
        assertEquals(List.of(201L), List.copyOf(customerIds.getAllValues().get(1)));
        assertEquals(List.of(201L), List.copyOf(supplierIds.getAllValues().get(1)));
        verifyNoMoreInteractions(customerMapper, supplierMapper);
        verifyNoInteractions(openingService);
    }

    @Test
    void toExportRows_missingRecordsAndNullOrEmptyCodesStayUnchangedWithoutRetry() {
        List<FinOpeningVo> rows = new ArrayList<>();
        for (int repeat = 0; repeat < 3; repeat++) {
            for (String type : List.of("AR", "AP")) {
                rows.add(exportRow(type, 9L));  // 主数据不存在
                rows.add(exportRow(type, 10L)); // 主数据存在，code 为 null
                rows.add(exportRow(type, 11L)); // code 为空串
            }
        }
        when(customerMapper.selectByIds(List.of(9L, 10L, 11L)))
            .thenReturn(List.of(customer(10, null), customer(11, "")));
        when(supplierMapper.selectByIds(List.of(9L, 10L, 11L)))
            .thenReturn(List.of(supplier(10, null), supplier(11, "")));

        List<FinOpeningExportVo> exported = service.toExportRows(rows);

        assertEquals(18, exported.size());
        for (int index = 0; index < exported.size(); index++) {
            if (index % 3 == 2) {
                assertEquals("", exported.get(index).getPartyCode());
            } else {
                assertNull(exported.get(index).getPartyCode());
            }
        }
        verify(customerMapper).selectByIds(List.of(9L, 10L, 11L));
        verify(supplierMapper).selectByIds(List.of(9L, 10L, 11L));
        verifyNoMoreInteractions(customerMapper, supplierMapper);
        verifyNoInteractions(openingService);
    }

    @Test
    void toExportRows_keepsSameIdNamespacesChineseTypeFallbacksAndNullIds() {
        List<FinOpeningVo> rows = new ArrayList<>();
        for (String type : Arrays.asList("AR", "AP", "应收", "应付", "CUSTOM", null)) {
            rows.add(exportRow(type, 9L));
        }
        rows.add(exportRow("AR", null));
        rows.add(exportRow("AP", null));
        when(customerMapper.selectByIds(List.of(9L))).thenReturn(List.of(customer(9, "C9")));
        when(supplierMapper.selectByIds(List.of(9L))).thenReturn(List.of(supplier(9, "S9")));

        List<FinOpeningExportVo> exported = service.toExportRows(rows);

        assertEquals(Arrays.asList("应收", "应付", "应收", "应付", "CUSTOM", null, "应收", "应付"),
            exported.stream().map(FinOpeningExportVo::getPartyType).toList());
        assertEquals(Arrays.asList("C9", "S9", "S9", "S9", "S9", "S9", null, null),
            exported.stream().map(FinOpeningExportVo::getPartyCode).toList());
        for (int index = 0; index < rows.size(); index++) {
            assertSame(rows.get(index).getAmount(), exported.get(index).getAmount());
            assertEquals(rows.get(index).getRemark(), exported.get(index).getRemark());
        }
        verify(customerMapper).selectByIds(List.of(9L));
        verify(supplierMapper).selectByIds(List.of(9L));
        verifyNoMoreInteractions(customerMapper, supplierMapper);
        verifyNoInteractions(openingService);
    }

    @Test
    void toExportRows_emptyListDoesNotQueryAnyMapper() {
        assertTrue(service.toExportRows(List.of()).isEmpty());
        verifyNoInteractions(customerMapper, supplierMapper, openingService);
    }

    @Test
    void importRows_missingOrInvalidOpeningDate_rejected() {
        ImportResult result = service.importRows(rows(
            row("应收", "C001", "1", null, null),
            row("应收", "C001", "1", "2026/13/01", null)));
        assertEquals(2, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("期初截止日不能为空"));
        assertTrue(result.getErrors().get(1).message().contains("期初截止日格式必须是 yyyy-MM-dd"));
        verify(openingService, never()).save(any(FinOpeningBo.class));
    }

    @Test
    void importRows_duplicateParty_rejectedToAvoidSilentOverwrite() {
        ImportResult result = service.importRows(rows(
            row("应收", "C001", "100", "2020-09-30", null),
            row("应收", "C001", "200", "2020-09-30", null)));
        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("往来单位重复"));
        verify(openingService, never()).save(any(FinOpeningBo.class));
    }

    private FinOpeningImportRow row(String type, String code, String amount, String date, String remark) {
        FinOpeningImportRow row = row(type, code, amount, remark);
        row.setOpeningDate(date);
        return row;
    }

    // ---------- 辅助 ----------

    private FinOpeningVo exportRow(String type, Long id) {
        FinOpeningVo row = new FinOpeningVo();
        row.setPartyType(type);
        row.setPartyId(id);
        row.setAmount(new BigDecimal("9007199254740993.01"));
        row.setRemark(type + "-" + id);
        return row;
    }

    private ErpCustomer customer(long id, String code) {
        ErpCustomer customer = new ErpCustomer();
        customer.setId(id);
        customer.setCode(code);
        return customer;
    }

    private ErpSupplier supplier(long id, String code) {
        ErpSupplier supplier = new ErpSupplier();
        supplier.setId(id);
        supplier.setCode(code);
        return supplier;
    }

    private FinOpeningImportRow row(String partyType, String partyCode, String amount, String remark) {
        FinOpeningImportRow row = new FinOpeningImportRow();
        row.setPartyType(partyType);
        row.setPartyCode(partyCode);
        row.setAmount(amount);
        row.setOpeningDate("2020-09-30");
        row.setRemark(remark);
        return row;
    }

    private List<ExcelRow<FinOpeningImportRow>> rows(FinOpeningImportRow... data) {
        List<ExcelRow<FinOpeningImportRow>> rows = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            rows.add(new ExcelRow<>(i + 2, data[i]));
        }
        return rows;
    }
}
