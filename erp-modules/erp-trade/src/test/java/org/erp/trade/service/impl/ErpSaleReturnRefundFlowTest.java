package org.erp.trade.service.impl;

import cn.dev33.satoken.exception.NotPermissionException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.fin.service.IFinReceiptService;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.domain.ErpSaleReturn;
import org.erp.trade.domain.ErpSaleReturnItem;
import org.erp.trade.domain.bo.ErpSaleReturnBo;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
import org.erp.trade.mapper.ErpSaleReturnItemMapper;
import org.erp.trade.mapper.ErpSaleReturnMapper;
import org.erp.trade.service.IErpOrderProgressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** Trade orchestration calls the controlled finance API by persisted return ID only. */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class ErpSaleReturnRefundFlowTest {

    private static final Long RETURN_ID = 501L;
    private static final Long SALE_ID = 901L;
    private static final Long CUSTOMER_ID = 203L;
    private static final Long ACCOUNT_ID = 17L;
    private static final Long REFUND_ID = 7007L;
    private static final Long SOURCE_ORDER_ID = 444L;
    private static final Long SALE_ITEM_ID = 902L;
    private static final Long PRODUCT_ID = 303L;
    private static final Date CUTOFF_DATE = java.sql.Date.valueOf("2026-01-01");
    private static final Date BIZ_DATE = java.sql.Date.valueOf("2026-01-02");
    private static final String RETURN_NO = "XT-PERSISTED-501";

    @Mock private ErpSaleReturnMapper returnMapper;
    @Mock private ErpSaleReturnItemMapper itemMapper;
    @Mock private ErpSaleMapper saleMapper;
    @Mock private ErpSaleItemMapper saleItemMapper;
    @Mock private ErpProductMapper productMapper;
    @Mock private ErpCustomerMapper customerMapper;
    @Mock private IInventoryService inventoryService;
    @Mock private IErpWarehouseService warehouseService;
    @Mock private IFinArApService arApService;
    @Mock private IFinReceiptService receiptService;
    @Mock private SequenceService sequenceService;
    @Mock private IErpOrderProgressService orderProgressService;
    @Mock private IFinOpeningGuardService openingGuardService;

    private ErpSaleReturnServiceImpl service;
    private MockedStatic<TenantHelper> tenantHelper;

    @BeforeAll
    static void initializeLambdaMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "sale-return-refund-flow-test");
        TableInfoHelper.initTableInfo(assistant, ErpSaleReturn.class);
        TableInfoHelper.initTableInfo(assistant, ErpSaleReturnItem.class);
    }

    @BeforeEach
    void setUp() {
        tenantHelper = mockStatic(TenantHelper.class);
        tenantHelper.when(TenantHelper::getTenantId).thenReturn("TENANT_TEST");
        service = new ErpSaleReturnServiceImpl(returnMapper, itemMapper, saleMapper, saleItemMapper,
            productMapper, customerMapper, inventoryService, warehouseService, arApService,
            receiptService, sequenceService, orderProgressService, openingGuardService);
    }

    @AfterEach
    void tearDown() {
        tenantHelper.close();
    }

    @ParameterizedTest(name = "{0} supplies the transaction required by controlled finance APIs")
    @ValueSource(strings = {"insert", "update", "confirm", "withdraw", "voidBill"})
    void mutatingTradeEntryDeclaresCallerTransaction(String methodName) throws NoSuchMethodException {
        Class<?>[] parameters = switch (methodName) {
            case "insert" -> new Class<?>[]{ErpSaleReturnBo.class};
            case "update" -> new Class<?>[]{Long.class, ErpSaleReturnBo.class};
            default -> new Class<?>[]{Long.class};
        };
        Transactional transaction = ErpSaleReturnServiceImpl.class.getMethod(methodName, parameters)
            .getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertEquals(Propagation.REQUIRED, transaction.propagation());
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
        assertTrue(Arrays.asList(transaction.rollbackFor()).contains(Exception.class));
    }

    @Test
    void confirmUsesPersistedReturnIdAndLinksRefundBeforeStockAndConditionalConfirmation() {
        ErpSaleReturn head = returnHead("DRAFT");
        ErpSaleReturnItem row = stubConfirmable(head);
        when(receiptService.insertSaleReturnRefund(RETURN_ID)).thenReturn(REFUND_ID);
        when(warehouseService.defaultWarehouseId()).thenReturn(1L);
        when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.confirm(RETURN_ID);

        InOrder order = inOrder(returnMapper, saleMapper, orderProgressService, openingGuardService,
            receiptService, arApService, inventoryService);
        order.verify(returnMapper).lockById(RETURN_ID);
        order.verify(saleMapper).lockById(SALE_ID);
        order.verify(orderProgressService).lockOrder("SALE", SOURCE_ORDER_ID);
        order.verify(openingGuardService).lockAndCheckBusinessDate("AR", CUSTOMER_ID, BIZ_DATE);
        order.verify(receiptService).insertSaleReturnRefund(RETURN_ID);
        order.verify(arApService).createBySaleReturn(RETURN_ID, RETURN_NO, CUSTOMER_ID,
            new BigDecimal("-100.00"), BIZ_DATE);
        order.verify(inventoryService).lockStockRows(List.of(PRODUCT_ID));
        order.verify(inventoryService).inbound("SALE_RETURN", RETURN_NO, RETURN_ID, PRODUCT_ID, 1L,
            new BigDecimal("2.0000"), new BigDecimal("7.5000"), "CUSTOMER", CUSTOMER_ID, BIZ_DATE, 0);
        order.verify(returnMapper).update(isNull(), any(Wrapper.class));
        order.verify(orderProgressService).recalc("SALE", SOURCE_ORDER_ID, null);
        order.verifyNoMoreInteractions();
        assertEquals(REFUND_ID, head.getRefundReceiptId());
        assertEquals(new BigDecimal("15.00"), row.getReturnCost());
        verify(itemMapper).updateById(row);
        verify(itemMapper).sumConfirmedReturnedQty(List.of(SALE_ITEM_ID), "TENANT_TEST", RETURN_ID);
        verify(saleItemMapper).selectByIds(List.of(SALE_ITEM_ID));
        verify(saleItemMapper, never()).selectById(any());
        LambdaUpdateWrapper<ErpSaleReturn> change = captureTransition();
        assertTransition(change, "DRAFT", "CONFIRMED", REFUND_ID);
        assertBound(change.getSqlSet(), change.getParamNameValuePairs(), "return_cost_amount", new BigDecimal("15.00"));
        verifyNoInteractions(productMapper, customerMapper, sequenceService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "insert", "update"})
    void openingCutoffStopsSaleReturnConfirmationBeforeRefundStockAndOrderWrites(String entry) {
        ErpSaleReturn persisted = returnHead("DRAFT");
        persisted.setBizDate(CUTOFF_DATE);
        if ("update".equals(entry)) {
            // 第一把锁供编辑，确认重新读取持久表头。
            stubConfirmable(returnHead("DRAFT"), persisted);
        } else {
            stubConfirmable(persisted);
        }
        ErpSaleReturnBo request = returnBo(true);
        if (!"confirm".equals(entry)) {
            when(saleMapper.selectById(SALE_ID)).thenReturn(originalSale());
        }
        if ("insert".equals(entry)) {
            stubReturnInsert();
        }
        ServiceException denial = new ServiceException("请先撤回期初", 40902);
        doThrow(denial).when(openingGuardService).lockAndCheckBusinessDate("AR", CUSTOMER_ID, CUTOFF_DATE);

        assertSame(denial, assertThrows(ServiceException.class, () -> {
            switch (entry) {
                case "confirm" -> service.confirm(RETURN_ID);
                case "insert" -> service.insert(request);
                case "update" -> service.update(RETURN_ID, request);
                default -> throw new AssertionError(entry);
            }
        }));

        verify(openingGuardService).lockAndCheckBusinessDate("AR", CUSTOMER_ID, CUTOFF_DATE);
        verifyNoInteractions(receiptService, arApService, inventoryService);
        verify(orderProgressService, never()).recalc(any(), any(), any());
        verify(returnMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @ParameterizedTest(name = "sale return draft without cutoff check (insert={0})")
    @ValueSource(booleans = {true, false})
    void savingDraftDoesNotCheckOpeningCutoff(boolean insert) {
        ErpSaleReturn head = returnHead("DRAFT");
        head.setBizDate(CUTOFF_DATE);
        when(saleMapper.selectById(SALE_ID)).thenReturn(originalSale());
        when(saleItemMapper.selectByIds(List.of(SALE_ITEM_ID))).thenReturn(List.of(originalSaleItem()));
        when(returnMapper.selectById(RETURN_ID)).thenReturn(head);
        if (insert) {
            stubReturnInsert();
        } else {
            when(returnMapper.lockById(RETURN_ID)).thenReturn(head);
        }

        ErpSaleReturnBo request = returnBo(false);
        var saved = insert ? service.insert(request) : service.update(RETURN_ID, request);

        assertEquals("DRAFT", saved.getStatus());
        verify(itemMapper).insert(any(ErpSaleReturnItem.class));
        verifyNoInteractions(openingGuardService, receiptService, arApService, inventoryService, orderProgressService);
    }

    @Test
    void confirmRejectsLegacyRefundDraftWithoutPersistedAccountBeforeFinanceOrStock() {
        ErpSaleReturn head = returnHead("DRAFT");
        head.setRefundAccountId(null);
        stubConfirmable(head);

        ServiceException denial = assertThrows(ServiceException.class, () -> service.confirm(RETURN_ID));

        assertEquals(40902, denial.getCode());
        assertTrue(denial.getMessage().contains("退款必须选择结算账户"));
        verifyNoInteractions(receiptService, arApService, inventoryService, warehouseService, sequenceService);
        verify(returnMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @ParameterizedTest(name = "confirmation propagates finance denial {0}")
    @MethodSource("confirmationDenials")
    void refundFailureDuringConfirmStopsNegativeLedgerAndStock(RuntimeException denial) {
        stubConfirmable(returnHead("DRAFT"));
        when(receiptService.insertSaleReturnRefund(RETURN_ID)).thenThrow(denial);

        assertSame(denial, assertThrows(RuntimeException.class, () -> service.confirm(RETURN_ID)));

        verify(receiptService).insertSaleReturnRefund(RETURN_ID);
        verifyNoMoreInteractions(receiptService);
        verifyNoInteractions(arApService, inventoryService, warehouseService, sequenceService);
        verify(returnMapper, never()).update(isNull(), any(Wrapper.class));
        verify(orderProgressService, never()).recalc(any(), any(), any());
    }

    @Test
    void confirmationWithoutRefundNeverCallsReceiptService() {
        ErpSaleReturn head = returnHead("DRAFT");
        head.setNeedRefund(0);
        head.setRefundAccountId(null);
        head.setRefundAmount(BigDecimal.ZERO);
        stubConfirmable(head);
        when(warehouseService.defaultWarehouseId()).thenReturn(1L);
        when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.confirm(RETURN_ID);

        verifyNoInteractions(receiptService, sequenceService);
        verify(arApService).createBySaleReturn(RETURN_ID, RETURN_NO, CUSTOMER_ID,
            new BigDecimal("-100.00"), BIZ_DATE);
        assertTransition(captureTransition(), "DRAFT", "CONFIRMED", null);
    }

    @Test
    void lostConfirmationCasIsRejectedAndDoesNotRecalculateOrders() {
        stubConfirmable(returnHead("DRAFT"));
        when(receiptService.insertSaleReturnRefund(RETURN_ID)).thenReturn(REFUND_ID);
        when(warehouseService.defaultWarehouseId()).thenReturn(1L);
        when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        ServiceException denial = assertThrows(ServiceException.class, () -> service.confirm(RETURN_ID));

        assertEquals(40902, denial.getCode());
        verify(receiptService).insertSaleReturnRefund(RETURN_ID);
        assertTransition(captureTransition(), "DRAFT", "CONFIRMED", REFUND_ID);
        verify(orderProgressService, never()).recalc(any(), any(), any());
        // Actual rollback of the already-issued refund/stock is integration coverage, not a mock claim.
    }

    @Test
    void withdrawalUsesReturnIdBeforeVoidingNegativeLedgerAndReversingStockThenClearsLink() {
        stubWithdrawable(returnHead("CONFIRMED"));
        when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.withdraw(RETURN_ID);

        InOrder order = inOrder(returnMapper, saleMapper, orderProgressService,
            receiptService, arApService, inventoryService);
        order.verify(returnMapper).lockById(RETURN_ID);
        order.verify(saleMapper).lockById(SALE_ID);
        order.verify(orderProgressService).lockOrder("SALE", SOURCE_ORDER_ID);
        order.verify(receiptService).withdrawSaleReturnRefund(RETURN_ID);
        order.verify(arApService).voidBySource("SALE_RETURN", RETURN_ID);
        order.verify(inventoryService).reverseByBill(RETURN_NO, RETURN_ID, BIZ_DATE);
        order.verify(returnMapper).update(isNull(), any(Wrapper.class));
        order.verify(orderProgressService).recalc("SALE", SOURCE_ORDER_ID, null);
        order.verifyNoMoreInteractions();
        LambdaUpdateWrapper<ErpSaleReturn> change = captureTransition();
        assertTransition(change, "CONFIRMED", "DRAFT", null);
        assertBound(change.getSqlSet(), change.getParamNameValuePairs(), "return_cost_amount", BigDecimal.ZERO);
        verifyNoInteractions(itemMapper, saleItemMapper, productMapper, customerMapper, warehouseService, sequenceService);
    }

    @Test
    void withdrawalWithoutAssociationNeverCallsReceiptService() {
        ErpSaleReturn head = returnHead("CONFIRMED");
        head.setNeedRefund(0);
        head.setRefundReceiptId(null);
        stubWithdrawable(head);
        when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.withdraw(RETURN_ID);

        verifyNoInteractions(receiptService, sequenceService);
        verify(arApService).voidBySource("SALE_RETURN", RETURN_ID);
        verify(inventoryService).reverseByBill(RETURN_NO, RETURN_ID, BIZ_DATE);
        assertTransition(captureTransition(), "CONFIRMED", "DRAFT", null);
    }

    @ParameterizedTest(name = "withdrawal propagates finance denial {0}")
    @MethodSource("withdrawalDenials")
    void refundFailureDuringWithdrawalStopsLedgerStockAndUnlinking(RuntimeException denial) {
        stubWithdrawable(returnHead("CONFIRMED"));
        doThrow(denial).when(receiptService).withdrawSaleReturnRefund(RETURN_ID);

        assertSame(denial, assertThrows(RuntimeException.class, () -> service.withdraw(RETURN_ID)));

        verify(receiptService).withdrawSaleReturnRefund(RETURN_ID);
        verifyNoMoreInteractions(receiptService);
        verifyNoInteractions(arApService, inventoryService, warehouseService, sequenceService);
        verify(returnMapper, never()).update(isNull(), any(Wrapper.class));
        verify(orderProgressService, never()).recalc(any(), any(), any());
    }

    @Test
    void lostWithdrawalCasIsRejectedAndDoesNotRecalculateOrders() {
        stubWithdrawable(returnHead("CONFIRMED"));
        when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        ServiceException denial = assertThrows(ServiceException.class, () -> service.withdraw(RETURN_ID));

        assertEquals(40902, denial.getCode());
        verify(receiptService).withdrawSaleReturnRefund(RETURN_ID);
        assertTransition(captureTransition(), "CONFIRMED", "DRAFT", null);
        verify(orderProgressService, never()).recalc(any(), any(), any());
    }

    private ErpSaleReturnItem stubConfirmable(ErpSaleReturn head, ErpSaleReturn... laterReads) {
        when(returnMapper.lockById(RETURN_ID)).thenReturn(head, laterReads);
        when(saleMapper.lockById(SALE_ID)).thenReturn(originalSale());
        ErpSaleReturnItem row = new ErpSaleReturnItem();
        row.setId(502L);
        row.setOrderId(RETURN_ID);
        row.setSaleItemId(SALE_ITEM_ID);
        row.setProductId(PRODUCT_ID);
        row.setQty(new BigDecimal("2.0000"));
        row.setPrice(new BigDecimal("50.0000"));
        row.setAmount(new BigDecimal("100.00"));
        row.setSeq(0);
        // One current-document item load and one grouped, tenant-scoped historical return quantity read.
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        when(itemMapper.sumConfirmedReturnedQty(any(), any(), any())).thenReturn(List.of());
        when(saleItemMapper.selectByIds(any())).thenReturn(List.of(originalSaleItem()));
        return row;
    }

    private void stubReturnInsert() {
        when(sequenceService.nextNo("XT")).thenReturn(RETURN_NO);
        when(returnMapper.insert(any(ErpSaleReturn.class))).thenAnswer(inv -> {
            inv.<ErpSaleReturn>getArgument(0).setId(RETURN_ID);
            return 1;
        });
    }

    private static ErpSaleReturnBo returnBo(boolean confirm) {
        ErpSaleReturnBo bo = new ErpSaleReturnBo();
        bo.setSaleId(SALE_ID);
        bo.setBizDate(new Date(CUTOFF_DATE.getTime() + 1_000));
        bo.setConfirm(confirm);
        bo.setNeedRefund(true);
        bo.setRefundAmount(new BigDecimal("40.00"));
        bo.setRefundAccountId(ACCOUNT_ID);
        ErpSaleReturnBo.ItemBo item = new ErpSaleReturnBo.ItemBo();
        item.setSaleItemId(SALE_ITEM_ID);
        item.setQty(new BigDecimal("2.0000"));
        item.setPrice(new BigDecimal("50.0000"));
        bo.setItems(List.of(item));
        return bo;
    }

    private static ErpSaleItem originalSaleItem() {
        ErpSaleItem original = new ErpSaleItem();
        original.setId(SALE_ITEM_ID);
        original.setOrderId(SALE_ID);
        original.setProductId(PRODUCT_ID);
        original.setQty(new BigDecimal("4.0000"));
        original.setUnitCost(new BigDecimal("7.5000"));
        return original;
    }

    private void stubWithdrawable(ErpSaleReturn head) {
        when(returnMapper.lockById(RETURN_ID)).thenReturn(head);
        when(saleMapper.lockById(SALE_ID)).thenReturn(originalSale());
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<ErpSaleReturn> captureTransition() {
        ArgumentCaptor<LambdaUpdateWrapper<ErpSaleReturn>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(returnMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private static void assertTransition(LambdaUpdateWrapper<ErpSaleReturn> change,
                                         String from, String to, Long refundReceiptId) {
        String conditions = change.getSqlSegment();
        String assignments = change.getSqlSet();
        Map<String, Object> parameters = change.getParamNameValuePairs();
        assertBound(conditions, parameters, "id", RETURN_ID);
        assertBound(conditions, parameters, "status", from);
        assertBound(assignments, parameters, "status", to);
        assertBound(assignments, parameters, "refund_receipt_id", refundReceiptId);
    }

    private static void assertBound(String sql, Map<String, Object> parameters, String column, Object expected) {
        var matcher = Pattern.compile("(?<![\\w.])" + Pattern.quote(column)
            + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}").matcher(sql);
        assertTrue(matcher.find(), () -> "Missing bound column " + column + " in " + sql);
        assertEquals(expected, parameters.get(matcher.group(1)), column + " in " + sql);
        assertFalse(matcher.find(), "Column must have only one assignment/predicate: " + column);
    }

    private static Stream<RuntimeException> confirmationDenials() {
        return financeDenials("erp:finance:receipt:confirm");
    }

    private static Stream<RuntimeException> withdrawalDenials() {
        return financeDenials("erp:finance:receipt:withdraw");
    }

    private static Stream<RuntimeException> financeDenials(String permission) {
        return Stream.of(new NotPermissionException(permission),
            new ServiceException("原销售台账归属或余额不匹配", 40902));
    }

    private static ErpSaleReturn returnHead(String status) {
        ErpSaleReturn head = new ErpSaleReturn();
        head.setId(RETURN_ID);
        head.setNo(RETURN_NO);
        head.setSaleId(SALE_ID);
        head.setCustomerId(CUSTOMER_ID);
        head.setBizDate(BIZ_DATE);
        head.setStatus(status);
        head.setTotalAmount(new BigDecimal("100.00"));
        head.setNeedRefund(1);
        head.setRefundAmount(new BigDecimal("40.00"));
        head.setRefundAccountId(ACCOUNT_ID);
        head.setRefundReceiptId("CONFIRMED".equals(status) ? REFUND_ID : null);
        return head;
    }

    private static ErpSale originalSale() {
        ErpSale sale = new ErpSale();
        sale.setId(SALE_ID);
        sale.setNo("XS-PERSISTED-901");
        sale.setCustomerId(CUSTOMER_ID);
        sale.setBizDate(java.sql.Date.valueOf("2025-12-31"));
        sale.setStatus("CONFIRMED");
        sale.setSourceOrderId(SOURCE_ORDER_ID);
        return sale;
    }
}
