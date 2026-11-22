package org.erp.stock.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.stock.domain.bo.StockPage;
import org.erp.stock.domain.vo.ErpStockLogVo;
import org.erp.stock.mapper.ErpStockLogMapper;
import org.erp.stock.mapper.ErpStockMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class InventoryQueryBoundaryTest {
    ErpStockMapper stocks = mock(ErpStockMapper.class);
    ErpStockLogMapper logs = mock(ErpStockLogMapper.class);
    ErpProductMapper products = mock(ErpProductMapper.class);
    ErpSupplierMapper suppliers = mock(ErpSupplierMapper.class);
    ErpCustomerMapper customers = mock(ErpCustomerMapper.class);
    InventoryServiceImpl service = new InventoryServiceImpl(stocks, logs, products, suppliers, customers);
    LocalDate start = LocalDate.of(2026, 1, 1);
    LocalDate end = LocalDate.of(2026, 12, 31);

    @Test
    void logCursor_usesLastVisibleRow_notSentinel_andNamesOnlyVisibleRows() {
        var first = row(9L, end, "SUPPLIER", 3L);
        var second = row(8L, end, "NONE", 999L);
        var sentinel = row(7L, end, "SUPPLIER", 99L);
        when(logs.selectLogPage(1L, start, end, null, null, 3)).thenReturn(List.of(first, second, sentinel));
        var supplier = new ErpSupplier(); supplier.setId(3L); supplier.setName("供应商3");
        when(suppliers.selectByIds(List.of(3L))).thenReturn(List.of(supplier));
        var result = service.pageLogs(1L, start, end, 2, null, null);
        assertEquals(List.of(first, second), result.rows());
        assertTrue(result.hasMore());
        assertEquals(end, result.nextBeforeDate());
        assertEquals(8L, result.nextBeforeId());
        assertEquals("供应商3", first.getPartnerName());
        assertNull(second.getPartnerName());
        verify(suppliers).selectByIds(List.of(3L));
        verifyNoInteractions(customers, products);
        verify(logs, never()).selectList(any());
    }

    @Test
    void followingPage_andEmptyPage_keepDateWindowAndEndCursor() {
        var last = row(6L, end.minusDays(1), "NONE", null);
        when(logs.selectLogPage(1L, start, end, end, 8L, 3)).thenReturn(List.of(last));
        var result = service.pageLogs(1L, start, end, 2, end, 8L);
        assertEquals(1, result.rows().size());
        assertFalse(result.hasMore());
        assertNull(result.nextBeforeDate());
        assertNull(result.nextBeforeId());
        when(logs.selectLogPage(1L, start, end, end, 1L, 3)).thenReturn(List.of());
        var empty = service.pageLogs(1L, start, end, 2, end, 1L);
        assertTrue(empty.rows().isEmpty());
        assertFalse(empty.hasMore());
        verifyNoInteractions(suppliers, customers);
    }

    @Test
    void dateWindowAndCursorValidation_failsClosed() {
        assertThrows(ServiceException.class, () -> service.pageLogs(null, start, end, 50, null, null));
        assertThrows(ServiceException.class, () -> service.pageLogs(0L, start, end, 50, null, null));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, null, end, 50, null, null));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, start, null, 50, null, null));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, end, start, 50, null, null));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, start, start.plusDays(366), 50, null, null));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, start, end, 50, end, null));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, start, end, 50, null, 2L));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, start, end, 50, end, 0L));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, start, end, 50, start.minusDays(1), 2L));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, start, end, 50, end.plusDays(1), 2L));
        assertThrows(ServiceException.class, () -> service.pageLogs(1L, start, end, 101, null, null));
        verifyNoInteractions(logs, stocks, products, suppliers, customers);
    }

    @Test
    void maximumDateWindowAndOneDayWindow_areInclusive() {
        when(logs.selectLogPage(1L, start, start.plusDays(365), null, null, 2)).thenReturn(List.of());
        when(logs.selectLogPage(1L, start, start, null, null, 2)).thenReturn(List.of());
        assertTrue(service.pageLogs(1L, start, start.plusDays(365), 1, null, null).rows().isEmpty());
        assertTrue(service.pageLogs(1L, start, start, 1, null, null).rows().isEmpty());
    }

    @Test
    void quantityIds_areBoundedAndDeduplicated_notTruncated() {
        when(stocks.selectQuantityTotals(List.of(3L, 1L))).thenReturn(List.of());
        assertTrue(service.quantityTotals(List.of(3L, 1L, 3L)).isEmpty());
        verify(stocks).selectQuantityTotals(List.of(3L, 1L));
        assertThrows(ServiceException.class, () -> service.quantityTotals(null));
        assertThrows(ServiceException.class, () -> service.quantityTotals(List.of()));
        assertThrows(ServiceException.class, () -> service.quantityTotals(Collections.nCopies(201, 1L)));
        assertThrows(ServiceException.class, () -> service.quantityTotals(Arrays.asList(1L, null)));
        assertThrows(ServiceException.class, () -> service.quantityTotals(List.of(0L)));
    }

    @Test
    void pageOffset_doesNotOverflowIntegerAndCannotDisableLimit() {
        assertEquals((Integer.MAX_VALUE - 1L) * 100, StockPage.of(Integer.MAX_VALUE, 100).offset());
        assertThrows(ServiceException.class, () -> StockPage.of(-1, 50));
        assertThrows(ServiceException.class, () -> StockPage.of(1, -1));
    }

    private ErpStockLogVo row(Long id, LocalDate date, String type, Long partnerId) {
        var row = new ErpStockLogVo();
        row.setId(id); row.setBizDate(date); row.setPartnerType(type); row.setPartnerId(partnerId);
        return row;
    }
}
