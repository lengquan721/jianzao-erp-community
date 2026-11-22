package org.erp.fin.excel;

import org.dromara.common.core.exception.ServiceException;
import org.erp.common.excel.CellValues;
import org.erp.common.excel.ExcelRow;
import org.erp.common.excel.ExcelRowReader;
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.domain.vo.FinOpeningVo;
import org.erp.fin.service.IFinOpeningService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 日期兼容、文件内单位去重、整批回滚和有界错误。默认严格桩。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinOpeningExcelBoundaryTest {
    @Mock private IFinOpeningService openings;
    @Mock private ErpCustomerMapper customers;
    @Mock private ErpSupplierMapper suppliers;
    private FinOpeningExcelService excel;
    @BeforeEach void setup() { excel = new FinOpeningExcelService(openings, customers, suppliers); }

    @Test void arAndApWithSameIdAreIndependentAndBatchLocksAlwaysArThenAp() {
        ErpCustomer customer = new ErpCustomer(); customer.setId(9L);
        ErpSupplier supplier = new ErpSupplier(); supplier.setId(9L);
        when(customers.selectOne(any(Wrapper.class))).thenReturn(customer);
        when(suppliers.selectOne(any(Wrapper.class))).thenReturn(supplier);
        var result = excel.importRows(List.of(row(2, "AP", "S9", "1", "2020/9/30"),
            row(3, "AR", "C9", "2", "2020-09-30")));
        assertEquals(2, result.getSuccess());
        ArgumentCaptor<FinOpeningBo> values = ArgumentCaptor.forClass(FinOpeningBo.class);
        verify(openings, times(2)).save(values.capture());
        assertEquals(List.of("AR", "AP"), values.getAllValues().stream().map(FinOpeningBo::getPartyType).toList());
        assertTrue(values.getAllValues().stream().allMatch(value -> LocalDate.of(2020, 9, 30).equals(value.getOpeningDate())));
    }

    @Test void aliasesResolvingToSamePartyAreDuplicatesNotSilentUpserts() {
        ErpCustomer customer = new ErpCustomer(); customer.setId(9L);
        when(customers.selectOne(any(Wrapper.class))).thenReturn(customer);
        var result = excel.importRows(List.of(row(2, "AR", "C9", "1", "2020-09-30"),
            row(3, "AR", "c9", "2", "2020-09-30")));
        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("第2行"));
        verifyNoInteractions(openings);
    }

    @Test void legacyEmptyDateColumnDoesNotInventACutoffOrWriteAnyRow() {
        var result = excel.importRows(List.of(row(2, "AR", "C9", "1", null)));
        assertEquals(1, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("旧版模板"));
        verifyNoInteractions(openings, customers, suppliers);
    }

    @Test void invalidDatesAndOversizeNumbersAreRejectedWithoutAnyWrites() {
        ErpCustomer customer = new ErpCustomer(); customer.setId(9L);
        when(customers.selectOne(any(Wrapper.class))).thenReturn(customer);
        var result = excel.importRows(List.of(row(2, "AR", "C9", "1e100000", "2020-02-30"),
            row(3, "AR", "C9", "10000000000000000", LocalDate.now().plusDays(1).toString())));
        assertEquals(2, result.getFailed());
        assertTrue(result.getErrors().get(0).message().contains("格式必须是"));
        assertTrue(result.getErrors().get(1).message().contains("不能晚于今天"));
        verifyNoInteractions(openings);
    }

    @Test void failAfterEarlierSaveStillReturnsNoSuccessAndOriginalExcelErrorRow() {
        ErpCustomer customer = new ErpCustomer(); customer.setId(9L);
        ErpSupplier supplier = new ErpSupplier(); supplier.setId(9L);
        when(customers.selectOne(any(Wrapper.class))).thenReturn(customer);
        when(suppliers.selectOne(any(Wrapper.class))).thenReturn(supplier);
        when(openings.save(any())).thenReturn(new FinOpeningVo()).thenThrow(new ServiceException("已确认", 40902));
        var result = excel.importRows(List.of(row(2, "AP", "S9", "1", "2020-09-30"),
            row(3, "AR", "C9", "2", "2020-09-30")));
        assertEquals(0, result.getSuccess()); assertEquals(1, result.getFailed()); assertEquals(1, result.getSkipped());
        assertEquals(2, result.getErrors().get(0).row());
        assertTrue(result.getErrors().get(0).message().contains("整批已回滚"));
        // 代理事务回滚另由 MySQL/事务集成测试验；这里不伪称 mock 能撤销已保存的行。
    }

    @Test void sentinelAndValidationErrorDetailsAreBounded() {
        var result = excel.importRows(Collections.nCopies(2001, row(2, "AR", "C9", "1", "2020-09-30")));
        assertEquals(2001, result.getFailed()); assertTrue(result.getErrors().size() <= 100);
        assertTrue(result.getErrors().stream().allMatch(value -> value.message().length() <= 513));
        verifyNoInteractions(openings, customers, suppliers);
    }

    @Test void boundedReaderLimitBecomesRollbackResultBeforeAnyWrite() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        var input = new ByteArrayInputStream(new byte[0]);
        when(file.getInputStream()).thenReturn(input);
        try (var reader = mockStatic(ExcelRowReader.class)) {
            reader.when(() -> ExcelRowReader.read(input, FinOpeningImportRow.class))
                .thenThrow(new ExcelRowReader.ImportLimitException(17, "Excel 列数不能超过64列"));
            var error = assertThrows(FinOpeningExcelService.ImportRollbackException.class, () -> excel.importData(file));
            assertEquals(17, error.getResult().getErrors().get(0).row());
            assertEquals(0, error.getResult().getSuccess());
        }
        verifyNoInteractions(openings, customers, suppliers);
    }

    @Test void exportKeepsExplicitDatesAndHistoricalNullWithoutFutureDefaults() {
        FinOpeningVo old = new FinOpeningVo(); old.setPartyType("AR"); old.setOpeningDate(null);
        FinOpeningVo dated = new FinOpeningVo(); dated.setPartyType("AP"); dated.setOpeningDate(LocalDate.of(2020, 9, 30));
        var rows = excel.toExportRows(List.of(old, dated));
        assertNull(rows.get(0).getOpeningDate()); assertEquals("2020-09-30", rows.get(1).getOpeningDate());
        verifyNoInteractions(openings, customers, suppliers);
    }

    @Test void strictCellDateParsingDoesNotNormalizeInvalidDaysOrAcceptUnboundedInput() {
        List<String> errors = new ArrayList<>();
        assertNull(CellValues.date("2020-02-30", "截止日", true, errors));
        assertNull(CellValues.date("9".repeat(100000), "截止日", true, errors));
        assertEquals(2, errors.size());
        assertEquals(LocalDate.of(2020, 2, 29), CellValues.date("2020/2/29", "截止日", true, errors));
        assertEquals(2, errors.size());
    }

    private ExcelRow<FinOpeningImportRow> row(int number, String type, String code, String amount, String date) {
        FinOpeningImportRow row = new FinOpeningImportRow(); row.setPartyType(type); row.setPartyCode(code);
        row.setAmount(amount); row.setOpeningDate(date); return new ExcelRow<>(number, row);
    }
}
