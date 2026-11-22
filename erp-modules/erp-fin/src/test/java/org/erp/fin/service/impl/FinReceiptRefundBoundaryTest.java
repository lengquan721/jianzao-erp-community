package org.erp.fin.service.impl;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.domain.bo.FinReceiptBo;
import org.erp.fin.mapper.FinAccountMapper;
import org.erp.fin.mapper.FinReceiptAllocMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.master.mapper.ErpCustomerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Refund entry contracts; no shared stubs and Mockito's default STRICT_STUBS. */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinReceiptRefundBoundaryTest {

    private static final Long RETURN_ID = 501L;
    private static final Long RECEIPT_ID = 7007L;

    @Mock private FinReceiptMapper receiptMapper;
    @Mock private FinAccountMapper accountMapper;
    @Mock private FinReceiptAllocMapper allocMapper;
    @Mock private ErpCustomerMapper customerMapper;
    @Mock private IFinArApService arApService;
    @Mock private SequenceService sequenceService;

    @Mock private org.erp.fin.service.IFinOpeningGuardService openingGuard;
    private FinReceiptServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinReceiptServiceImpl(receiptMapper, accountMapper, allocMapper,
            customerMapper, arApService, sequenceService, openingGuard);
    }

    @ParameterizedTest(name = "{0} requires an existing caller transaction")
    @EnumSource(InternalEntry.class)
    void internalRefundEntryDeclaresMandatoryTransaction(InternalEntry entry) throws NoSuchMethodException {
        var method = FinReceiptServiceImpl.class.getMethod(entry.method, Long.class);
        Transactional transaction = method.getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertEquals(Propagation.MANDATORY, transaction.propagation());
        assertTrue(Arrays.asList(transaction.rollbackFor()).contains(Exception.class));
        // The only argument is the return ID, never client-supplied amount/account/customer.
        assertEquals(1, method.getParameterCount());
    }

    @ParameterizedTest(name = "{0} denies permission before SQL or number allocation")
    @EnumSource(InternalEntry.class)
    void permissionDenialPrecedesEveryDependency(InternalEntry entry) {
        NotPermissionException denial = new NotPermissionException(entry.permission);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkPermission(entry.permission)).thenThrow(denial);

            assertSame(denial, assertThrows(NotPermissionException.class, () -> entry.call(service)));

            stp.verify(() -> StpUtil.checkPermission(entry.permission));
            verifyNoInteractions(receiptMapper, accountMapper, allocMapper,
                customerMapper, arApService, sequenceService);
        }
    }

    @ParameterizedTest(name = "edit={0}, confirm={1}: reject client negative before doing work")
    @MethodSource("externalWrites")
    void externalNegativeCannotReachOrdinaryWrite(boolean edit, boolean confirm) {
        FinReceiptBo request = new FinReceiptBo();
        request.setNegative(true);
        request.setConfirm(confirm);
        // Deliberately leave all other fields unset: the negative guard must execute first.
        ServiceException denial = assertThrows(ServiceException.class, () -> {
            if (edit) {
                service.update(RECEIPT_ID, request);
            } else {
                service.insert(request);
            }
        });

        assertEquals(40902, denial.getCode());
        assertTrue(denial.getMessage().contains("普通收款接口"));
        verifyNoInteractions(receiptMapper, accountMapper, allocMapper,
            customerMapper, arApService, sequenceService);
    }

    @ParameterizedTest(name = "{0} cannot touch a {1} internal refund")
    @MethodSource("ordinaryRefundMutations")
    void ordinaryMutationCannotTouchAnyRefundState(OrdinaryMutation mutation, String status) {
        FinReceipt refund = new FinReceipt();
        refund.setId(RECEIPT_ID);
        refund.setNegative(1);
        refund.setStatus(status);
        when(receiptMapper.selectById(RECEIPT_ID)).thenReturn(refund);
        when(receiptMapper.lockById(RECEIPT_ID)).thenReturn(refund);

        ServiceException denial = assertThrows(ServiceException.class, () -> mutation.call(service));

        assertEquals(40902, denial.getCode());
        assertTrue(denial.getMessage().contains("请通过关联销售退货处理"));
        assertEquals(status, refund.getStatus());
        verify(receiptMapper).selectById(RECEIPT_ID);
        verify(receiptMapper).lockById(RECEIPT_ID);
        verifyNoMoreInteractions(receiptMapper);
        verifyNoInteractions(accountMapper, allocMapper, customerMapper, arApService, sequenceService);
    }

    private static Stream<Arguments> externalWrites() {
        return Stream.of(false, true).flatMap(edit -> Stream.of(false, true)
            .map(confirm -> Arguments.of(edit, confirm)));
    }

    private static Stream<Arguments> ordinaryRefundMutations() {
        return Arrays.stream(OrdinaryMutation.values()).flatMap(mutation ->
            Stream.of("DRAFT", "CONFIRMED", "VOID").map(status -> Arguments.of(mutation, status)));
    }

    private enum InternalEntry {
        INSERT("insertSaleReturnRefund", "erp:finance:receipt:confirm"),
        WITHDRAW("withdrawSaleReturnRefund", "erp:finance:receipt:withdraw");

        private final String method;
        private final String permission;

        InternalEntry(String method, String permission) {
            this.method = method;
            this.permission = permission;
        }

        void call(FinReceiptServiceImpl service) {
            if (this == INSERT) {
                service.insertSaleReturnRefund(RETURN_ID);
            } else {
                service.withdrawSaleReturnRefund(RETURN_ID);
            }
        }
    }

    private enum OrdinaryMutation {
        EDIT, CONFIRM, WITHDRAW, VOID, DELETE;

        void call(FinReceiptServiceImpl service) {
            switch (this) {
                case EDIT -> {
                    FinReceiptBo request = new FinReceiptBo();
                    request.setNegative(false);
                    service.update(RECEIPT_ID, request);
                }
                case CONFIRM -> service.confirm(RECEIPT_ID);
                case WITHDRAW -> service.withdraw(RECEIPT_ID);
                case VOID -> service.voidBill(RECEIPT_ID);
                case DELETE -> service.deleteDraft(RECEIPT_ID);
            }
        }
    }
}
