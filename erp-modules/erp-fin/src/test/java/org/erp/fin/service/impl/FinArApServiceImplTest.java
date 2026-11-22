package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * FinArApServiceImpl 单元测试
 *
 * 覆盖：销售→AR、采购→AP（含运杂费总额）、期初 OPENING（D1，重录冲旧）、
 * 幂等（重复生成 40902）、冲销（settled>0 抛 40904 / 无台账幂等）、
 * 汇总口径（总额/已结/未结/期初拆分）与参数校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class FinArApServiceImplTest {

    private static final Date BIZ_DATE = new Date(0);

    @Mock
    private FinArApMapper arApMapper;
    @Mock
    private FinSourceNoMapper sourceNoMapper;
    @Mock
    private ErpCustomerMapper customerMapper;
    @Mock
    private ErpSupplierMapper supplierMapper;

    @Mock private org.erp.fin.mapper.FinOpeningMapper openingMapper;
    @Mock private org.erp.fin.service.IFinOpeningGuardService openingGuard;
    private FinArApServiceImpl service;

    @BeforeEach
    void setUp() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "fin-ar-ap-source-test"), FinArAp.class);
        service = new FinArApServiceImpl(arApMapper, sourceNoMapper, customerMapper, supplierMapper, openingMapper, openingGuard);
        when(arApMapper.insert(any(FinArAp.class))).thenReturn(1);
        when(arApMapper.updateById(any(FinArAp.class))).thenReturn(1);
        when(arApMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(arApMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(sourceNoMapper.selectSaleNos(any())).thenReturn(List.of());
        when(sourceNoMapper.selectPurchaseNos(any())).thenReturn(List.of());
    }

    // ---------- 生成 ----------

    @Test
    void createBySale_writesArLedger() {
        service.createBySale(101L, "XS001", 9L, new BigDecimal("500.00"), BIZ_DATE);

        ArgumentCaptor<FinArAp> captor = ArgumentCaptor.forClass(FinArAp.class);
        verify(arApMapper).insert(captor.capture());
        FinArAp row = captor.getValue();
        assertEquals("AR", row.getPartyType());
        assertEquals(9L, row.getPartyId());
        assertEquals("SALE", row.getSourceType());
        assertEquals(101L, row.getSourceId());
        assertEquals(0, new BigDecimal("500.00").compareTo(row.getAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getSettledAmount()));
        assertEquals("OPEN", row.getStatus());
    }

    @Test
    void createByPurchase_writesApLedgerWithFreight() {
        service.createByPurchase(201L, "CG001", 8L, new BigDecimal("1130.00"), BIZ_DATE);

        ArgumentCaptor<FinArAp> captor = ArgumentCaptor.forClass(FinArAp.class);
        verify(arApMapper).insert(captor.capture());
        FinArAp row = captor.getValue();
        assertEquals("AP", row.getPartyType());
        assertEquals("PURCHASE", row.getSourceType());
        assertEquals(0, new BigDecimal("1130.00").compareTo(row.getAmount()));
    }

    @Test
    void create_duplicateSource_rejected40902() {
        when(arApMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.createBySale(101L, "XS001", 9L, new BigDecimal("500.00"), BIZ_DATE));
        assertEquals(40902, ex.getCode());
        verify(arApMapper, never()).insert(any(FinArAp.class));
    }

    @Test
    void create_negativeAmount_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.createBySale(101L, "XS001", 9L, new BigDecimal("-1"), BIZ_DATE));
        assertTrue(ex.getMessage().contains("不能为负"));
        verify(arApMapper, never()).insert(any(FinArAp.class));
    }

    // ---------- 期初（D1） ----------

    @Test
    void createOpening_writesOpeningLedger() {
        service.createOpening("AR", 9L, new BigDecimal("2000.00"), 301L, BIZ_DATE);

        ArgumentCaptor<FinArAp> captor = ArgumentCaptor.forClass(FinArAp.class);
        verify(arApMapper).insert(captor.capture());
        FinArAp row = captor.getValue();
        assertEquals("AR", row.getPartyType());
        assertEquals("OPENING", row.getSourceType());
        assertEquals(301L, row.getSourceId());
    }

    @Test
    void createOpening_invalidPartyType_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.createOpening("XX", 9L, BigDecimal.TEN, 301L, BIZ_DATE));
        assertTrue(ex.getMessage().contains("AR 或 AP"));
    }

    @Test
    void createOpening_rerecord_voidsOldOpening() {
        FinArAp old = arRow(1L, "AR", 9L, "OPENING", 300L, "2000.00", "0", "OPEN");
        when(arApMapper.selectList(any(Wrapper.class))).thenReturn(List.of(old));
        when(arApMapper.selectByIdForUpdate(1L)).thenReturn(old);

        service.createOpening("AR", 9L, new BigDecimal("2500.00"), 301L, BIZ_DATE);

        assertEquals("VOID", old.getStatus());
        verify(arApMapper).updateById(old);
        ArgumentCaptor<FinArAp> captor = ArgumentCaptor.forClass(FinArAp.class);
        verify(arApMapper).insert(captor.capture());
        assertEquals(301L, captor.getValue().getSourceId());
    }

    @Test
    void createOpening_oldSettled_rejected40904() {
        FinArAp old = arRow(1L, "AR", 9L, "OPENING", 300L, "2000.00", "500.00", "PARTIAL");
        when(arApMapper.selectList(any(Wrapper.class))).thenReturn(List.of(old));
        when(arApMapper.selectByIdForUpdate(1L)).thenReturn(old);

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.createOpening("AR", 9L, new BigDecimal("2500.00"), 301L, BIZ_DATE));
        assertEquals(40904, ex.getCode());
        verify(arApMapper, never()).insert(any(FinArAp.class));
    }

    // ---------- 冲销 ----------

    @Test
    void voidBySource_settledRows_rejected40904() {
        FinArAp ledger = arRow(1L, "AR", 9L, "SALE", 101L, "500.00", "100.00", "PARTIAL");
        when(arApMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ledger));
        when(arApMapper.selectByIdForUpdate(1L)).thenReturn(ledger);

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.voidBySource("SALE", 101L));
        assertEquals(40904, ex.getCode());
        verify(arApMapper, never()).updateById(any(FinArAp.class));
    }

    @Test
    void voidBySource_unsettled_voidsAll() {
        FinArAp a1 = arRow(1L, "AR", 9L, "SALE", 101L, "300.00", "0", "OPEN");
        FinArAp a2 = arRow(2L, "AR", 9L, "SALE", 101L, "200.00", "0", "OPEN");
        when(arApMapper.selectList(any(Wrapper.class))).thenReturn(List.of(a1, a2));
        when(arApMapper.selectByIdForUpdate(1L)).thenReturn(a1);
        when(arApMapper.selectByIdForUpdate(2L)).thenReturn(a2);

        service.voidBySource("SALE", 101L);

        assertEquals("VOID", a1.getStatus());
        assertEquals("VOID", a2.getStatus());
        verify(arApMapper).updateById(a1);
        verify(arApMapper).updateById(a2);
    }

    @Test
    void voidBySource_noLedger_idempotentSuccess() {
        service.voidBySource("SALE", 999L);
        verify(arApMapper, never()).updateById(any(FinArAp.class));
    }

    // ---------- 汇总（S8） ----------

    @Test
    void summary_aggregatesTotalsAndParties() {
        // 数据库保留原口径：SALE 3000 + OPENING 2000 + SETTLED 500；不读全量台账到 Java。
        when(arApMapper.selectSummaryTotals("AR")).thenReturn(totals("5500.00", "1500.00", "4000.00", "2000.00"));
        when(arApMapper.selectSummaryParties("AR", 0, 5001)).thenReturn(List.of(
            partySummary(9L, "5000.00", "1000.00", "4000.00", "2000.00"),
            partySummary(10L, "500.00", "500.00", "0.00", "0.00")
        ));
        when(customerMapper.selectByIds(List.of(9L, 10L))).thenReturn(List.of(
            customer(9L, "示例机械"), customer(10L, "恒达设备")));

        var vo = service.summary("AR");

        // VOID 不参与：总额 = 3000 + 2000 + 500
        assertEquals(0, new BigDecimal("5500.00").compareTo(vo.getTotalAmount()));
        assertEquals(0, new BigDecimal("1500.00").compareTo(vo.getSettledAmount()));
        assertEquals(0, new BigDecimal("4000.00").compareTo(vo.getUnsettledAmount()));
        // 期初拆分
        assertEquals(0, new BigDecimal("2000.00").compareTo(vo.getOpeningAmount()));
        assertEquals(2, vo.getParties().size());

        var p9 = vo.getParties().get(0);
        assertEquals("示例机械", p9.getPartyName());
        assertEquals(0, new BigDecimal("5000.00").compareTo(p9.getAmount()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(p9.getSettled()));
        assertEquals(0, new BigDecimal("4000.00").compareTo(p9.getUnsettled()));
        assertEquals(0, new BigDecimal("2000.00").compareTo(p9.getOpening()));
        assertEquals("恒达设备", vo.getParties().get(1).getPartyName());
        verify(customerMapper).selectByIds(List.of(9L, 10L));
        verifyNoMoreInteractions(customerMapper);
        verifyNoInteractions(supplierMapper);
    }

    @Test
    void summary_invalidPartyType_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.summary("XX"));
        assertTrue(ex.getMessage().contains("AR 或 AP"));
    }

    @Test
    void summary_ap_resolvesSupplierNames() {
        when(arApMapper.selectSummaryTotals("AP")).thenReturn(totals("1130.00", "0.00", "1130.00", "0.00"));
        when(arApMapper.selectSummaryParties("AP", 0, 5001)).thenReturn(List.of(
            partySummary(8L, "1130.00", "0.00", "1130.00", "0.00")
        ));
        when(supplierMapper.selectByIds(List.of(8L))).thenReturn(List.of(supplier(8L, "钢材供应商")));

        var vo = service.summary("AP");

        assertEquals(1, vo.getParties().size());
        assertEquals("钢材供应商", vo.getParties().get(0).getPartyName());
        verify(supplierMapper).selectByIds(List.of(8L));
        verifyNoMoreInteractions(supplierMapper);
        verifyNoInteractions(customerMapper);
    }

    @Test
    void summary_201SuppliersUseOneBatchAndKeepMissingNamesAndMoneyScale() {
        var parties = IntStream.rangeClosed(1, 201)
            .mapToObj(id -> partySummary(id, "10.00", "1.00", "9.00", "0.00")).toList();
        var ids = parties.stream().map(FinArApSummaryVo.PartySummary::getPartyId).toList();
        var supplierRows = IntStream.rangeClosed(1, 200)
            .mapToObj(id -> supplier(id, id == 200 ? null : "供应商-" + id)).toList();
        when(arApMapper.selectSummaryParties("AP", 0, 5001)).thenReturn(parties);
        when(arApMapper.selectSummaryTotals("AP"))
            .thenReturn(totals("1234.565", "200.005", "1034.560", "12.340"));
        when(supplierMapper.selectByIds(ids.subList(0, 200))).thenReturn(supplierRows);
        when(supplierMapper.selectByIds(ids.subList(200, 201))).thenReturn(List.of());

        var result = service.summary("AP");

        assertEquals(201, result.getParties().size());
        for (int i = 0; i < 199; i++) {
            assertEquals("供应商-" + (i + 1), result.getParties().get(i).getPartyName());
        }
        assertNull(result.getParties().get(199).getPartyName()); // 有记录但名称为空
        assertNull(result.getParties().get(200).getPartyName()); // 主数据缺失，不回查
        assertEquals(new BigDecimal("1234.57"), result.getTotalAmount());
        assertEquals(new BigDecimal("200.01"), result.getSettledAmount());
        assertEquals(new BigDecimal("1034.56"), result.getUnsettledAmount());
        assertEquals(new BigDecimal("12.34"), result.getOpeningAmount());
        assertEquals(new BigDecimal("10.00"), result.getParties().get(0).getAmount());
        verify(supplierMapper).selectByIds(ids.subList(0, 200));
        verify(supplierMapper).selectByIds(ids.subList(200, 201));
        verifyNoMoreInteractions(supplierMapper);
        verifyNoInteractions(customerMapper, sourceNoMapper);
        verify(arApMapper).selectSummaryParties("AP", 0, 5001);
        verify(arApMapper, never()).selectList(any(Wrapper.class));
    }

    // ---------- 来源单号（B3） ----------

    @Test
    void listByParty_销售来源填充销售单号() {
        when(arApMapper.selectByPartyPage("AR", 9L, 0, 5001)).thenReturn(List.of(
            arRow(1L, "AR", 9L, "SALE", 101L, "500.00", "0", "OPEN")
        ));
        when(sourceNoMapper.selectSaleNos(any())).thenReturn(List.of(sourceNo(101L, "XS20260101001")));

        var voList = service.listByParty("AR", 9L);

        assertEquals(1, voList.size());
        assertEquals("XS20260101001", voList.get(0).getSourceNo());
    }

    @Test
    void listByParty_采购来源填充采购单号() {
        when(arApMapper.selectByPartyPage("AP", 8L, 0, 5001)).thenReturn(List.of(
            arRow(1L, "AP", 8L, "PURCHASE", 201L, "1130.00", "0", "OPEN")
        ));
        when(sourceNoMapper.selectPurchaseNos(any())).thenReturn(List.of(sourceNo(201L, "CG20260101001")));

        var voList = service.listByParty("AP", 8L);

        assertEquals("CG20260101001", voList.get(0).getSourceNo());
    }

    @Test
    void listByParty_期初来源无单号且不查业务单表() {
        when(arApMapper.selectByPartyPage("AR", 9L, 0, 5001)).thenReturn(List.of(
            arRow(1L, "AR", 9L, "OPENING", 301L, "2000.00", "0", "OPEN")
        ));

        var voList = service.listByParty("AR", 9L);

        assertNull(voList.get(0).getSourceNo());
        verify(sourceNoMapper, never()).selectSaleNos(any());
        verify(sourceNoMapper, never()).selectPurchaseNos(any());
    }

    @Test
    void listByParty_批量查询来源单号且只查一次() {
        when(arApMapper.selectByPartyPage("AR", 9L, 0, 5001)).thenReturn(List.of(
            arRow(1L, "AR", 9L, "SALE", 101L, "500.00", "0", "OPEN"),
            arRow(2L, "AR", 9L, "SALE", 102L, "300.00", "0", "OPEN")
        ));
        when(sourceNoMapper.selectSaleNos(any())).thenReturn(List.of(
            sourceNo(101L, "XS001"), sourceNo(102L, "XS002")));

        var voList = service.listByParty("AR", 9L);

        assertEquals("XS001", voList.get(0).getSourceNo());
        assertEquals("XS002", voList.get(1).getSourceNo());
        ArgumentCaptor<java.util.Collection<Long>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sourceNoMapper, org.mockito.Mockito.times(1)).selectSaleNos(captor.capture());
        assertEquals(List.of(101L, 102L), List.copyOf(captor.getValue()));
    }

    @Test
    void getBySource_keepsSingleLookupAndOriginalNullAndNonArSemantics() {
        var ar = arRow(1L, "AR", 9L, "SALE", 101L, "12.34", "2.34", "PARTIAL");
        var ap = arRow(2L, "AP", 9L, "PURCHASE", 102L, "12.34", "2.34", "PARTIAL");
        var missing = arRow(3L, "AR", 99L, "SALE", 103L, "12.34", "2.34", "PARTIAL");
        var noId = arRow(4L, "AR", 9L, "OPENING", 104L, "12.34", "2.34", "PARTIAL");
        noId.setPartyId(null);
        var nonAr = arRow(5L, "XX", 9L, "OPENING", 105L, "12.34", "2.34", "PARTIAL");
        when(arApMapper.selectOne(any(Wrapper.class)))
            .thenReturn(ar).thenReturn(ap).thenReturn(missing).thenReturn(noId).thenReturn(nonAr).thenReturn(null);
        when(customerMapper.selectById(9L)).thenReturn(customer(9L, "单详情客户"));
        when(supplierMapper.selectById(9L)).thenReturn(supplier(9L, "单详情供应商"));

        var arVo = service.getBySource("SALE", 101L);

        assertEquals("单详情客户", arVo.getPartyName());
        assertEquals(new BigDecimal("12.34"), arVo.getAmount());
        assertEquals(new BigDecimal("2.34"), arVo.getSettledAmount());
        assertEquals(new BigDecimal("10.00"), arVo.getUnsettledAmount());
        assertNull(arVo.getSourceNo()); // 单详情原本不加载来源单号
        assertEquals("单详情供应商", service.getBySource("PURCHASE", 102L).getPartyName());
        assertNull(service.getBySource("SALE", 103L).getPartyName());
        assertNull(service.getBySource("OPENING", 104L).getPartyName());
        assertEquals("单详情供应商", service.getBySource("OPENING", 105L).getPartyName());
        assertNull(service.getBySource("SALE", 999L));
        verify(customerMapper).selectById(9L);
        verify(customerMapper).selectById(99L);
        verify(supplierMapper, times(2)).selectById(9L);
        verifyNoMoreInteractions(customerMapper, supplierMapper);
        verifyNoInteractions(sourceNoMapper);
    }

    @Test
    void countBusinessBefore_ar_countsSalesAndReceipts() {
        when(openingMapper.countLedgerBeforeForUpdate("AR", 9L, BIZ_DATE)).thenReturn(2L);
        when(openingMapper.countReceiptsBeforeForUpdate(9L, BIZ_DATE)).thenReturn(1L);
        assertEquals(3L, service.countBusinessBefore("AR", 9L, BIZ_DATE));
        verify(openingGuard).lockParty("AR", 9L);
        verify(openingMapper).countReceiptsBeforeForUpdate(9L, BIZ_DATE);
        verify(openingMapper, never()).countPaymentsBeforeForUpdate(any(), any());
    }

    @Test
    void countBusinessBefore_ap_countsPurchaseAndPayments() {
        when(openingMapper.countLedgerBeforeForUpdate("AP", 10L, BIZ_DATE)).thenReturn(1L);
        when(openingMapper.countPaymentsBeforeForUpdate(10L, BIZ_DATE)).thenReturn(2L);
        assertEquals(3L, service.countBusinessBefore("AP", 10L, BIZ_DATE));
        verify(openingMapper).countPaymentsBeforeForUpdate(10L, BIZ_DATE);
        verify(openingMapper, never()).countReceiptsBeforeForUpdate(any(), any());
    }

    @Test
    void listSourceInfo_mapsBusinessNo_andOpeningHasNoNo() {
        when(arApMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            arRow(1L, "AR", 9L, "SALE", 500L, "100.00", "0.00", "OPEN"),
            arRow(2L, "AP", 8L, "PURCHASE", 600L, "200.00", "0.00", "OPEN"),
            arRow(3L, "AR", 9L, "OPENING", 700L, "300.00", "0.00", "OPEN")));
        when(sourceNoMapper.selectSaleNos(any())).thenReturn(List.of(sourceNo(500L, "XS202611010001")));
        when(sourceNoMapper.selectPurchaseNos(any())).thenReturn(List.of(sourceNo(600L, "CG202611010001")));
        var ar = service.listSourceInfo(List.of(1L, 2L, 3L), "AR", 9L);
        var ap = service.listSourceInfo(List.of(1L, 2L, 3L), "AP", 8L);
        assertEquals(3, ar.size() + ap.size());
        assertEquals("SALE", ar.get(0).getSourceType());
        assertEquals("XS202611010001", ar.get(0).getSourceNo());
        assertEquals("CG202611010001", ap.get(0).getSourceNo());
        assertNull(ar.get(1).getSourceNo());
    }

    @Test
    void listSourceInfo_emptyIds_returnsEmpty() {
        assertTrue(service.listSourceInfo(List.of()).isEmpty());
        assertTrue(service.listSourceInfo(List.of(), "AR", 9L).isEmpty());
        assertEquals(400, assertThrows(ServiceException.class, () -> service.listSourceInfo(List.of(1L))).getCode());
    }

    // ---------- 辅助 ----------

    private FinArApSummaryVo totals(String amount, String settled, String unsettled, String opening) {
        FinArApSummaryVo vo = new FinArApSummaryVo();
        vo.setTotalAmount(new BigDecimal(amount));
        vo.setSettledAmount(new BigDecimal(settled));
        vo.setUnsettledAmount(new BigDecimal(unsettled));
        vo.setOpeningAmount(new BigDecimal(opening));
        return vo;
    }

    private FinArApSummaryVo.PartySummary partySummary(long id, String amount, String settled, String unsettled, String opening) {
        FinArApSummaryVo.PartySummary vo = new FinArApSummaryVo.PartySummary();
        vo.setPartyId(id);
        vo.setAmount(new BigDecimal(amount));
        vo.setSettled(new BigDecimal(settled));
        vo.setUnsettled(new BigDecimal(unsettled));
        vo.setOpening(new BigDecimal(opening));
        return vo;
    }

    private FinSourceNo sourceNo(long sourceId, String no) {
        FinSourceNo row = new FinSourceNo();
        row.setSourceId(sourceId);
        row.setPartyId(no.startsWith("CG") ? 8L : 9L);
        row.setNo(no);
        return row;
    }

    private FinArAp arRow(long id, String partyType, long partyId, String sourceType, long sourceId,
                          String amount, String settled, String status) {
        FinArAp row = new FinArAp();
        row.setId(id);
        row.setPartyType(partyType);
        row.setPartyId(partyId);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setBizDate(BIZ_DATE);
        row.setAmount(new BigDecimal(amount));
        row.setSettledAmount(new BigDecimal(settled));
        row.setStatus(status);
        return row;
    }

    private ErpCustomer customer(long id, String name) {
        ErpCustomer c = new ErpCustomer();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private ErpSupplier supplier(long id, String name) {
        ErpSupplier s = new ErpSupplier();
        s.setId(id);
        s.setName(name);
        return s;
    }
}
