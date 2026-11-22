package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.domain.*;
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.domain.bo.FinPaymentBo;
import org.erp.fin.domain.bo.FinReceiptBo;
import org.erp.fin.mapper.*;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 服务层锁序及旧快照反例；真实两连接 MySQL 并发另验。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinOpeningConcurrencyOrderTest {
    @Mock private FinOpeningMapper openings;
    @Mock private IFinOpeningGuardService guard;
    @Mock private IFinArApService ledger;
    @Mock private ErpCustomerMapper customers;
    @Mock private ErpSupplierMapper suppliers;
    @Mock private FinReceiptMapper receipts;
    @Mock private FinReceiptAllocMapper receiptAllocs;
    @Mock private FinPaymentMapper payments;
    @Mock private FinPaymentAllocMapper paymentAllocs;
    @Mock private FinAccountMapper accounts;
    @Mock private SequenceService sequence;
    private FinOpeningServiceImpl service;

    @org.junit.jupiter.api.BeforeAll
    static void initializeLambdaMetadata() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (Class<?> entity : List.of(FinReceiptAlloc.class, FinPaymentAlloc.class, FinArAp.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "finance-current-rows"), entity);
        }
    }

    @BeforeEach void setup() {
        service = new FinOpeningServiceImpl(openings, customers, suppliers, ledger, guard);
    }

    @Test void saveLocksMasterEvenWithoutAnyOpeningThenReadsCurrentValue() {
        FinOpeningBo bo = new FinOpeningBo();
        bo.setPartyType("AR"); bo.setPartyId(9L); bo.setAmount(BigDecimal.TEN);
        bo.setOpeningDate(LocalDate.of(2020, 9, 30));
        when(openings.insert(any(FinOpening.class))).thenReturn(1);
        var result = service.save(bo);
        assertEquals(bo.getOpeningDate(), result.getOpeningDate());
        assertEquals(Boolean.FALSE, result.getOverwritten());
        var order = inOrder(guard, openings);
        order.verify(guard).lockParty("AR", 9L);
        order.verify(openings).selectByPartyForUpdate("AR", 9L);
        order.verify(openings).insert(any(FinOpening.class));
    }

    @Test void confirmUsesLockedCurrentDateNotIdentitySnapshotAndNeverUsesNow() {
        FinOpening identity = opening("DRAFT", LocalDate.of(1999, 1, 1));
        FinOpening current = opening("DRAFT", LocalDate.of(2020, 9, 30));
        when(openings.selectById(1L)).thenReturn(identity);
        when(openings.selectByIdForUpdate(1L)).thenReturn(current);
        when(openings.update(isNull(), any(Wrapper.class))).thenReturn(1);
        service.confirm(1L);
        var order = inOrder(openings, guard, ledger);
        order.verify(openings).selectById(1L);
        order.verify(guard).lockParty("AR", 9L);
        order.verify(openings).selectByIdForUpdate(1L);
        order.verify(ledger).countBusinessBefore(eq("AR"), eq(9L), argThat(value ->
            new Date(value.getTime()).toLocalDate().equals(LocalDate.of(2020, 10, 1))));
        order.verify(ledger).createOpening(eq("AR"), eq(9L), eq(BigDecimal.TEN), eq(1L), argThat(value ->
            new Date(value.getTime()).toLocalDate().equals(LocalDate.of(2020, 9, 30))));
        order.verify(openings).update(isNull(), any(Wrapper.class));
        assertEquals("CONFIRMED", current.getStatus());
        assertEquals(LocalDate.of(1999, 1, 1), identity.getOpeningDate());
    }

    @Test void staleDraftSnapshotCannotOverwriteAConfirmedCurrentRow() {
        FinOpeningBo bo = new FinOpeningBo();
        bo.setPartyType("AR"); bo.setPartyId(9L); bo.setAmount(BigDecimal.TEN);
        bo.setOpeningDate(LocalDate.of(2020, 9, 30));
        when(openings.selectByPartyForUpdate("AR", 9L)).thenReturn(opening("CONFIRMED", bo.getOpeningDate()));
        assertEquals(40902, assertThrows(ServiceException.class, () -> service.save(bo)).getCode());
        verify(guard).lockParty("AR", 9L);
        verify(openings).selectByPartyForUpdate("AR", 9L);
        verifyNoMoreInteractions(openings);
    }

    @Test void historicalNullCanBeReadButCannotReconfirmAfterControlledWithdrawal() {
        FinOpening historical = opening("CONFIRMED", null);
        when(openings.selectById(1L)).thenReturn(historical);
        when(openings.selectByIdForUpdate(1L)).thenReturn(historical);
        when(openings.update(isNull(), any(Wrapper.class))).thenReturn(1);
        service.unconfirm(1L);
        assertEquals("DRAFT", historical.getStatus());
        assertNull(historical.getOpeningDate());
        assertThrows(ServiceException.class, () -> service.confirm(1L));
        verify(ledger).voidBySource("OPENING", 1L);
        verify(ledger, never()).createOpening(any(), any(), any(), any(), any());
    }

    @Test void settledOpeningCannotWithdrawOrResetItsHeader() {
        FinOpening current = opening("CONFIRMED", null);
        when(openings.selectById(1L)).thenReturn(current);
        when(openings.selectByIdForUpdate(1L)).thenReturn(current);
        doThrow(new ServiceException("先反核销", 40904)).when(ledger).voidBySource("OPENING", 1L);
        assertEquals(40904, assertThrows(ServiceException.class, () -> service.unconfirm(1L)).getCode());
        assertEquals("CONFIRMED", current.getStatus());
        verify(openings, never()).update(any(), any(Wrapper.class));
    }

    @Test void receiptLocksStableCustomerBeforeHeaderThenChecksCurrentBusinessDateBeforeAllocations() {
        FinReceipt identity = receipt();
        FinReceipt current = receipt(); current.setBizDate(Date.valueOf("2020-09-30"));
        when(receipts.selectById(7L)).thenReturn(identity);
        when(receipts.lockById(7L)).thenReturn(current);
        var denial = new ServiceException("截止日拦截", 40902);
        doThrow(denial).when(guard).lockAndCheckBusinessDate("AR", 9L, current.getBizDate());
        var finance = new FinReceiptServiceImpl(receipts, accounts, receiptAllocs, customers, ledger, sequence, guard);
        assertSame(denial, assertThrows(ServiceException.class, () -> finance.confirm(7L)));
        var order = inOrder(receipts, guard);
        order.verify(receipts).selectById(7L);
        order.verify(guard).lockParty("AR", 9L);
        order.verify(receipts).lockById(7L);
        order.verify(guard).lockAndCheckBusinessDate("AR", 9L, current.getBizDate());
        verifyNoInteractions(receiptAllocs, ledger, sequence, accounts);
        verify(receipts, never()).update(any(), any(Wrapper.class));
    }

    @Test void paymentUsesTheSameBoundaryAndDoesNotSettleWhenCutoffDenied() {
        FinPayment head = new FinPayment(); head.setId(8L); head.setSupplierId(9L);
        head.setStatus("DRAFT"); head.setBizDate(Date.valueOf("2020-09-30"));
        when(payments.selectById(8L)).thenReturn(head);
        when(payments.lockById(8L)).thenReturn(head);
        doThrow(new ServiceException("截止日拦截", 40902)).when(guard)
            .lockAndCheckBusinessDate("AP", 9L, head.getBizDate());
        var finance = new FinPaymentServiceImpl(payments, accounts, paymentAllocs, suppliers, ledger, sequence, guard);
        assertThrows(ServiceException.class, () -> finance.confirm(8L));
        var order = inOrder(payments, guard);
        order.verify(payments).selectById(8L);
        order.verify(guard).lockParty("AP", 9L);
        order.verify(payments).lockById(8L);
        order.verify(guard).lockAndCheckBusinessDate("AP", 9L, head.getBizDate());
        verifyNoInteractions(paymentAllocs, ledger, sequence, accounts);
    }

    @Test void saveAndConfirmReceiptChecksBeforeNewHeaderInsertButDraftDoesNotCheckCutoff() {
        var finance = new FinReceiptServiceImpl(receipts, accounts, receiptAllocs, customers, ledger, sequence, guard);
        FinReceiptBo bo = new FinReceiptBo(); bo.setCustomerId(9L); bo.setConfirm(true);
        bo.setBizDate(Date.valueOf("2020-09-30"));
        doThrow(new ServiceException("截止日拦截", 40902)).when(guard)
            .lockAndCheckBusinessDate("AR", 9L, bo.getBizDate());
        assertThrows(ServiceException.class, () -> finance.insert(bo));
        verifyNoInteractions(receipts, receiptAllocs, ledger, sequence, accounts);
    }

    @Test void receiptAllocationReadCannotReuseTheIdentityLookupOldSnapshot() {
        FinReceipt head = receipt(); head.setAmount(BigDecimal.TEN);
        when(receipts.selectById(7L)).thenReturn(head);
        when(receipts.lockById(7L)).thenReturn(head);
        when(receipts.update(isNull(), any(Wrapper.class))).thenReturn(1);
        FinReceiptAlloc old = new FinReceiptAlloc(); old.setArApId(111L); old.setAmount(BigDecimal.TEN);
        FinReceiptAlloc current = new FinReceiptAlloc(); current.setArApId(222L); current.setAmount(BigDecimal.TEN);
        when(receiptAllocs.selectList(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<?> query = call.getArgument(0);
            String sql = query.getSqlSegment().toLowerCase(java.util.Locale.ROOT);
            assertTrue(sql.contains("order by ar_ap_id asc,id asc"), sql);
            return List.of(sql.endsWith("for update") ? current : old);
        });
        var finance = new FinReceiptServiceImpl(receipts, accounts, receiptAllocs, customers, ledger, sequence, guard);
        finance.confirm(7L);
        verify(ledger).settle(222L, BigDecimal.TEN, "AR", 9L);
        verify(ledger, never()).settle(eq(111L), any(), any(), any());
    }

    @Test void paymentAllocationReadMustAlsoBeCurrentWithoutChangingItsOrderOrAmount() {
        FinPayment head = new FinPayment(); head.setId(8L); head.setSupplierId(9L);
        head.setStatus("DRAFT"); head.setBizDate(Date.valueOf("2020-10-01")); head.setAmount(BigDecimal.TEN);
        when(payments.selectById(8L)).thenReturn(head);
        when(payments.lockById(8L)).thenReturn(head);
        when(payments.update(isNull(), any(Wrapper.class))).thenReturn(1);
        FinPaymentAlloc current = new FinPaymentAlloc(); current.setArApId(222L); current.setAmount(BigDecimal.TEN);
        when(paymentAllocs.selectList(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<?> query = call.getArgument(0);
            String sql = query.getSqlSegment().toLowerCase(java.util.Locale.ROOT);
            assertTrue(sql.endsWith("for update"), sql);
            assertTrue(sql.contains("order by ar_ap_id asc,id asc"), sql);
            return List.of(current);
        });
        var finance = new FinPaymentServiceImpl(payments, accounts, paymentAllocs, suppliers, ledger, sequence, guard);
        finance.confirm(8L);
        verify(ledger).settle(222L, BigDecimal.TEN, "AP", 9L);
    }

    @Test void voidSourceEnumerationMustSeeNewlyCommittedRowsAfterAnOldIdentitySnapshot() {
        FinArApMapper mapper = mock(FinArApMapper.class);
        FinArAp current = new FinArAp(); current.setId(7L); current.setStatus("OPEN");
        current.setSettledAmount(BigDecimal.ZERO);
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<?> query = call.getArgument(0);
            return query.getSqlSegment().endsWith("for update") ? List.of(current) : List.of();
        });
        when(mapper.selectByIdForUpdate(7L)).thenReturn(current);
        var bottom = new FinArApServiceImpl(mapper, mock(FinSourceNoMapper.class), customers, suppliers, openings, guard);
        bottom.voidBySource("OPENING", 1L);
        assertEquals("VOID", current.getStatus());
        verify(mapper).updateById(current);
    }

    @Test void nonOpeningBottomLevelCreationCannotBypassGuardIncludingNegativeReturns() {
        FinArApMapper mapper = mock(FinArApMapper.class);
        var bottom = new FinArApServiceImpl(mapper, mock(FinSourceNoMapper.class), customers, suppliers, openings, guard);
        var date = Date.valueOf("2020-09-30");
        doThrow(new ServiceException("截止日拦截", 40902)).when(guard).lockAndCheckBusinessDate("AR", 9L, date);
        assertThrows(ServiceException.class, () -> bottom.createBySale(1L, "S", 9L, BigDecimal.TEN, date));
        assertThrows(ServiceException.class, () -> bottom.createBySaleReturn(2L, "R", 9L, BigDecimal.TEN.negate(), date));
        verifyNoInteractions(mapper);
    }

    private FinOpening opening(String status, LocalDate cutoff) {
        FinOpening row = new FinOpening(); row.setId(1L); row.setPartyType("AR"); row.setPartyId(9L);
        row.setAmount(BigDecimal.TEN); row.setStatus(status); row.setOpeningDate(cutoff); return row;
    }
    private FinReceipt receipt() {
        FinReceipt row = new FinReceipt(); row.setId(7L); row.setCustomerId(9L); row.setStatus("DRAFT");
        row.setNegative(0); row.setBizDate(Date.valueOf("2020-10-01")); return row;
    }
}
