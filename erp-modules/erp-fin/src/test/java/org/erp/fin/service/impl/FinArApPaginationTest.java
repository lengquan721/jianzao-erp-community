package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.erp.fin.controller.FinArApController;
import org.erp.fin.domain.FinArAp;
import org.erp.fin.domain.FinSourceNo;
import org.erp.fin.domain.vo.FinArApSummaryVo;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinSourceNoMapper;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 直接调用真实 service；只替换 Mapper，不启动应用、不连接数据库。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinArApPaginationTest {

    @Mock private FinArApMapper ledger;
    @Mock private FinSourceNoMapper sources;
    @Mock private ErpCustomerMapper customers;
    @Mock private ErpSupplierMapper suppliers;
    @Mock private org.erp.fin.mapper.FinOpeningMapper openingMapper;
    @Mock private org.erp.fin.service.IFinOpeningGuardService openingGuard;
    private FinArApServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinArApServiceImpl(ledger, sources, customers, suppliers, openingMapper, openingGuard);
    }

    @Test
    void summaryUsesRealGroupCountAndGlobalTotalsNotCurrentPageAmounts() {
        when(ledger.countSummaryParties("AR")).thenReturn(47L);
        var party = new FinArApSummaryVo.PartySummary();
        party.setPartyId(9L);
        party.setAmount(new BigDecimal("100.00"));
        party.setSettled(new BigDecimal("20.00"));
        party.setUnsettled(new BigDecimal("80.00"));
        party.setOpening(BigDecimal.ZERO);
        when(ledger.selectSummaryParties("AR", 20, 20)).thenReturn(List.of(party));
        when(ledger.selectSummaryTotals("AR")).thenReturn(totals());
        when(customers.selectByIds(List.of(9L))).thenReturn(List.of(customer(9L, "本页客户")));

        var result = service.summaryPage("AR", 2, 20);

        assertEquals(47L, result.getTotal());
        assertEquals(new BigDecimal("9100.25"), result.getTotalAmount());
        assertEquals(new BigDecimal("2200.00"), result.getSettledAmount());
        assertEquals(new BigDecimal("6900.25"), result.getUnsettledAmount());
        assertEquals(new BigDecimal("2000.00"), result.getOpeningAmount());
        assertEquals("AR", result.getPartyType());
        assertEquals(List.of(party), result.getParties());
        assertEquals("本页客户", party.getPartyName());
        verify(ledger).selectSummaryParties("AR", 20, 20);
        verify(ledger, never()).selectList(any(Wrapper.class));
        verify(customers).selectByIds(List.of(9L));
        verifyNoMoreInteractions(customers);
        verifyNoInteractions(sources, suppliers);
    }

    @Test
    void outOfRangeSummaryPageStillReturnsGlobalAmountsAndRealTotal() {
        when(ledger.countSummaryParties("AP")).thenReturn(3L);
        when(ledger.selectSummaryTotals("AP")).thenReturn(totals());

        var result = service.summaryPage("AP", Integer.MAX_VALUE, 200);

        assertEquals(3L, result.getTotal());
        assertTrue(result.getParties().isEmpty());
        assertEquals(new BigDecimal("6900.25"), result.getUnsettledAmount());
        verify(ledger, never()).selectSummaryParties(anyString(), anyLong(), anyInt());
        verifyNoInteractions(sources, customers, suppliers);
    }

    @Test
    void detailPageKeepsSourceNoBusinessDateSettledNegativeAndNullDateRows() {
        var sale = row(91L, "SALE", "300.00", "300.00", "SETTLED");
        sale.setSourceId(101L);
        Date bizDate = new Date(1795132800123L);
        sale.setBizDate(bizDate);
        var returned = row(92L, "SALE_RETURN", "-50.25", "0.00", "OPEN");
        returned.setSourceId(102L);
        returned.setBizDate(null);
        when(ledger.countByParty("AR", 9L)).thenReturn(43L);
        when(ledger.selectByPartyPage("AR", 9L, 40, 20)).thenReturn(List.of(sale, returned));
        when(sources.selectSaleNos(List.of(101L))).thenReturn(List.of(sourceNo(101L, "XS-101")));
        when(sources.selectSaleReturnNos(List.of(102L))).thenReturn(List.of(sourceNo(102L, "XT-102")));
        when(customers.selectByIds(List.of(9L))).thenReturn(List.of(customer(9L, "明细客户")));

        var result = service.pageByParty("AR", 9L, 3, 20);

        assertEquals(43L, result.getTotal());
        assertEquals(List.of(91L, 92L), result.getRows().stream().map(r -> r.getId()).toList());
        var first = result.getRows().get(0);
        assertEquals("XS-101", first.getSourceNo());
        assertSame(bizDate, first.getBizDate());
        assertEquals("SETTLED", first.getStatus());
        assertEquals(new BigDecimal("0.00"), first.getUnsettledAmount());
        var second = result.getRows().get(1);
        assertEquals("XT-102", second.getSourceNo());
        assertNull(second.getBizDate());
        assertEquals(new BigDecimal("-50.25"), second.getUnsettledAmount());
        assertEquals("明细客户", first.getPartyName());
        assertEquals("明细客户", second.getPartyName());
        verify(sources).selectSaleNos(List.of(101L));
        verify(sources).selectSaleReturnNos(List.of(102L));
        verify(customers).selectByIds(List.of(9L));
        verifyNoMoreInteractions(customers);
        verifyNoInteractions(suppliers);
        verify(ledger, never()).selectList(any(Wrapper.class));
    }

    @Test
    void emptyDetailPageDoesNotLoadNamesOrSourcesOrLoseTotal() {
        when(ledger.countByParty("AP", 8L)).thenReturn(2L);

        var result = service.pageByParty("AP", 8L, 2, 20);

        assertEquals(2L, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verify(ledger, never()).selectByPartyPage(anyString(), anyLong(), anyLong(), anyInt());
        verifyNoInteractions(sources, customers, suppliers);
    }

    @Test
    void legacyDetailReturnsAllRowsAtLimitWithOneDeduplicatedNameBatch() {
        when(ledger.selectByPartyPage("AR", 9L, 0, 5001))
            .thenReturn(Collections.nCopies(5000, row(1L, "OPENING", "10.00", "0.00", "OPEN")));
        when(customers.selectByIds(List.of(9L))).thenReturn(List.of(customer(9L, "五千行同一客户")));

        var result = service.listByParty("AR", 9L);

        assertEquals(5000, result.size());
        assertTrue(result.stream().allMatch(vo -> "五千行同一客户".equals(vo.getPartyName())));
        verify(ledger).selectByPartyPage("AR", 9L, 0, 5001);
        verify(customers).selectByIds(List.of(9L));
        verifyNoMoreInteractions(customers);
        verifyNoInteractions(sources, suppliers);
    }

    @Test
    void legacyOversizeDetailRejectsBeforeEnrichmentRatherThanTruncating() {
        when(ledger.selectByPartyPage("AR", 9L, 0, 5001))
            .thenReturn(Collections.nCopies(5001, row(1L, "OPENING", "10.00", "0.00", "OPEN")));

        var error = assertThrows(ServiceException.class, () -> service.listByParty("AR", 9L));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("5000"));
        verifyNoInteractions(sources, customers, suppliers);
    }

    @Test
    void allocatableFilterUsesItsOwnQueryAndPreservesTheLegacyEnvelope() {
        var candidate = row(21L, "OPENING", "10.00", "3.50", "PARTIAL");
        var historical = row(22L, "OPENING", "5.00", "5.00", "SETTLED");
        when(ledger.selectAllocatableByParty("AR", 9L, 5001)).thenReturn(List.of(candidate));
        when(ledger.selectByPartyPage("AR", 9L, 0, 5001)).thenReturn(List.of(historical, candidate));
        var controller = new FinArApController(service);

        var selected = controller.detail("AR", 9L, true).getData();

        assertEquals(1, selected.size());
        assertEquals(21L, selected.get(0).getId());
        assertEquals(new BigDecimal("6.50"), selected.get(0).getUnsettledAmount());
        verify(ledger, never()).selectByPartyPage(anyString(), anyLong(), anyLong(), anyInt());
        assertEquals(2, controller.detail("AR", 9L, false).getData().size());
    }

    @Test
    void moreThan5000EligibleRowsRejectsBeforeEnrichmentWithoutFallingBackToHistory() {
        when(ledger.selectAllocatableByParty("AP", 8L, 5001))
            .thenReturn(Collections.nCopies(5001, row(1L, "OPENING", "10.00", "0.00", "OPEN")));

        var error = assertThrows(ServiceException.class, () -> service.listAllocatableByParty("AP", 8L));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("5000"));
        verify(ledger, never()).selectByPartyPage(anyString(), anyLong(), anyLong(), anyInt());
        verifyNoInteractions(sources, customers, suppliers);
    }

    @Test
    void legacyOversizeSummaryRejectsBeforeTotalsOrNameLookups() {
        when(ledger.selectSummaryParties("AR", 0, 5001))
            .thenReturn(Collections.nCopies(5001, new FinArApSummaryVo.PartySummary()));

        var error = assertThrows(ServiceException.class, () -> service.summary("AR"));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("/summary/page"));
        verify(ledger, never()).selectSummaryTotals(anyString());
        verifyNoInteractions(sources, customers, suppliers);
    }

    @Test
    void invalidPagesAndPartyIdsFailBeforeAnyRead() {
        for (int[] page : List.of(new int[]{0, 20}, new int[]{1, 0}, new int[]{1, -1}, new int[]{1, 201})) {
            assertThrows(ServiceException.class, () -> service.summaryPage("AR", page[0], page[1]));
            assertThrows(ServiceException.class, () -> service.pageByParty("AP", 8L, page[0], page[1]));
        }
        assertThrows(ServiceException.class, () -> service.summaryPage("XX", 1, 20));
        assertThrows(ServiceException.class, () -> service.pageByParty("AR", null, 1, 20));
        assertThrows(ServiceException.class, () -> service.listByParty("AR", 0L));
        verifyNoInteractions(ledger, sources, customers, suppliers);
    }

    @Test
    void summaryWith201PartiesLoadsOnlyEachReturnedPageInOneNameBatch() {
        var parties = IntStream.rangeClosed(1, 201).mapToObj(id -> {
            var party = new FinArApSummaryVo.PartySummary();
            party.setPartyId((long) id);
            party.setAmount(new BigDecimal("10.00"));
            return party;
        }).toList();
        var firstPage = parties.subList(0, 200);
        var lastPage = parties.subList(200, 201);
        var firstIds = firstPage.stream().map(FinArApSummaryVo.PartySummary::getPartyId).toList();
        when(ledger.countSummaryParties("AR")).thenReturn(201L);
        when(ledger.selectSummaryParties("AR", 0, 200)).thenReturn(firstPage);
        when(ledger.selectSummaryParties("AR", 200, 200)).thenReturn(lastPage);
        when(ledger.selectSummaryTotals("AR")).thenReturn(totals());
        when(customers.selectByIds(firstIds)).thenReturn(firstIds.stream()
            .map(id -> customer(id, "客户-" + id)).toList());
        when(customers.selectByIds(List.of(201L))).thenReturn(List.of(customer(201L, "客户-201")));

        var first = service.summaryPage("AR", 1, 200);
        var last = service.summaryPage("AR", 2, 200);

        assertEquals(201L, first.getTotal());
        assertEquals(201L, last.getTotal());
        assertEquals(200, first.getParties().size());
        for (int i = 0; i < 200; i++) {
            assertEquals("客户-" + (i + 1), first.getParties().get(i).getPartyName());
        }
        assertEquals(1, last.getParties().size());
        assertEquals("客户-201", last.getParties().get(0).getPartyName());
        assertEquals(new BigDecimal("9100.25"), first.getTotalAmount());
        assertEquals(new BigDecimal("9100.25"), last.getTotalAmount());
        verify(customers).selectByIds(firstIds);
        verify(customers).selectByIds(List.of(201L));
        verifyNoMoreInteractions(customers);
        verifyNoInteractions(sources, suppliers);
        verify(ledger).selectSummaryParties("AR", 0, 200);
        verify(ledger).selectSummaryParties("AR", 200, 200);
        verify(ledger, never()).selectList(any(Wrapper.class));
    }

    @Test
    void selectPageDeduplicatesWithinTypeAndKeepsSameIdMissingAndNullNamesSeparate() {
        var rows = List.of(
            partyRow(1L, "AR", 9L), partyRow(2L, "AR", 9L),
            partyRow(3L, "AP", 9L), partyRow(4L, "AP", 9L),
            partyRow(5L, "AR", 99L), partyRow(6L, "AP", 99L),
            partyRow(7L, "AR", null), partyRow(8L, "AP", 10L),
            partyRow(9L, "XX", 9L), partyRow(10L, null, 9L), partyRow(11L, "AP", null));
        var page = new Page<FinArAp>(2, 20, 201);
        page.setRecords(rows);
        when(ledger.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(customers.selectByIds(List.of(9L, 99L))).thenReturn(List.of(customer(9L, "同ID客户")));
        when(suppliers.selectByIds(List.of(9L, 99L, 10L)))
            .thenReturn(List.of(supplier(10L, null), supplier(9L, "同ID供应商")));

        var result = service.selectPage(null, null, null, new PageQuery(20, 2));

        assertEquals(201L, result.getTotal());
        assertEquals(rows.stream().map(FinArAp::getId).toList(),
            result.getRows().stream().map(vo -> vo.getId()).toList());
        assertEquals("同ID客户", result.getRows().get(0).getPartyName());
        assertEquals("同ID客户", result.getRows().get(1).getPartyName());
        assertEquals("同ID供应商", result.getRows().get(2).getPartyName());
        assertEquals("同ID供应商", result.getRows().get(3).getPartyName());
        for (int index : List.of(4, 5, 6, 7, 10)) {
            assertNull(result.getRows().get(index).getPartyName());
        }
        assertEquals("同ID供应商", result.getRows().get(8).getPartyName());
        assertEquals("同ID供应商", result.getRows().get(9).getPartyName());
        assertEquals(new BigDecimal("12.34"), result.getRows().get(0).getAmount());
        assertEquals(new BigDecimal("2.34"), result.getRows().get(0).getSettledAmount());
        assertTrue(result.getRows().stream()
            .allMatch(vo -> new BigDecimal("10.00").equals(vo.getUnsettledAmount())));
        verify(customers).selectByIds(List.of(9L, 99L));
        verify(suppliers).selectByIds(List.of(9L, 99L, 10L));
        verifyNoMoreInteractions(customers, suppliers);
        verifyNoInteractions(sources);
    }

    @Test
    void emptySelectPageDoesNotReadNamesOrSourcesAndKeepsTotal() {
        when(ledger.selectPage(any(Page.class), any(Wrapper.class)))
            .thenReturn(new Page<FinArAp>(2, 20, 3));

        var result = service.selectPage(null, null, null, new PageQuery(20, 2));

        assertEquals(3L, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verifyNoInteractions(sources, customers, suppliers);
    }

    @Test
    void emptyLegacySummaryAndDetailDoNotReadNamesOrSources() {
        when(ledger.selectSummaryParties("AP", 0, 5001)).thenReturn(List.of());
        when(ledger.selectSummaryTotals("AP")).thenReturn(totals());
        when(ledger.selectByPartyPage("AR", 9L, 0, 5001)).thenReturn(List.of());

        var summary = service.summary("AP");
        var detail = service.listByParty("AR", 9L);

        assertTrue(summary.getParties().isEmpty());
        assertEquals(new BigDecimal("9100.25"), summary.getTotalAmount());
        assertTrue(detail.isEmpty());
        verifyNoInteractions(sources, customers, suppliers);
    }

    private FinArAp partyRow(long id, String partyType, Long partyId) {
        var row = row(id, "OPENING", "12.34", "2.34", "PARTIAL");
        row.setPartyType(partyType);
        row.setPartyId(partyId);
        return row;
    }

    private ErpCustomer customer(long id, String name) {
        var customer = new ErpCustomer();
        customer.setId(id);
        customer.setName(name);
        return customer;
    }

    private ErpSupplier supplier(long id, String name) {
        var supplier = new ErpSupplier();
        supplier.setId(id);
        supplier.setName(name);
        return supplier;
    }

    private FinArApSummaryVo totals() {
        var vo = new FinArApSummaryVo();
        vo.setTotalAmount(new BigDecimal("9100.25"));
        vo.setSettledAmount(new BigDecimal("2200.00"));
        vo.setUnsettledAmount(new BigDecimal("6900.25"));
        vo.setOpeningAmount(new BigDecimal("2000.00"));
        return vo;
    }

    private FinArAp row(long id, String sourceType, String amount, String settled, String status) {
        var row = new FinArAp();
        row.setId(id);
        row.setPartyType("AR");
        row.setPartyId(9L);
        row.setSourceType(sourceType);
        row.setAmount(new BigDecimal(amount));
        row.setSettledAmount(new BigDecimal(settled));
        row.setStatus(status);
        return row;
    }

    private FinSourceNo sourceNo(long id, String no) {
        var row = new FinSourceNo();
        row.setSourceId(id);
        row.setPartyId(9L);
        row.setNo(no);
        return row;
    }
}
