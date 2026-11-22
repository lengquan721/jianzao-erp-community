package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.excel.utils.ExcelUtil;
import org.erp.fin.controller.FinOpeningController;
import org.erp.fin.domain.FinOpening;
import org.erp.fin.excel.FinOpeningExcelService;
import org.erp.fin.excel.FinOpeningExportVo;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.service.IFinArApService;
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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 service/controller/Excel 路径，替换数据库与最终 Excel 输出，不启动应用或 HTTP。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinOpeningReadBoundaryTest {

    @Mock private FinOpeningMapper openings;
    @Mock private ErpCustomerMapper customers;
    @Mock private ErpSupplierMapper suppliers;
    @Mock private IFinArApService ledger;
    @Mock private org.erp.fin.service.IFinOpeningGuardService openingGuard;
    private FinOpeningServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinOpeningServiceImpl(openings, customers, suppliers, ledger, openingGuard);
    }

    @Test
    void databasePageKeepsTrueTotalStatusDecimalDateAndBatchesOnlyCurrentPageNames() {
        FinOpening ar = row(41, "AR", 9, "DRAFT");
        FinOpening ap = row(42, "AP", 9, "CONFIRMED");
        FinOpening missing = row(43, "AR", 10, "CONFIRMED");
        ar.setOpeningDate(java.time.LocalDate.of(2020, 9, 30));
        ap.setOpeningDate(null);
        when(openings.countReadRows(null)).thenReturn(93L);
        when(openings.selectReadRows(null, 40, 20)).thenReturn(List.of(ar, ap, missing));
        when(customers.selectByIds(List.of(9L, 10L))).thenReturn(List.of(customer(9)));
        ErpSupplier supplier = new ErpSupplier();
        supplier.setId(9L);
        supplier.setName("同 ID 供应商");
        when(suppliers.selectByIds(List.of(9L))).thenReturn(List.of(supplier));

        var page = service.page(null, 3, 20);

        assertEquals(93L, page.getTotal());
        assertEquals(3, page.getRows().size());
        assertEquals("客户9", page.getRows().get(0).getPartyName());
        assertEquals("同 ID 供应商", page.getRows().get(1).getPartyName());
        assertNull(page.getRows().get(2).getPartyName(), "已删除或找不到名称不减少记录/total");
        assertEquals("DRAFT", page.getRows().get(0).getStatus());
        assertEquals("CONFIRMED", page.getRows().get(1).getStatus());
        assertEquals(ar.getAmount(), page.getRows().get(0).getAmount());
        assertSame(ar.getCreateTime(), page.getRows().get(0).getCreateTime());
        assertEquals(ar.getRemark(), page.getRows().get(0).getRemark());
        assertEquals(ar.getOpeningDate(), page.getRows().get(0).getOpeningDate());
        assertNull(page.getRows().get(1).getOpeningDate(), "历史 NULL 只读，不默认回填当前/未来日期");
        verify(customers).selectByIds(List.of(9L, 10L));
        verify(suppliers).selectByIds(List.of(9L));
        verify(customers, never()).selectById(any());
        verify(suppliers, never()).selectById(any());
        verify(openings, never()).selectList(any(Wrapper.class));
        verifyNoInteractions(ledger);
    }

    @Test
    void emptyOrFarBeyondLastPageKeepsTotalWithoutNameOrRowReads() {
        when(openings.countReadRows("AP")).thenReturn(2L);
        var page = service.page("AP", Integer.MAX_VALUE, 200);
        assertEquals(2L, page.getTotal());
        assertTrue(page.getRows().isEmpty());
        verify(openings, never()).selectReadRows(any(), anyLong(), anyInt());
        verifyNoInteractions(customers, suppliers, ledger);
    }

    @Test
    void invalidPagesFailBeforeAnyDatabaseRead() {
        for (int[] page : List.of(new int[]{0, 20}, new int[]{1, 0}, new int[]{1, -1}, new int[]{1, 201})) {
            assertThrows(ServiceException.class, () -> service.page("AR", page[0], page[1]));
        }
        verifyNoInteractions(openings, customers, suppliers, ledger);
    }

    @Test
    void legacyAndExportNameEnrichmentChunksDistinctIdsWithoutDroppingLaterIds() {
        List<FinOpening> rows = LongStream.rangeClosed(1, 201).mapToObj(id -> row(id, "AR", id, "DRAFT")).toList();
        when(openings.selectReadRows("AR", 0, 5001)).thenReturn(rows);
        when(customers.selectByIds(any())).thenAnswer(invocation -> {
            java.util.Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(this::customer).toList();
        });

        var result = service.listAll("AR");

        assertEquals(201, result.size());
        assertEquals("客户201", result.get(200).getPartyName());
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(customers, times(2)).selectByIds(ids.capture());
        assertEquals(200, ids.getAllValues().get(0).size());
        assertEquals(List.of(201L), List.copyOf(ids.getAllValues().get(1)));
        verifyNoInteractions(suppliers, ledger);
    }

    @Test
    void actualExportAt5000SendsAllRowsToExcelWriterNotFirstInteractivePage() {
        when(openings.selectReadRows("AR", 0, 5001)).thenReturn(Collections.nCopies(5000, row(1, "AR", 9, "DRAFT")));
        when(customers.selectByIds(List.of(9L))).thenReturn(List.of(customer(9)));
        var response = mock(HttpServletResponse.class);
        var excel = new FinOpeningExcelService(service, customers, suppliers);
        var controller = new FinOpeningController(service, excel);
        try (var writer = mockStatic(ExcelUtil.class)) {
            controller.export("AR", response);
            writer.verify(() -> ExcelUtil.exportExcel(
                argThat((List<FinOpeningExportVo> rows) -> rows.size() == 5000
                    && "C9".equals(rows.get(4999).getPartyCode())
                    && new BigDecimal("9007199254740993.01").equals(rows.get(4999).getAmount())),
                eq("期初往来"), eq(FinOpeningExportVo.class), same(response)));
        }
        verify(openings).selectReadRows("AR", 0, 5001);
        verify(openings, never()).countReadRows(any());
        // 一次名称批量、一次编码批量；5000 行只含同一 partyId，不逐行/逐 ID 回查。
        verify(customers, times(2)).selectByIds(List.of(9L));
        verify(customers, never()).selectById(any());
        verifyNoMoreInteractions(customers);
        verifyNoInteractions(suppliers, ledger);
    }

    @Test
    void actualExportAt5001FailsBeforeNameLookupOrAnyExcelResponseWrite() {
        when(openings.selectReadRows("AP", 0, 5001)).thenReturn(Collections.nCopies(5001, row(1, "AP", 9, "CONFIRMED")));
        var response = mock(HttpServletResponse.class);
        var excel = new FinOpeningExcelService(service, customers, suppliers);
        var controller = new FinOpeningController(service, excel);

        var error = assertThrows(ServiceException.class, () -> controller.export("AP", response));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("5000"));
        verifyNoInteractions(response, customers, suppliers, ledger);
    }

    @Test
    void oldListEnvelopeRemainsWholeAndBoundedForConsumersNotMigratedToPage() {
        when(openings.selectReadRows("AR", 0, 5001)).thenReturn(Collections.nCopies(201, row(1, "AR", 9, "DRAFT")));
        when(customers.selectByIds(List.of(9L))).thenReturn(List.of(customer(9)));
        var controller = new FinOpeningController(service, mock(FinOpeningExcelService.class));
        assertEquals(201, controller.list("AR").getData().size());
    }

    private FinOpening row(long id, String type, long partyId, String status) {
        var row = new FinOpening();
        row.setId(id);
        row.setPartyType(type);
        row.setPartyId(partyId);
        row.setStatus(status);
        row.setAmount(new BigDecimal("9007199254740993.01"));
        row.setRemark("原始备注");
        row.setCreateTime(new Date(1795132800123L));
        return row;
    }

    private ErpCustomer customer(long id) {
        var customer = new ErpCustomer();
        customer.setId(id);
        customer.setCode("C" + id);
        customer.setName("客户" + id);
        customer.setStatus(0); // 禁用不等于删除，名称仍沿用原 selectById 可见语义。
        return customer;
    }
}
