package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.erp.fin.domain.FinArAp;
import org.erp.fin.domain.FinSourceNo;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.mapper.FinSourceNoMapper;
import org.erp.fin.service.IFinOpeningGuardService;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 来源按 type+id 分命名空间，再核对方向与源单往来；不以同 ID 覆盖其它来源。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinArApSourceBoundaryTest {
    @Mock private FinArApMapper mapper;
    @Mock private FinSourceNoMapper sources;
    @Mock private ErpCustomerMapper customers;
    @Mock private ErpSupplierMapper suppliers;
    @Mock private FinOpeningMapper openings;
    @Mock private IFinOpeningGuardService guard;
    private FinArApServiceImpl service;

    @BeforeEach void setup() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "source-boundary-test"), FinArAp.class);
        service = new FinArApServiceImpl(mapper, sources, customers, suppliers, openings, guard);
    }

    @Test void identicalSourceIdsAcrossAllFourTablesDoNotCollide() {
        List<FinArAp> rows = List.of(row(1, "AR", 9, "SALE", 77), row(2, "AR", 9, "SALE_RETURN", 77),
            row(3, "AP", 8, "PURCHASE", 77), row(4, "AP", 8, "PURCHASE_RETURN", 77),
            row(5, "AR", 9, "OPENING", 77));
        var page = new Page<FinArAp>(1, 20, 5); page.setRecords(rows);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(sources.selectSaleNos(List.of(77L))).thenReturn(List.of(no(77, 9, "XS-77")));
        when(sources.selectSaleReturnNos(List.of(77L))).thenReturn(List.of(no(77, 9, "XT-77")));
        when(sources.selectPurchaseNos(List.of(77L))).thenReturn(List.of(no(77, 8, "CG-77")));
        when(sources.selectPurchaseReturnNos(List.of(77L))).thenReturn(List.of(no(77, 8, "CT-77")));
        var result = service.selectPage(null, null, null, new PageQuery(20, 1));
        assertEquals(java.util.Arrays.asList("XS-77", "XT-77", "CG-77", "CT-77", null),
            result.getRows().stream().map(value -> value.getSourceNo()).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), result.getRows().stream().map(value -> value.getId()).toList());
    }

    @Test void scopeDropsForeignDirectionAndPartyAndUnknownSourcesStayUnknown() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            row(1, "AR", 9, "SALE", 77), row(2, "AP", 9, "PURCHASE", 77),
            row(3, "AR", 999, "SALE", 78), row(4, "AR", 9, "PURCHASE", 77),
            row(5, "AR", 9, "FUTURE", 77), row(6, "AR", 9, "SALE", 79)));
        // A damaged ledger points at a source owned by another party: never display its number.
        when(sources.selectSaleNos(List.of(77L, 79L))).thenReturn(List.of(no(77, 999, "PRIVATE")));
        var result = service.listSourceInfo(List.of(1L, 2L, 3L, 4L, 5L, 6L), "AR", 9L);
        assertEquals(List.of(1L, 4L, 5L, 6L), result.stream().map(value -> value.getArApId()).toList());
        assertTrue(result.stream().allMatch(value -> value.getSourceNo() == null));
        assertEquals("SALE", result.get(0).getSourceType());
        assertNull(result.get(1).getSourceType());
        assertNull(result.get(2).getSourceType());
        verify(sources).selectSaleNos(List.of(77L, 79L));
        verifyNoMoreInteractions(sources);
        verifyNoInteractions(customers, suppliers);
    }

    @Test void allBatchSqlUsesAtMost200DistinctIdsAndKeepsEveryRow() {
        List<FinArAp> rows = LongStream.rangeClosed(1, 201).mapToObj(id -> row(id, "AR", 9, "SALE", id)).toList();
        when(mapper.selectList(any(Wrapper.class))).thenReturn(rows.subList(0, 200), rows.subList(200, 201));
        when(sources.selectSaleNos(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            assertTrue(ids.size() <= 200);
            return ids.stream().map(id -> no(id, 9, "XS-" + id)).toList();
        });
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, 201).boxed().toList()); ids.add(1L); ids.add(null);
        var result = service.listSourceInfo(ids, "AR", 9L);
        assertEquals(201, result.size());
        assertEquals("XS-201", result.get(200).getSourceNo());
        ArgumentCaptor<Collection<Long>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(sources, times(2)).selectSaleNos(batches.capture());
        assertEquals(List.of(200, 1), batches.getAllValues().stream().map(Collection::size).toList());
        verify(mapper, times(2)).selectList(any(Wrapper.class));
    }

    @Test void unscopedLegacyLookupFailsClosedRatherThanAssumingUnknownPermissionIsZero() {
        assertThrows(ServiceException.class, () -> service.listSourceInfo(List.of(1L)));
        assertTrue(service.listSourceInfo(List.of()).isEmpty());
        verifyNoInteractions(mapper, sources, customers, suppliers);
    }

    private FinArAp row(long id, String type, long party, String source, long sourceId) {
        FinArAp row = new FinArAp(); row.setId(id); row.setPartyType(type); row.setPartyId(party);
        row.setSourceType(source); row.setSourceId(sourceId); row.setAmount(BigDecimal.TEN);
        row.setSettledAmount(BigDecimal.ZERO); row.setStatus("OPEN"); return row;
    }
    private FinSourceNo no(long id, long partyId, String number) {
        FinSourceNo row = new FinSourceNo(); row.setSourceId(id); row.setPartyId(partyId); row.setNo(number); return row;
    }
}
