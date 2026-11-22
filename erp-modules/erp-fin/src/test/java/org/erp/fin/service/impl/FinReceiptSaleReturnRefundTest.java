package org.erp.fin.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.domain.FinAccount;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.domain.FinReceiptAlloc;
import org.erp.fin.domain.FinSaleReturnRefundSource;
import org.erp.fin.domain.vo.FinArApVo;
import org.erp.fin.mapper.FinAccountMapper;
import org.erp.fin.mapper.FinReceiptAllocMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.master.mapper.ErpCustomerMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Persisted-source refund issuance/withdrawal; deliberately no lenient or global stubs. */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinReceiptSaleReturnRefundTest {

    private static final Long RETURN_ID = 501L;
    private static final Long SALE_ID = 901L;
    private static final Long CUSTOMER_ID = 203L;
    private static final Long ACCOUNT_ID = 17L;
    private static final Long RECEIPT_ID = 7007L;
    private static final Long AR_ID = 3001L;
    private static final Date BIZ_DATE = new Date(123456789L);
    private static final String CONFIRM_PERMISSION = "erp:finance:receipt:confirm";
    private static final String WITHDRAW_PERMISSION = "erp:finance:receipt:withdraw";

    @Mock private FinReceiptMapper receiptMapper;
    @Mock private FinAccountMapper accountMapper;
    @Mock private FinReceiptAllocMapper allocMapper;
    @Mock private ErpCustomerMapper customerMapper;
    @Mock private IFinArApService arApService;
    @Mock private SequenceService sequenceService;

    @Mock private org.erp.fin.service.IFinOpeningGuardService openingGuard;
    private FinReceiptServiceImpl service;

    @BeforeAll
    static void initializeLambdaMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "controlled-refund-test");
        TableInfoHelper.initTableInfo(assistant, FinReceiptAlloc.class);
    }

    @BeforeEach
    void setUp() {
        service = new FinReceiptServiceImpl(receiptMapper, accountMapper, allocMapper,
            customerMapper, arApService, sequenceService, openingGuard);
    }

    @ParameterizedTest(name = "issue rejects persisted source: {0}")
    @MethodSource("invalidPendingSources")
    void rejectsInvalidPersistedPendingSource(String reason, FinSaleReturnRefundSource source) {
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(source);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ServiceException denial = assertThrows(ServiceException.class,
                () -> service.insertSaleReturnRefund(RETURN_ID));

            assertEquals(40902, denial.getCode(), reason);
            stp.verify(() -> StpUtil.checkPermission(CONFIRM_PERMISSION));
            verify(receiptMapper).lockRefundSource(RETURN_ID);
            verifyNoMoreInteractions(receiptMapper);
            verifyNoInteractions(accountMapper, allocMapper, customerMapper, arApService, sequenceService);
        }
    }

    @ParameterizedTest(name = "issue rejects original receivable: {0}")
    @MethodSource("invalidOriginalReceivables")
    void rejectsWrongOriginalReceivableBeforeAllocatingNumber(String reason, FinArApVo original) {
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(pendingSource());
        when(arApService.getBySource("SALE", SALE_ID)).thenReturn(original);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ServiceException denial = assertThrows(ServiceException.class,
                () -> service.insertSaleReturnRefund(RETURN_ID));

            assertEquals(40902, denial.getCode(), reason);
            stp.verify(() -> StpUtil.checkPermission(CONFIRM_PERMISSION));
            verify(receiptMapper).lockRefundSource(RETURN_ID);
            verify(arApService).getBySource("SALE", SALE_ID);
            verifyNoMoreInteractions(receiptMapper, arApService);
            verifyNoInteractions(accountMapper, allocMapper, customerMapper, sequenceService);
        }
    }

    @Test
    void controlledRefundCutoffDenialPropagatesBeforeAnyRefundHeaderOrAllocationIsWritten() {
        FinSaleReturnRefundSource source = pendingSource();
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(source);
        when(arApService.getBySource("SALE", SALE_ID)).thenReturn(originalReceivable(source));
        ServiceException denial = new ServiceException("业务日期早于或等于期初截止日", 40902);
        doThrow(denial).when(openingGuard).lockAndCheckBusinessDate("AR", CUSTOMER_ID, BIZ_DATE);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertSame(denial, assertThrows(ServiceException.class, () -> service.insertSaleReturnRefund(RETURN_ID)));
            stp.verify(() -> StpUtil.checkPermission(CONFIRM_PERMISSION));
        }
        verify(receiptMapper).lockRefundSource(RETURN_ID);
        verify(arApService).getBySource("SALE", SALE_ID);
        verify(openingGuard).lockAndCheckBusinessDate("AR", CUSTOMER_ID, BIZ_DATE);
        verifyNoMoreInteractions(receiptMapper, arApService, openingGuard);
        verifyNoInteractions(sequenceService, accountMapper, allocMapper, customerMapper);
    }

    @ParameterizedTest(name = "issue rejects settlement account: {0}")
    @ValueSource(strings = {"missing", "disabled", "null-status"})
    void rejectsMissingOrInactivePersistedAccount(String reason) {
        FinSaleReturnRefundSource source = pendingSource();
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(source);
        when(arApService.getBySource("SALE", SALE_ID)).thenReturn(originalReceivable(source));
        when(sequenceService.nextNo("SK")).thenReturn("SK-REFUND-TEST");
        FinAccount account = "missing".equals(reason) ? null : activeAccount(ACCOUNT_ID);
        if (account != null) {
            account.setStatus("disabled".equals(reason) ? 0 : null);
        }
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ServiceException denial = assertThrows(ServiceException.class,
                () -> service.insertSaleReturnRefund(RETURN_ID));

            assertTrue(denial.getMessage().contains("账户"));
            stp.verify(() -> StpUtil.checkPermission(CONFIRM_PERMISSION));
            verify(receiptMapper).lockRefundSource(RETURN_ID);
            verify(accountMapper).selectById(ACCOUNT_ID);
            verify(arApService).getBySource("SALE", SALE_ID);
            verifyNoMoreInteractions(receiptMapper, arApService);
            verifyNoInteractions(allocMapper, customerMapper);
            // Account existence/status validation currently occurs after nextNo, not before it.
            verify(sequenceService).nextNo("SK");
        }
    }

    @ParameterizedTest(name = "persisted customer={0}, account={1}, amount={2}/{3}")
    @CsvSource({"203,17,40.00,100.00", "209,23,100.00,100.00"})
    void createsRefundOnlyFromPersistedSource(long customerId, long accountId, String amount, String total) {
        FinSaleReturnRefundSource source = pendingSource();
        source.setCustomerId(customerId);
        source.setRefundAccountId(accountId);
        source.setRefundAmount(new BigDecimal(amount));
        source.setTotalAmount(new BigDecimal(total));
        List<FinReceiptAlloc> storedAllocations = stubRefundDraftCreation(source);
        when(receiptMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertEquals(RECEIPT_ID, service.insertSaleReturnRefund(RETURN_ID));
            stp.verify(() -> StpUtil.checkPermission(CONFIRM_PERMISSION));
        }

        ArgumentCaptor<FinReceipt> inserted = ArgumentCaptor.forClass(FinReceipt.class);
        verify(receiptMapper).insert(inserted.capture());
        FinReceipt refund = inserted.getValue();
        assertEquals(customerId, refund.getCustomerId());
        assertEquals(accountId, refund.getAccountId());
        assertEquals("persisted-account-" + accountId, refund.getAccount());
        assertEquals(new BigDecimal(amount), refund.getAmount());
        assertEquals(BIZ_DATE, refund.getBizDate());
        assertEquals("销售退货退款 " + source.getNo(), refund.getRemark());
        assertEquals(1, refund.getNegative());
        assertEquals("CONFIRMED", refund.getStatus());
        assertNotNull(refund.getConfirmedAt());
        assertEquals(1, storedAllocations.size());
        FinReceiptAlloc allocation = storedAllocations.get(0);
        assertEquals(RECEIPT_ID, allocation.getReceiptId());
        assertEquals(AR_ID, allocation.getArApId());
        assertEquals(new BigDecimal(amount), allocation.getAmount());
        assertTransition(captureTransition(), "DRAFT", "CONFIRMED");
        assertEquals("DRAFT", source.getStatus());
        assertNull(source.getRefundReceiptId(), "the trade transaction owns linking the receipt");

        InOrder order = inOrder(receiptMapper, arApService, sequenceService, accountMapper, allocMapper);
        order.verify(receiptMapper).lockRefundSource(RETURN_ID);
        order.verify(arApService).getBySource("SALE", SALE_ID);
        order.verify(sequenceService).nextNo("SK");
        order.verify(accountMapper).selectById(accountId);
        order.verify(receiptMapper).insert(any(FinReceipt.class));
        order.verify(allocMapper).delete(any(Wrapper.class));
        order.verify(allocMapper).insert(any(FinReceiptAlloc.class));
        order.verify(allocMapper).selectList(any(Wrapper.class));
        order.verify(arApService).unsettleSaleReturnRefund(AR_ID, new BigDecimal(amount), customerId, SALE_ID);
        order.verify(receiptMapper).update(isNull(), any(Wrapper.class));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(customerMapper);
    }

    @ParameterizedTest(name = "issue rejects conditional update row count {0}")
    @ValueSource(ints = {0, 2})
    void issuanceRequiresExactlyOneDraftToConfirmedTransition(int changedRows) {
        stubRefundDraftCreation(pendingSource());
        when(receiptMapper.update(isNull(), any(Wrapper.class))).thenReturn(changedRows);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ServiceException denial = assertThrows(ServiceException.class,
                () -> service.insertSaleReturnRefund(RETURN_ID));
            assertEquals(40902, denial.getCode());
            stp.verify(() -> StpUtil.checkPermission(CONFIRM_PERMISSION));
        }
        verify(arApService).unsettleSaleReturnRefund(AR_ID, new BigDecimal("40.00"), CUSTOMER_ID, SALE_ID);
        assertTransition(captureTransition(), "DRAFT", "CONFIRMED");
        // This unit test observes refusal only; a real transaction must roll back prior writes.
    }

    @Test
    void originalLedgerRejectionDoesNotConfirmIssuedRefund() {
        stubRefundDraftCreation(pendingSource());
        ServiceException denial = new ServiceException("原单台账归属已变更", 40902);
        doThrow(denial).when(arApService).unsettleSaleReturnRefund(
            AR_ID, new BigDecimal("40.00"), CUSTOMER_ID, SALE_ID);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertSame(denial, assertThrows(ServiceException.class,
                () -> service.insertSaleReturnRefund(RETURN_ID)));
            stp.verify(() -> StpUtil.checkPermission(CONFIRM_PERMISSION));
        }
        verify(receiptMapper, never()).update(isNull(), any(Wrapper.class));
        verify(arApService, never()).settle(any(), any(), any(), any());
        verify(arApService, never()).unsettle(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "withdraw rejects persisted source: {0}")
    @MethodSource("invalidWithdrawalSources")
    void rejectsWithdrawalWithoutValidConfirmedAssociation(String reason, FinSaleReturnRefundSource source) {
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(source);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ServiceException denial = assertThrows(ServiceException.class,
                () -> service.withdrawSaleReturnRefund(RETURN_ID));

            assertEquals(40902, denial.getCode(), reason);
            stp.verify(() -> StpUtil.checkPermission(WITHDRAW_PERMISSION));
            verify(receiptMapper).lockRefundSource(RETURN_ID);
            verifyNoMoreInteractions(receiptMapper);
            verifyNoInteractions(accountMapper, allocMapper, customerMapper, arApService, sequenceService);
        }
    }

    @ParameterizedTest(name = "withdraw rejects associated receipt: {0}")
    @MethodSource("invalidLinkedReceipts")
    void rejectsMismatchedOrNonRefundAssociation(String reason, FinReceipt receipt) {
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(confirmedSource());
        when(receiptMapper.lockById(RECEIPT_ID)).thenReturn(receipt);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ServiceException denial = assertThrows(ServiceException.class,
                () -> service.withdrawSaleReturnRefund(RETURN_ID));

            assertEquals(40902, denial.getCode(), reason);
            stp.verify(() -> StpUtil.checkPermission(WITHDRAW_PERMISSION));
            verify(receiptMapper).lockRefundSource(RETURN_ID);
            verify(receiptMapper).lockById(RECEIPT_ID);
            verifyNoMoreInteractions(receiptMapper);
            verifyNoInteractions(accountMapper, allocMapper, customerMapper, arApService, sequenceService);
        }
    }

    @ParameterizedTest(name = "withdraw rejects allocation shape: {0}")
    @MethodSource("invalidWithdrawalAllocations")
    void withdrawalRequiresFullAmountAndOneOriginalLedger(String reason, List<FinReceiptAlloc> rows, String message) {
        FinReceipt receipt = stubLinkedReceipt();
        when(allocMapper.selectList(any(Wrapper.class))).thenReturn(rows);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ServiceException denial = assertThrows(ServiceException.class,
                () -> service.withdrawSaleReturnRefund(RETURN_ID));

            assertTrue(denial.getMessage().contains(message), reason);
            stp.verify(() -> StpUtil.checkPermission(WITHDRAW_PERMISSION));
        }
        assertEquals("CONFIRMED", receipt.getStatus());
        verifyNoInteractions(arApService, accountMapper, customerMapper, sequenceService);
        verify(receiptMapper, never()).update(isNull(), any(Wrapper.class));
        verify(allocMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void withdrawsOnlyLinkedRefundThenMakesReceiptTerminalVoid() {
        FinReceipt receipt = stubLinkedReceipt();
        when(allocMapper.selectList(any(Wrapper.class))).thenReturn(List.of(allocation(1L, AR_ID, "40.00")));
        when(receiptMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            service.withdrawSaleReturnRefund(RETURN_ID);
            stp.verify(() -> StpUtil.checkPermission(WITHDRAW_PERMISSION));
        }

        InOrder order = inOrder(receiptMapper, allocMapper, arApService);
        order.verify(receiptMapper).lockRefundSource(RETURN_ID);
        order.verify(receiptMapper).lockById(RECEIPT_ID);
        order.verify(allocMapper).selectList(any(Wrapper.class));
        order.verify(arApService).settleSaleReturnRefund(AR_ID, new BigDecimal("40.00"), CUSTOMER_ID, SALE_ID);
        order.verify(receiptMapper).update(isNull(), any(Wrapper.class));
        order.verifyNoMoreInteractions();
        assertEquals("VOID", receipt.getStatus());
        assertNotNull(receipt.getWithdrawnAt());
        UpdateWrapper<FinReceipt> transition = captureTransition();
        assertTransition(transition, "CONFIRMED", "VOID");
        String assignments = transition.getSqlSet();
        assertBound(assignments, transition.getParamNameValuePairs(), "withdrawn_at", receipt.getWithdrawnAt());
        assertBound(assignments, transition.getParamNameValuePairs(), "voided_at", receipt.getWithdrawnAt());
        assertBound(assignments, transition.getParamNameValuePairs(), "voided_by", receipt.getWithdrawnBy());
        verifyNoInteractions(accountMapper, customerMapper, sequenceService);
        // No allocation delete, ordinary settle/unsettle, or reusable DRAFT transition is allowed.
    }

    @Test
    void legacyNullSourceAccountCanOnlyReverseItsPersistedLinkedRefund() {
        // The additive migration intentionally leaves historical source accounts null.
        FinSaleReturnRefundSource source = confirmedSource();
        source.setRefundAccountId(null);
        FinReceipt receipt = linkedReceipt();
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(source);
        when(receiptMapper.lockById(RECEIPT_ID)).thenReturn(receipt);
        when(allocMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(allocation(1L, AR_ID, "40.00")));
        when(receiptMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            service.withdrawSaleReturnRefund(RETURN_ID);
            stp.verify(() -> StpUtil.checkPermission(WITHDRAW_PERMISSION));
        }

        verify(receiptMapper).lockRefundSource(RETURN_ID);
        verify(receiptMapper).lockById(RECEIPT_ID);
        verify(arApService).settleSaleReturnRefund(AR_ID, new BigDecimal("40.00"), CUSTOMER_ID, SALE_ID);
        assertEquals(ACCOUNT_ID, receipt.getAccountId());
        assertEquals("VOID", receipt.getStatus());
        assertTransition(captureTransition(), "CONFIRMED", "VOID");
        verifyNoInteractions(accountMapper, customerMapper, sequenceService);
        verify(allocMapper, never()).delete(any(Wrapper.class));
    }

    @ParameterizedTest(name = "withdraw rejects conditional update row count {0}")
    @ValueSource(ints = {0, 2})
    void withdrawalRequiresExactlyOneConfirmedToVoidTransition(int changedRows) {
        FinReceipt receipt = stubLinkedReceipt();
        when(allocMapper.selectList(any(Wrapper.class))).thenReturn(List.of(allocation(1L, AR_ID, "40.00")));
        when(receiptMapper.update(isNull(), any(Wrapper.class))).thenReturn(changedRows);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ServiceException denial = assertThrows(ServiceException.class,
                () -> service.withdrawSaleReturnRefund(RETURN_ID));
            assertEquals(40902, denial.getCode());
            stp.verify(() -> StpUtil.checkPermission(WITHDRAW_PERMISSION));
        }
        assertEquals("CONFIRMED", receipt.getStatus());
        verify(arApService).settleSaleReturnRefund(AR_ID, new BigDecimal("40.00"), CUSTOMER_ID, SALE_ID);
        assertTransition(captureTransition(), "CONFIRMED", "VOID");
    }

    @Test
    void wrongOriginalLedgerDuringWithdrawalCannotVoidReceipt() {
        FinReceipt receipt = stubLinkedReceipt();
        Long foreignLedgerId = 3999L;
        when(allocMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(allocation(1L, foreignLedgerId, "40.00")));
        ServiceException denial = new ServiceException("退款只能关联原销售单应收台账", 40902);
        doThrow(denial).when(arApService).settleSaleReturnRefund(
            foreignLedgerId, new BigDecimal("40.00"), CUSTOMER_ID, SALE_ID);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertSame(denial, assertThrows(ServiceException.class,
                () -> service.withdrawSaleReturnRefund(RETURN_ID)));
            stp.verify(() -> StpUtil.checkPermission(WITHDRAW_PERMISSION));
        }
        assertEquals("CONFIRMED", receipt.getStatus());
        verify(arApService).settleSaleReturnRefund(foreignLedgerId, new BigDecimal("40.00"), CUSTOMER_ID, SALE_ID);
        verifyNoMoreInteractions(arApService);
        verify(receiptMapper, never()).update(isNull(), any(Wrapper.class));
        verify(allocMapper, never()).delete(any(Wrapper.class));
        verifyNoInteractions(accountMapper, customerMapper, sequenceService);
    }

    /** Only for tests that actually reach creation; reads echo the rows really inserted. */
    private List<FinReceiptAlloc> stubRefundDraftCreation(FinSaleReturnRefundSource source) {
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(source);
        when(arApService.getBySource("SALE", source.getSaleId())).thenReturn(originalReceivable(source));
        when(sequenceService.nextNo("SK")).thenReturn("SK-REFUND-TEST");
        when(accountMapper.selectById(source.getRefundAccountId()))
            .thenReturn(activeAccount(source.getRefundAccountId()));
        when(receiptMapper.insert(any(FinReceipt.class))).thenAnswer(invocation -> {
            FinReceipt receipt = invocation.getArgument(0);
            assertEquals("DRAFT", receipt.getStatus());
            assertEquals(1, receipt.getNegative());
            receipt.setId(RECEIPT_ID);
            return 1;
        });
        List<FinReceiptAlloc> stored = new ArrayList<>();
        when(allocMapper.insert(any(FinReceiptAlloc.class))).thenAnswer(invocation -> {
            stored.add(invocation.getArgument(0));
            return 1;
        });
        when(allocMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            var lambda = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) query;
            assertBound(sql, lambda.getParamNameValuePairs(), "receipt_id", RECEIPT_ID);
            return List.copyOf(stored);
        });
        return stored;
    }

    private FinReceipt stubLinkedReceipt() {
        FinReceipt receipt = linkedReceipt();
        when(receiptMapper.lockRefundSource(RETURN_ID)).thenReturn(confirmedSource());
        when(receiptMapper.lockById(RECEIPT_ID)).thenReturn(receipt);
        return receipt;
    }

    @SuppressWarnings("unchecked")
    private UpdateWrapper<FinReceipt> captureTransition() {
        ArgumentCaptor<UpdateWrapper<FinReceipt>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(receiptMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private static void assertTransition(UpdateWrapper<FinReceipt> transition, String from, String to) {
        String conditions = transition.getSqlSegment();
        String assignments = transition.getSqlSet();
        Map<String, Object> parameters = transition.getParamNameValuePairs();
        assertBound(conditions, parameters, "id", RECEIPT_ID);
        assertBound(conditions, parameters, "status", from);
        assertBound(assignments, parameters, "status", to);
        assertFalse(assignments.contains("negative"), "negative must be fixed when the refund is inserted");
    }

    /** Check each actual column binding, not unordered presence of DRAFT/CONFIRMED in the map. */
    private static void assertBound(String sql, Map<String, Object> parameters, String column, Object expected) {
        var matcher = Pattern.compile("(?<![\\w.])" + Pattern.quote(column)
            + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}").matcher(sql);
        assertTrue(matcher.find(), () -> "Missing bound column " + column + " in " + sql);
        assertEquals(expected, parameters.get(matcher.group(1)), column + " in " + sql);
        assertFalse(matcher.find(), "Column must have only one assignment/predicate: " + column);
    }

    private static Stream<Arguments> invalidPendingSources() {
        return Stream.of(
            Arguments.of("missing return", null),
            pendingCase("confirmed return", s -> s.setStatus("CONFIRMED")),
            pendingCase("void return", s -> s.setStatus("VOID")),
            pendingCase("null status", s -> s.setStatus(null)),
            pendingCase("refund disabled", s -> s.setNeedRefund(0)),
            pendingCase("null needRefund", s -> s.setNeedRefund(null)),
            pendingCase("invalid needRefund flag", s -> s.setNeedRefund(2)),
            pendingCase("already linked", s -> s.setRefundReceiptId(RECEIPT_ID)),
            pendingCase("missing original sale", s -> s.setSaleId(null)),
            pendingCase("missing customer", s -> s.setCustomerId(null)),
            pendingCase("missing account", s -> s.setRefundAccountId(null)),
            pendingCase("missing refund amount", s -> s.setRefundAmount(null)),
            pendingCase("zero refund", s -> s.setRefundAmount(BigDecimal.ZERO)),
            pendingCase("negative refund", s -> s.setRefundAmount(new BigDecimal("-0.01"))),
            pendingCase("missing total", s -> s.setTotalAmount(null)),
            pendingCase("refund exceeds total", s -> s.setTotalAmount(new BigDecimal("39.99"))),
            pendingCase("zero total", s -> s.setTotalAmount(BigDecimal.ZERO)),
            pendingCase("negative total", s -> s.setTotalAmount(new BigDecimal("-1.00")))
        );
    }

    private static Arguments pendingCase(String reason, Consumer<FinSaleReturnRefundSource> mutation) {
        FinSaleReturnRefundSource source = pendingSource();
        mutation.accept(source);
        return Arguments.of(reason, source);
    }

    private static Stream<Arguments> invalidOriginalReceivables() {
        return Stream.of(
            Arguments.of("missing AR", null),
            arCase("AP instead of AR", ar -> ar.setPartyType("AP")),
            arCase("null party type", ar -> ar.setPartyType(null)),
            arCase("different customer", ar -> ar.setPartyId(999L)),
            arCase("null customer", ar -> ar.setPartyId(null)),
            arCase("opening instead of sale", ar -> ar.setSourceType("OPENING")),
            arCase("null source type", ar -> ar.setSourceType(null)),
            arCase("different sale", ar -> ar.setSourceId(999L)),
            arCase("null source ID", ar -> ar.setSourceId(null))
        );
    }

    private static Arguments arCase(String reason, Consumer<FinArApVo> mutation) {
        FinArApVo original = originalReceivable(pendingSource());
        mutation.accept(original);
        return Arguments.of(reason, original);
    }

    private static Stream<Arguments> invalidWithdrawalSources() {
        return Stream.of(
            Arguments.of("missing return", null),
            withdrawalCase("draft return", s -> s.setStatus("DRAFT")),
            withdrawalCase("void return", s -> s.setStatus("VOID")),
            withdrawalCase("null status", s -> s.setStatus(null)),
            withdrawalCase("no linked receipt", s -> s.setRefundReceiptId(null)),
            withdrawalCase("missing sale", s -> s.setSaleId(null)),
            withdrawalCase("missing customer", s -> s.setCustomerId(null)),
            withdrawalCase("missing amount", s -> s.setRefundAmount(null)),
            withdrawalCase("refund disabled", s -> s.setNeedRefund(0)),
            withdrawalCase("null needRefund", s -> s.setNeedRefund(null)),
            withdrawalCase("invalid needRefund flag", s -> s.setNeedRefund(2)),
            withdrawalCase("zero amount", s -> s.setRefundAmount(BigDecimal.ZERO)),
            withdrawalCase("negative amount", s -> s.setRefundAmount(new BigDecimal("-0.01")))
        );
    }

    private static Arguments withdrawalCase(String reason, Consumer<FinSaleReturnRefundSource> mutation) {
        FinSaleReturnRefundSource source = confirmedSource();
        mutation.accept(source);
        return Arguments.of(reason, source);
    }

    private static Stream<Arguments> invalidLinkedReceipts() {
        return Stream.of(
            Arguments.of("missing receipt", null),
            receiptCase("ordinary receipt", r -> r.setNegative(0)),
            receiptCase("null refund flag", r -> r.setNegative(null)),
            receiptCase("invalid refund flag", r -> r.setNegative(2)),
            receiptCase("draft receipt", r -> r.setStatus("DRAFT")),
            receiptCase("void receipt", r -> r.setStatus("VOID")),
            receiptCase("null receipt status", r -> r.setStatus(null)),
            receiptCase("different customer", r -> r.setCustomerId(999L)),
            receiptCase("null customer", r -> r.setCustomerId(null)),
            receiptCase("different amount", r -> r.setAmount(new BigDecimal("40.01"))),
            receiptCase("zero amount", r -> r.setAmount(BigDecimal.ZERO)),
            receiptCase("negative amount", r -> r.setAmount(new BigDecimal("-40.00"))),
            receiptCase("different account", r -> r.setAccountId(999L)),
            receiptCase("null receipt account", r -> r.setAccountId(null))
        );
    }

    private static Arguments receiptCase(String reason, Consumer<FinReceipt> mutation) {
        FinReceipt receipt = linkedReceipt();
        mutation.accept(receipt);
        return Arguments.of(reason, receipt);
    }

    private static Stream<Arguments> invalidWithdrawalAllocations() {
        return Stream.of(
            Arguments.of("empty", List.of(), "必须等于收款金额"),
            Arguments.of("sum mismatch", List.of(allocation(1L, AR_ID, "39.99")), "必须等于收款金额"),
            Arguments.of("split over different ledgers", List.of(
                allocation(1L, AR_ID, "20.00"), allocation(2L, 3999L, "20.00")), "唯一原单台账"),
            Arguments.of("split over same ledger", List.of(
                allocation(1L, AR_ID, "20.00"), allocation(2L, AR_ID, "20.00")), "唯一原单台账")
        );
    }

    private static FinSaleReturnRefundSource pendingSource() {
        FinSaleReturnRefundSource source = new FinSaleReturnRefundSource();
        source.setNo("XT-PERSISTED-501");
        source.setSaleId(SALE_ID);
        source.setCustomerId(CUSTOMER_ID);
        source.setBizDate(BIZ_DATE);
        source.setStatus("DRAFT");
        source.setTotalAmount(new BigDecimal("100.00"));
        source.setNeedRefund(1);
        source.setRefundAmount(new BigDecimal("40.00"));
        source.setRefundAccountId(ACCOUNT_ID);
        return source;
    }

    private static FinSaleReturnRefundSource confirmedSource() {
        FinSaleReturnRefundSource source = pendingSource();
        source.setStatus("CONFIRMED");
        source.setRefundReceiptId(RECEIPT_ID);
        return source;
    }

    private static FinArApVo originalReceivable(FinSaleReturnRefundSource source) {
        FinArApVo original = new FinArApVo();
        original.setId(AR_ID);
        original.setPartyType("AR");
        original.setPartyId(source.getCustomerId());
        original.setSourceType("SALE");
        original.setSourceId(source.getSaleId());
        return original;
    }

    private static FinAccount activeAccount(Long accountId) {
        FinAccount account = new FinAccount();
        account.setId(accountId);
        account.setName("persisted-account-" + accountId);
        account.setStatus(1);
        return account;
    }

    private static FinReceipt linkedReceipt() {
        FinReceipt receipt = new FinReceipt();
        receipt.setId(RECEIPT_ID);
        receipt.setCustomerId(CUSTOMER_ID);
        receipt.setAmount(new BigDecimal("40.00"));
        receipt.setAccountId(ACCOUNT_ID);
        receipt.setNegative(1);
        receipt.setStatus("CONFIRMED");
        return receipt;
    }

    private static FinReceiptAlloc allocation(Long id, Long ledgerId, String amount) {
        FinReceiptAlloc row = new FinReceiptAlloc();
        row.setId(id);
        row.setReceiptId(RECEIPT_ID);
        row.setArApId(ledgerId);
        row.setAmount(new BigDecimal(amount));
        return row;
    }
}
