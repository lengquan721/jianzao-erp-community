package org.erp.fin.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.erp.fin.domain.FinArAp;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinSourceNoMapper;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Ownership and balance guards for refund adjustment of the locked original SALE/AR row. */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinArApSaleReturnRefundTest {

    private static final Long AR_ID = 3001L;
    private static final Long CUSTOMER_ID = 203L;
    private static final Long SALE_ID = 901L;

    @Mock private FinArApMapper arApMapper;
    @Mock private FinSourceNoMapper sourceNoMapper;
    @Mock private ErpCustomerMapper customerMapper;
    @Mock private ErpSupplierMapper supplierMapper;

    @Mock private org.erp.fin.mapper.FinOpeningMapper openingMapper;
    @Mock private org.erp.fin.service.IFinOpeningGuardService openingGuard;
    private FinArApServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinArApServiceImpl(arApMapper, sourceNoMapper, customerMapper, supplierMapper, openingMapper, openingGuard);
    }

    @ParameterizedTest(name = "ordinary unsettle rejects {0}")
    @MethodSource("ordinaryPartyMismatches")
    void ordinaryUnsettleChecksPartyBeforeChangingBalance(OwnershipCase mismatch) {
        FinArAp row = originalLedger();
        mismatch.mutate().accept(row);
        when(arApMapper.selectByIdForUpdate(AR_ID)).thenReturn(row);

        ServiceException denial = assertThrows(ServiceException.class,
            () -> service.unsettle(AR_ID, new BigDecimal("20.00"), "AR", CUSTOMER_ID));

        assertEquals(40902, denial.getCode());
        assertTrue(denial.getMessage().contains("不属于该往来单位"));
        assertUnchangedAndNotWritten(row);
    }

    @ParameterizedTest(name = "ordinary unsettle {0} -> {1}, {2}")
    @CsvSource({"20.00,40.00,PARTIAL", "60.00,0.00,OPEN"})
    void ordinaryUnsettleForMatchingPartyRemainsSupported(String amount, String settled, String status) {
        FinArAp row = originalLedger();
        when(arApMapper.selectByIdForUpdate(AR_ID)).thenReturn(row);
        when(arApMapper.updateById(row)).thenReturn(1);

        service.unsettle(AR_ID, new BigDecimal(amount), "AR", CUSTOMER_ID);

        assertWrittenAfterLock(row, settled, status);
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("refundOwnershipMismatches")
    void controlledRefundChecksBothPartyAndOriginalSale(RefundAction action, OwnershipCase mismatch) {
        FinArAp row = originalLedger();
        mismatch.mutate().accept(row);
        when(arApMapper.selectByIdForUpdate(AR_ID)).thenReturn(row);

        ServiceException denial = assertThrows(ServiceException.class,
            () -> action.call(service, new BigDecimal("20.00"), CUSTOMER_ID, SALE_ID));

        assertTrue(denial.getMessage().contains(mismatch.message()));
        assertUnchangedAndNotWritten(row);
    }

    @ParameterizedTest(name = "{0} rejects missing expected {1}")
    @MethodSource("missingExpectedOwners")
    void controlledRefundDoesNotAllowNullExpectedOwner(RefundAction action, String missing) {
        FinArAp row = originalLedger();
        when(arApMapper.selectByIdForUpdate(AR_ID)).thenReturn(row);

        assertThrows(ServiceException.class, () -> action.call(service, new BigDecimal("20.00"),
            "customer".equals(missing) ? null : CUSTOMER_ID, "sale".equals(missing) ? null : SALE_ID));

        assertUnchangedAndNotWritten(row);
    }

    @ParameterizedTest(name = "{0} rejects amount {1} before locking a ledger")
    @MethodSource("invalidAmounts")
    void controlledRefundRejectsNonPositiveAmountBeforeSql(RefundAction action, String amount) {
        BigDecimal invalid = amount == null ? null : new BigDecimal(amount);

        ServiceException denial = assertThrows(ServiceException.class,
            () -> action.call(service, invalid, CUSTOMER_ID, SALE_ID));

        assertTrue(denial.getMessage().contains("金额必须大于0"));
        verifyNoInteractions(arApMapper, sourceNoMapper, customerMapper, supplierMapper);
    }

    @ParameterizedTest(name = "{0} rejects ledger {1}")
    @MethodSource("unavailableLedgers")
    void controlledRefundRejectsMissingOrVoidLedger(RefundAction action, String unavailable) {
        FinArAp row = "missing".equals(unavailable) ? null : originalLedger();
        if (row != null) {
            row.setStatus("VOID");
        }
        when(arApMapper.selectByIdForUpdate(AR_ID)).thenReturn(row);

        ServiceException denial = assertThrows(ServiceException.class,
            () -> action.call(service, new BigDecimal("20.00"), CUSTOMER_ID, SALE_ID));

        assertTrue(denial.getMessage().contains("不存在或已作废"));
        verify(arApMapper).selectByIdForUpdate(AR_ID);
        verifyNoMoreInteractions(arApMapper);
        verifyNoInteractions(sourceNoMapper, customerMapper, supplierMapper);
    }

    @ParameterizedTest(name = "{0} cannot cross the settled/unsettled balance")
    @EnumSource(RefundAction.class)
    void controlledRefundRejectsOverAdjustment(RefundAction action) {
        FinArAp row = originalLedger();
        when(arApMapper.selectByIdForUpdate(AR_ID)).thenReturn(row);
        BigDecimal excessive = new BigDecimal(action == RefundAction.ISSUE ? "60.01" : "40.01");

        ServiceException denial = assertThrows(ServiceException.class,
            () -> action.call(service, excessive, CUSTOMER_ID, SALE_ID));

        assertTrue(denial.getMessage().contains(action == RefundAction.ISSUE ? "超过已核销金额" : "超过未核销余额"));
        assertUnchangedAndNotWritten(row);
    }

    @ParameterizedTest(name = "{0}: adjust {1} -> settled={2}, status={3}")
    @CsvSource({
        "ISSUE,20.00,40.00,PARTIAL",
        "ISSUE,60.00,0.00,OPEN",
        "CANCEL,20.00,80.00,PARTIAL",
        "CANCEL,40.00,100.00,SETTLED"
    })
    void controlledRefundAdjustsOnlyMatchingOriginalLedger(RefundAction action, String amount, String settled, String status) {
        FinArAp row = originalLedger();
        when(arApMapper.selectByIdForUpdate(AR_ID)).thenReturn(row);
        when(arApMapper.updateById(row)).thenReturn(1);

        action.call(service, new BigDecimal(amount), CUSTOMER_ID, SALE_ID);

        assertWrittenAfterLock(row, settled, status);
        assertEquals("AR", row.getPartyType());
        assertEquals(CUSTOMER_ID, row.getPartyId());
        assertEquals("SALE", row.getSourceType());
        assertEquals(SALE_ID, row.getSourceId());
        assertEquals(new BigDecimal("100.00"), row.getAmount());
    }

    private void assertUnchangedAndNotWritten(FinArAp row) {
        assertEquals(new BigDecimal("60.00"), row.getSettledAmount());
        assertEquals("PARTIAL", row.getStatus());
        verify(arApMapper).selectByIdForUpdate(AR_ID);
        verifyNoMoreInteractions(arApMapper);
        verifyNoInteractions(sourceNoMapper, customerMapper, supplierMapper);
    }

    private void assertWrittenAfterLock(FinArAp row, String settled, String status) {
        assertEquals(new BigDecimal(settled), row.getSettledAmount());
        assertEquals(status, row.getStatus());
        InOrder order = inOrder(arApMapper);
        order.verify(arApMapper).selectByIdForUpdate(AR_ID);
        order.verify(arApMapper).updateById(row);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(sourceNoMapper, customerMapper, supplierMapper);
    }

    private static Stream<OwnershipCase> ordinaryPartyMismatches() {
        return Stream.of(
            new OwnershipCase("AP instead of AR", row -> row.setPartyType("AP"), "往来单位"),
            new OwnershipCase("null party type", row -> row.setPartyType(null), "往来单位"),
            new OwnershipCase("different customer", row -> row.setPartyId(999L), "往来单位"),
            new OwnershipCase("null customer", row -> row.setPartyId(null), "往来单位")
        );
    }

    private static Stream<Arguments> refundOwnershipMismatches() {
        return Arrays.stream(RefundAction.values()).flatMap(action -> Stream.of(
            new OwnershipCase("AP instead of AR", row -> row.setPartyType("AP"), "往来单位"),
            new OwnershipCase("different customer", row -> row.setPartyId(999L), "往来单位"),
            new OwnershipCase("return instead of original SALE", row -> row.setSourceType("SALE_RETURN"), "原销售单"),
            new OwnershipCase("opening instead of SALE", row -> row.setSourceType("OPENING"), "原销售单"),
            new OwnershipCase("null source type", row -> row.setSourceType(null), "原销售单"),
            new OwnershipCase("different sale", row -> row.setSourceId(999L), "原销售单"),
            new OwnershipCase("null source ID", row -> row.setSourceId(null), "原销售单")
        ).map(mismatch -> Arguments.of(action, mismatch)));
    }

    private static Stream<Arguments> missingExpectedOwners() {
        return Arrays.stream(RefundAction.values()).flatMap(action -> Stream.of("customer", "sale")
            .map(missing -> Arguments.of(action, missing)));
    }

    private static Stream<Arguments> invalidAmounts() {
        return Arrays.stream(RefundAction.values()).flatMap(action -> Stream.of(null, "0.00", "-0.01")
            .map(amount -> Arguments.of(action, amount)));
    }

    private static Stream<Arguments> unavailableLedgers() {
        return Arrays.stream(RefundAction.values()).flatMap(action -> Stream.of("missing", "VOID")
            .map(unavailable -> Arguments.of(action, unavailable)));
    }

    private static FinArAp originalLedger() {
        FinArAp row = new FinArAp();
        row.setId(AR_ID);
        row.setPartyType("AR");
        row.setPartyId(CUSTOMER_ID);
        row.setSourceType("SALE");
        row.setSourceId(SALE_ID);
        row.setAmount(new BigDecimal("100.00"));
        row.setSettledAmount(new BigDecimal("60.00"));
        row.setStatus("PARTIAL");
        return row;
    }

    private record OwnershipCase(String name, Consumer<FinArAp> mutate, String message) {
        @Override
        public String toString() {
            return name;
        }
    }

    private enum RefundAction {
        ISSUE, CANCEL;

        void call(FinArApServiceImpl service, BigDecimal amount, Long customerId, Long saleId) {
            if (this == ISSUE) {
                service.unsettleSaleReturnRefund(AR_ID, amount, customerId, saleId);
            } else {
                service.settleSaleReturnRefund(AR_ID, amount, customerId, saleId);
            }
        }
    }
}
