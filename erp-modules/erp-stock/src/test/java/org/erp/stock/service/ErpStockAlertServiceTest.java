package org.erp.stock.service;

import org.dromara.common.core.exception.ServiceException;
import org.erp.stock.domain.vo.ErpStockAlertTopRow;
import org.erp.stock.mapper.ErpStockAlertMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ErpStockAlertServiceTest {
    private final ErpStockAlertMapper mapper = mock(ErpStockAlertMapper.class);
    private final ErpStockAlertService service = new ErpStockAlertService(mapper);

    @Test
    void topCounts_areNotTruncatedArraySizes_andFullListIsNotLoaded() {
        when(mapper.selectTopAlerts(null, 5)).thenReturn(List.of(row(1L, "HIGH", 501, "9"), row(2L, "LOW", 1234, "-8")));
        var result = service.top(null, 5);
        assertEquals(501, result.highCount());
        assertEquals(1234, result.lowCount());
        assertEquals(1, result.highTop().size());
        assertEquals(1, result.lowTop().size());
        assertEquals(new BigDecimal("-8"), result.lowTop().get(0).gap());
        verify(mapper, never()).selectAlerts(any(), any());
    }

    @Test
    void noAlertTypes_defaultToZero_withoutFakePlaceholderRows() {
        when(mapper.selectTopAlerts(99L, 5)).thenReturn(List.of());
        var empty = service.top(99L, 5);
        assertEquals(0, empty.lowCount());
        assertEquals(0, empty.highCount());
        assertTrue(empty.lowTop().isEmpty());
        assertTrue(empty.highTop().isEmpty());
    }

    @Test
    void maximumAndMinimum_areValidatedBeforeDatabase() {
        assertThrows(ServiceException.class, () -> service.top(null, 0));
        assertThrows(ServiceException.class, () -> service.top(null, 51));
        verifyNoInteractions(mapper);
    }

    private ErpStockAlertTopRow row(Long id, String type, long count, String gap) {
        var row = new ErpStockAlertTopRow();
        row.setProductId(id);
        row.setProductName("P" + id);
        row.setTotalQty(BigDecimal.TEN);
        row.setGap(new BigDecimal(gap));
        row.setAlertCount(count);
        row.setAlertType(type);
        return row;
    }
}
