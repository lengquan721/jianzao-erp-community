package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.fin.service.IFinReceiptService;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.common.sequence.SequenceService;
import org.erp.trade.domain.ErpPurchase;
import org.erp.trade.domain.ErpPurchaseItem;
import org.erp.trade.domain.ErpPurchaseReturn;
import org.erp.trade.domain.ErpPurchaseReturnItem;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.domain.ErpSaleReturn;
import org.erp.trade.domain.ErpSaleReturnItem;
import org.erp.trade.domain.bo.ErpPurchaseReturnBo;
import org.erp.trade.domain.vo.ErpReturnQtyAggregateVo;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.mapper.ErpPurchaseReturnItemMapper;
import org.erp.trade.mapper.ErpPurchaseReturnMapper;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.mockito.Mockito.when;

/** Direct regression tests for bounded return lookups; no database connection. */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class ErpReturnBatchingRegressionTest {

    private static final String TENANT = "TENANT_P06";
    private static final long SALE_ID = 901L;
    private static final long PURCHASE_ID = 902L;
    private static final long RETURN_ID = 501L;
    private static final Date CUTOFF_DATE = java.sql.Date.valueOf("2026-01-01");

    @Mock private ErpSaleReturnMapper saleReturnMapper;
    @Mock private ErpSaleReturnItemMapper saleReturnItemMapper;
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

    @Mock private ErpPurchaseReturnMapper purchaseReturnMapper;
    @Mock private ErpPurchaseReturnItemMapper purchaseReturnItemMapper;
    @Mock private ErpPurchaseMapper purchaseMapper;
    @Mock private ErpPurchaseItemMapper purchaseItemMapper;
    @Mock private ErpSupplierMapper supplierMapper;

    private MockedStatic<TenantHelper> tenantHelper;

    @BeforeAll
    static void initializeLambdaMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "trade-return-batch-test");
        for (Class<?> entity : List.of(ErpSale.class, ErpSaleItem.class, ErpSaleReturn.class,
            ErpSaleReturnItem.class, ErpPurchase.class, ErpPurchaseItem.class,
            ErpPurchaseReturn.class, ErpPurchaseReturnItem.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        tenantHelper = mockStatic(TenantHelper.class);
        tenantHelper.when(TenantHelper::getTenantId).thenReturn(TENANT);
    }

    @AfterEach
    void tearDown() {
        tenantHelper.close();
    }

    @Test
    void saleOriginalItemsDeduplicatesProductIdsAndBatchesReturnSumsOnce() {
        ErpSale sale = sale("CONFIRMED");
        ErpSaleItem first = saleItem(101L, 10L, "5");
        ErpSaleItem second = saleItem(102L, 10L, "7");
        when(saleMapper.selectById(SALE_ID)).thenReturn(sale);
        when(saleItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(productMapper.selectByIds(any())).thenReturn(List.of(product(10L)));
        when(saleReturnItemMapper.sumConfirmedReturnedQty(any(), any(), any())).thenReturn(
            List.of(aggregate(101L, "2")));

        var rows = new ErpSaleReturnServiceImpl(saleReturnMapper, saleReturnItemMapper, saleMapper,
            saleItemMapper, productMapper, customerMapper, inventoryService, warehouseService,
            arApService, receiptService, sequenceService, orderProgressService, openingGuardService).originalItems(SALE_ID);

        assertEquals(2, rows.size());
        assertEquals(new BigDecimal("2"), rows.get(0).getReturnedQty());
        assertEquals(new BigDecimal("3"), rows.get(0).getReturnableQty());
        assertEquals(BigDecimal.ZERO, rows.get(1).getReturnedQty());
        ArgumentCaptor<Collection<Long>> products = ArgumentCaptor.forClass(Collection.class);
        verify(productMapper).selectByIds(products.capture());
        assertEquals(List.of(10L), List.copyOf(products.getValue()));
        verify(saleReturnItemMapper).sumConfirmedReturnedQty(List.of(101L, 102L), TENANT, null);
        verify(saleReturnItemMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void saleReturnDetailBatchesRepeatedOriginalRowsAndHistoricalQty() {
        ErpSaleReturn head = saleReturnHead();
        ErpSaleReturnItem first = saleReturnItem(501L, 601L, "1");
        ErpSaleReturnItem second = saleReturnItem(502L, 601L, "2");
        when(saleReturnMapper.selectById(RETURN_ID)).thenReturn(head);
        when(saleReturnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(saleItemMapper.selectByIds(any())).thenReturn(List.of(saleItem(601L, 10L, "5")));
        when(productMapper.selectByIds(any())).thenReturn(List.of(product(10L)));
        when(saleReturnItemMapper.sumConfirmedReturnedQty(any(), any(), any()))
            .thenReturn(List.of(aggregate(601L, "3")));

        var detail = saleReturnService().selectById(RETURN_ID);

        assertEquals(2, detail.getItems().size());
        assertEquals(new BigDecimal("2"), detail.getItems().get(0).getReturnableQty());
        verify(saleItemMapper).selectByIds(List.of(601L));
        verify(saleReturnItemMapper).sumConfirmedReturnedQty(List.of(601L), TENANT, null);
        verify(saleItemMapper, never()).selectById(any());
    }

    @Test
    void purchaseOriginalItemsUsesOneAggregateForDistinctOriginalRowsAndSkipsEmptyBatch() {
        ErpPurchase purchase = purchase("CONFIRMED");
        ErpPurchaseItem first = purchaseItem(201L, 10L, "5");
        ErpPurchaseItem second = purchaseItem(202L, 10L, "7");
        when(purchaseMapper.selectById(PURCHASE_ID)).thenReturn(purchase);
        when(purchaseItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(productMapper.selectByIds(any())).thenReturn(List.of(product(10L)));
        when(purchaseReturnItemMapper.sumConfirmedReturnedQty(any(), any(), any())).thenReturn(
            List.of(aggregate(201L, "1.25")));

        ErpPurchaseReturnServiceImpl service = purchaseReturnService();
        var rows = service.originalItems(PURCHASE_ID);

        assertEquals(2, rows.size());
        assertEquals(new BigDecimal("1.25"), rows.get(0).getReturnedQty());
        assertEquals(new BigDecimal("3.75"), rows.get(0).getReturnableQty());
        verify(purchaseReturnItemMapper).sumConfirmedReturnedQty(List.of(201L, 202L), TENANT, null);

        when(purchaseItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        service.originalItems(PURCHASE_ID);
        verify(purchaseReturnItemMapper, org.mockito.Mockito.times(1))
            .sumConfirmedReturnedQty(List.of(201L, 202L), TENANT, null);
        verify(productMapper, org.mockito.Mockito.times(1)).selectByIds(any());
    }

    @Test
    void saleConfirmationRejectsMissingOriginalBeforeAggregatingOrTouchingFinanceAndStock() {
        ErpSaleReturn head = saleReturnHead();
        when(saleReturnMapper.lockById(RETURN_ID)).thenReturn(head);
        when(saleMapper.lockById(SALE_ID)).thenReturn(sale("CONFIRMED"));
        ErpSaleReturnItem row = saleReturnItem(301L, 999L, "1");
        when(saleReturnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        when(saleItemMapper.selectByIds(any())).thenReturn(List.of());

        ServiceException missing = assertThrows(ServiceException.class,
            () -> saleReturnService().confirm(RETURN_ID));

        assertTrue(missing.getMessage().contains("原销售明细行不存在"));
        verify(saleItemMapper).selectByIds(List.of(999L));
        verify(saleReturnItemMapper, never()).sumConfirmedReturnedQty(any(), any(), any());
        verify(arApService, never()).createBySaleReturn(any(), any(), any(), any(), any());
        verify(inventoryService, never()).lockStockRows(any());
    }

    @Test
    void purchaseReturnDetailBatchesRepeatedOriginalRowsAndHistoricalQty() {
        ErpPurchaseReturn head = purchaseReturnHead();
        ErpPurchaseReturnItem first = purchaseReturnItem(511L, 611L, "1");
        ErpPurchaseReturnItem second = purchaseReturnItem(512L, 611L, "2");
        when(purchaseReturnMapper.selectById(RETURN_ID)).thenReturn(head);
        when(purchaseReturnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(purchaseItemMapper.selectByIds(any())).thenReturn(List.of(purchaseItem(611L, 10L, "5")));
        when(productMapper.selectByIds(any())).thenReturn(List.of(product(10L)));
        when(purchaseReturnItemMapper.sumConfirmedReturnedQty(any(), any(), any()))
            .thenReturn(List.of(aggregate(611L, "3")));

        var detail = purchaseReturnService().selectById(RETURN_ID);

        assertEquals(2, detail.getItems().size());
        assertEquals(new BigDecimal("2"), detail.getItems().get(0).getReturnableQty());
        verify(purchaseItemMapper).selectByIds(List.of(611L));
        verify(purchaseReturnItemMapper).sumConfirmedReturnedQty(List.of(611L), TENANT, null);
        verify(purchaseItemMapper, never()).selectById(any());
    }

    @Test
    void purchaseConfirmationChecksConfirmedReturnedQtyAndExcludesCurrentBillBeforeStock() {
        ErpPurchaseReturn head = purchaseReturnHead();
        when(purchaseReturnMapper.lockById(RETURN_ID)).thenReturn(head);
        when(purchaseMapper.selectById(PURCHASE_ID)).thenReturn(purchase("CONFIRMED"));
        ErpPurchaseReturnItem row = purchaseReturnItem(302L, 402L, "2");
        when(purchaseReturnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        when(purchaseItemMapper.selectByIds(any())).thenReturn(List.of(purchaseItem(402L, 10L, "4")));
        when(purchaseReturnItemMapper.sumConfirmedReturnedQty(any(), any(), any())).thenReturn(
            List.of(aggregate(402L, "3")));

        ServiceException overReturn = assertThrows(ServiceException.class,
            () -> purchaseReturnService().confirm(RETURN_ID));

        assertEquals(40902, overReturn.getCode());
        verify(purchaseReturnItemMapper).sumConfirmedReturnedQty(List.of(402L), TENANT, RETURN_ID);
        verify(inventoryService, never()).lockStockRows(any());
        verify(arApService, never()).createByPurchaseReturn(any(), any(), any(), any(), any());
    }

    @Test
    void purchaseConfirmationReusesOriginalCostAndKeepsNegativeLedgerAmount() {
        ErpPurchaseReturn head = purchaseReturnHead();
        head.setBizDate(java.sql.Date.valueOf("2026-01-02"));
        ErpPurchaseReturnItem row = purchaseReturnItem(304L, 404L, "2");
        ErpPurchaseItem original = purchaseItem(404L, 10L, "4");
        ErpPurchase originalHead = purchase("CONFIRMED");
        originalHead.setSourceOrderId(444L);
        when(purchaseReturnMapper.lockById(RETURN_ID)).thenReturn(head);
        when(purchaseMapper.selectById(PURCHASE_ID)).thenReturn(originalHead);
        when(purchaseReturnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        when(purchaseItemMapper.selectByIds(any())).thenReturn(List.of(original));
        when(purchaseReturnItemMapper.sumConfirmedReturnedQty(any(), any(), any())).thenReturn(List.of());
        when(warehouseService.defaultWarehouseId()).thenReturn(1L);
        when(purchaseReturnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        purchaseReturnService().confirm(RETURN_ID);

        assertEquals(new BigDecimal("8.25"), row.getUnitCost());
        assertEquals(new BigDecimal("16.50"), row.getReturnCost());
        verify(purchaseReturnItemMapper).sumConfirmedReturnedQty(List.of(404L), TENANT, RETURN_ID);
        InOrder order = inOrder(purchaseReturnMapper, orderProgressService, openingGuardService, inventoryService, arApService);
        order.verify(purchaseReturnMapper).lockPurchaseForReturn(PURCHASE_ID);
        order.verify(orderProgressService).lockOrder(IErpOrderProgressService.TYPE_PURCHASE, 444L);
        order.verify(openingGuardService).lockAndCheckBusinessDate("AP", 41L, head.getBizDate());
        order.verify(inventoryService).lockStockRows(List.of(10L));
        order.verify(inventoryService).outbound("PURCHASE_RETURN", "CT-P06", RETURN_ID, 10L, 1L,
            new BigDecimal("2"), new BigDecimal("8.25"), "SUPPLIER", 41L, head.getBizDate(), 0);
        order.verify(arApService).createByPurchaseReturn(RETURN_ID, "CT-P06", 41L,
            new BigDecimal("-20.00"), head.getBizDate());
        order.verify(orderProgressService).recalc(IErpOrderProgressService.TYPE_PURCHASE, 444L, null);
        verify(purchaseItemMapper).selectByIds(List.of(404L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "insert", "update"})
    void openingCutoffStopsPurchaseReturnConfirmationBeforeStockOrderAndFinanceWrites(String entry) {
        ErpPurchaseReturn persisted = purchaseReturnHead();
        persisted.setBizDate(CUTOFF_DATE);
        when(purchaseReturnMapper.lockById(RETURN_ID)).thenReturn(persisted);
        stubOriginalPurchaseForReturn();
        when(purchaseReturnItemMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(purchaseReturnItem(304L, 404L, "2")));
        when(purchaseReturnItemMapper.sumConfirmedReturnedQty(List.of(404L), TENANT, RETURN_ID)).thenReturn(List.of());
        ErpPurchaseReturnBo request = purchaseReturnBo(true);
        if ("insert".equals(entry)) {
            stubPurchaseReturnInsert();
        } else if ("update".equals(entry)) {
            when(purchaseReturnMapper.selectById(RETURN_ID)).thenReturn(purchaseReturnHead());
        }
        ServiceException denial = new ServiceException("请先撤回期初", 40902);
        doThrow(denial).when(openingGuardService).lockAndCheckBusinessDate("AP", 41L, CUTOFF_DATE);

        var service = purchaseReturnService();
        assertSame(denial, assertThrows(ServiceException.class, () -> {
            switch (entry) {
                case "confirm" -> service.confirm(RETURN_ID);
                case "insert" -> service.insert(request);
                case "update" -> service.update(RETURN_ID, request);
                default -> throw new AssertionError(entry);
            }
        }));

        verify(openingGuardService).lockAndCheckBusinessDate("AP", 41L, CUTOFF_DATE);
        verifyNoInteractions(arApService, inventoryService);
        verify(orderProgressService, never()).recalc(any(), any(), any());
        verify(purchaseReturnMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @ParameterizedTest(name = "purchase return draft without cutoff check (insert={0})")
    @ValueSource(booleans = {true, false})
    void savingPurchaseReturnDraftDoesNotCheckOpeningCutoff(boolean insert) {
        ErpPurchaseReturn head = purchaseReturnHead();
        head.setBizDate(CUTOFF_DATE);
        when(purchaseReturnMapper.selectById(RETURN_ID)).thenReturn(head);
        stubOriginalPurchaseForReturn();
        if (insert) {
            stubPurchaseReturnInsert();
        }

        var service = purchaseReturnService();
        ErpPurchaseReturnBo request = purchaseReturnBo(false);
        var saved = insert ? service.insert(request) : service.update(RETURN_ID, request);

        assertEquals("DRAFT", saved.getStatus());
        verify(purchaseReturnItemMapper).insert(any(ErpPurchaseReturnItem.class));
        verifyNoInteractions(openingGuardService, arApService, inventoryService, orderProgressService);
    }

    @Test
    void saleConfirmationUsesSingleGroupedConfirmedQuantityQueryToRejectOverReturn() {
        ErpSaleReturn head = saleReturnHead();
        when(saleReturnMapper.lockById(RETURN_ID)).thenReturn(head);
        when(saleMapper.lockById(SALE_ID)).thenReturn(sale("CONFIRMED"));
        when(saleReturnItemMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(saleReturnItem(303L, 403L, "2")));
        when(saleItemMapper.selectByIds(any())).thenReturn(List.of(saleItem(403L, 10L, "4")));
        when(saleReturnItemMapper.sumConfirmedReturnedQty(any(), any(), any()))
            .thenReturn(List.of(aggregate(403L, "3")));

        ServiceException overReturn = assertThrows(ServiceException.class,
            () -> saleReturnService().confirm(RETURN_ID));

        assertEquals(40902, overReturn.getCode());
        verify(saleReturnItemMapper).sumConfirmedReturnedQty(List.of(403L), TENANT, RETURN_ID);
        verify(inventoryService, never()).lockStockRows(any());
        verify(receiptService, never()).insertSaleReturnRefund(any());
        verify(arApService, never()).createBySaleReturn(any(), any(), any(), any(), any());
    }

    private void stubOriginalPurchaseForReturn() {
        ErpPurchase original = purchase("CONFIRMED");
        original.setBizDate(java.sql.Date.valueOf("2025-12-31"));
        when(purchaseMapper.selectById(PURCHASE_ID)).thenReturn(original);
        when(purchaseItemMapper.selectByIds(List.of(404L))).thenReturn(List.of(purchaseItem(404L, 10L, "4")));
    }

    private void stubPurchaseReturnInsert() {
        when(sequenceService.nextNo("CT")).thenReturn("CT-P06");
        when(purchaseReturnMapper.insert(any(ErpPurchaseReturn.class))).thenAnswer(inv -> {
            inv.<ErpPurchaseReturn>getArgument(0).setId(RETURN_ID);
            return 1;
        });
    }

    private static ErpPurchaseReturnBo purchaseReturnBo(boolean confirm) {
        ErpPurchaseReturnBo bo = new ErpPurchaseReturnBo();
        bo.setPurchaseId(PURCHASE_ID);
        bo.setBizDate(new Date(CUTOFF_DATE.getTime() + 1_000));
        bo.setConfirm(confirm);
        ErpPurchaseReturnBo.ItemBo item = new ErpPurchaseReturnBo.ItemBo();
        item.setPurchaseItemId(404L);
        item.setQty(new BigDecimal("2"));
        item.setPrice(BigDecimal.TEN);
        bo.setItems(List.of(item));
        return bo;
    }

    private ErpSaleReturnServiceImpl saleReturnService() {
        return new ErpSaleReturnServiceImpl(saleReturnMapper, saleReturnItemMapper, saleMapper, saleItemMapper,
            productMapper, customerMapper, inventoryService, warehouseService, arApService,
            receiptService, sequenceService, orderProgressService, openingGuardService);
    }

    private ErpPurchaseReturnServiceImpl purchaseReturnService() {
        return new ErpPurchaseReturnServiceImpl(purchaseReturnMapper, purchaseReturnItemMapper, purchaseMapper,
            purchaseItemMapper, productMapper, supplierMapper, inventoryService, warehouseService,
            arApService, sequenceService, orderProgressService, openingGuardService);
    }

    private ErpSale sale(String status) {
        ErpSale sale = new ErpSale();
        sale.setId(SALE_ID);
        sale.setCustomerId(40L);
        sale.setStatus(status);
        return sale;
    }

    private ErpPurchase purchase(String status) {
        ErpPurchase purchase = new ErpPurchase();
        purchase.setId(PURCHASE_ID);
        purchase.setSupplierId(41L);
        purchase.setStatus(status);
        return purchase;
    }

    private ErpSaleReturn saleReturnHead() {
        ErpSaleReturn head = new ErpSaleReturn();
        head.setId(RETURN_ID);
        head.setNo("XT-P06");
        head.setSaleId(SALE_ID);
        head.setCustomerId(40L);
        head.setStatus("DRAFT");
        head.setTotalAmount(new BigDecimal("20.00"));
        head.setNeedRefund(0);
        return head;
    }

    private ErpPurchaseReturn purchaseReturnHead() {
        ErpPurchaseReturn head = new ErpPurchaseReturn();
        head.setId(RETURN_ID);
        head.setNo("CT-P06");
        head.setPurchaseId(PURCHASE_ID);
        head.setSupplierId(41L);
        head.setStatus("DRAFT");
        head.setTotalAmount(new BigDecimal("20.00"));
        return head;
    }

    private ErpSaleItem saleItem(Long id, Long productId, String qty) {
        ErpSaleItem row = new ErpSaleItem();
        row.setId(id);
        row.setOrderId(SALE_ID);
        row.setProductId(productId);
        row.setQty(new BigDecimal(qty));
        row.setUnitCost(new BigDecimal("7.50"));
        return row;
    }

    private ErpPurchaseItem purchaseItem(Long id, Long productId, String qty) {
        ErpPurchaseItem row = new ErpPurchaseItem();
        row.setId(id);
        row.setOrderId(PURCHASE_ID);
        row.setProductId(productId);
        row.setQty(new BigDecimal(qty));
        row.setCostPrice(new BigDecimal("8.25"));
        return row;
    }

    private ErpSaleReturnItem saleReturnItem(Long id, Long originalId, String qty) {
        ErpSaleReturnItem row = new ErpSaleReturnItem();
        row.setId(id);
        row.setOrderId(RETURN_ID);
        row.setSaleItemId(originalId);
        row.setProductId(10L);
        row.setQty(new BigDecimal(qty));
        return row;
    }

    private ErpPurchaseReturnItem purchaseReturnItem(Long id, Long originalId, String qty) {
        ErpPurchaseReturnItem row = new ErpPurchaseReturnItem();
        row.setId(id);
        row.setOrderId(RETURN_ID);
        row.setPurchaseItemId(originalId);
        row.setProductId(10L);
        row.setQty(new BigDecimal(qty));
        return row;
    }

    private ErpProduct product(Long id) {
        ErpProduct product = new ErpProduct();
        product.setId(id);
        product.setCode("P-" + id);
        product.setName("Product " + id);
        return product;
    }

    private ErpReturnQtyAggregateVo aggregate(Long originalItemId, String returnedQty) {
        ErpReturnQtyAggregateVo aggregate = new ErpReturnQtyAggregateVo();
        aggregate.setOriginalItemId(originalItemId);
        aggregate.setReturnedQty(new BigDecimal(returnedQty));
        return aggregate;
    }
}
