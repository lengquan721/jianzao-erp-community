package org.erp.stock.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.stock.domain.ErpStock;
import org.erp.stock.domain.ErpStockLog;
import org.erp.stock.domain.vo.ErpStockLogVo;
import org.erp.stock.domain.vo.ErpStockVo;
import org.erp.stock.mapper.ErpStockLogMapper;
import org.erp.stock.mapper.ErpStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InventoryServiceImpl 单元测试
 *
 * 覆盖 DOC/11 §8.2/8.3 规则与 D2 加权全量重放：
 * 入库加权、出库条件扣减与均价不变、撤回反向流水与重复撤回阻断、
 * 重放排序/清零/部分剔除/负库存/零数量，及参数校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class InventoryServiceImplTest {

    private static final long PRODUCT_ID = 1L;
    private static final long WAREHOUSE_ID = 1L;

    @Mock
    private ErpStockMapper stockMapper;
    @Mock
    private ErpStockLogMapper stockLogMapper;
    @Mock
    private ErpProductMapper productMapper;
    @Mock
    private ErpSupplierMapper supplierMapper;
    @Mock
    private ErpCustomerMapper customerMapper;

    private InventoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InventoryServiceImpl(stockMapper, stockLogMapper, productMapper,
            supplierMapper, customerMapper);
        when(stockLogMapper.insert(any(ErpStockLog.class))).thenReturn(1);
        when(stockMapper.insert(any(ErpStock.class))).thenReturn(1);
        when(stockMapper.addQty(any(), any(), any())).thenReturn(1);
        when(productMapper.selectByIds(any())).thenReturn(List.of());
        when(supplierMapper.selectByIds(any())).thenReturn(List.of());
        when(customerMapper.selectByIds(any())).thenReturn(List.of());
        when(stockMapper.selectForUpdate(any(), any())).thenReturn(stockRow("50", "10.0000"));
        when(stockLogMapper.selectBillRowsForUpdate(any(), any(), any())).thenReturn(List.of());
        when(stockLogMapper.selectReplayRowsForUpdate(any(), any())).thenReturn(List.of());
    }

    // ---------- 出库 ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void outbound_qtyMustBePositive(BigDecimal qty) {
        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.outbound("SALE", "S0001", 100L, PRODUCT_ID, WAREHOUSE_ID,
                qty, "CUSTOMER", 9L, new Date(), 1));
        assertEquals("出库数量必须大于0", ex.getMessage());
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    @Test
    void outbound_insufficientStock_rejectedWith40901() {
        stubStockRowExists();
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("50", "10.0000"));
        when(stockMapper.deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("60"))).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.outbound("SALE", "S0001", 100L, PRODUCT_ID, WAREHOUSE_ID,
                new BigDecimal("60"), "CUSTOMER", 9L, new Date(), 1));

        assertEquals(40901, ex.getCode());
        assertTrue(ex.getMessage().contains("库存不足"));
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    @Test
    void outbound_success_deductsQty_keepsAvgCost_writesLog() {
        stubStockRowExists();
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("100", "10.6666"));
        // 模拟 RR 的锁前快照；既不能用于成本，也不能用于扣减后结存。
        when(stockMapper.selectOne(any())).thenReturn(stockRow("40", "8.3333"));
        when(stockMapper.deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("3"))).thenReturn(1);

        BigDecimal usedCost = service.outbound("SALE", "S0001", 100L, PRODUCT_ID, WAREHOUSE_ID,
            new BigDecimal("3"), "CUSTOMER", 9L, new Date(0), 1);
        assertEquals(0, new BigDecimal("10.6666").compareTo(usedCost));

        verify(stockMapper).deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("3"));
        // 出库不改变均价
        verify(stockMapper, never()).updateAvgCost(any(), any(), any());

        ArgumentCaptor<ErpStockLog> captor = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(stockLogMapper).insert(captor.capture());
        ErpStockLog log = captor.getValue();
        assertEquals("SALE", log.getBizType());
        assertEquals(0, new BigDecimal("-3").compareTo(log.getQty()));
        assertEquals(0, new BigDecimal("10.6666").compareTo(log.getUnitCost()));
        // 金额 = 10.6666 * 3 = 31.9998 → HALF_UP 2 位 = 32.00
        assertEquals(0, new BigDecimal("32.00").compareTo(log.getAmount()));
        assertEquals(0, new BigDecimal("97").compareTo(log.getBalanceAfter()));
        verify(stockMapper, never()).selectOne(any());
    }

    @Test
    void outbound_explicitCostPreservesOriginalCostAndUsesLockedBalance() {
        stubStockRowExists();
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("15", "7.3333"));
        when(stockMapper.deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("3"))).thenReturn(1);

        service.outbound("PURCHASE_RETURN", "PT0001", 300L, PRODUCT_ID, WAREHOUSE_ID,
            new BigDecimal("3"), new BigDecimal("10"), "SUPPLIER", 8L, new Date(0), 1);

        ArgumentCaptor<ErpStockLog> captor = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(stockLogMapper).insert(captor.capture());
        assertEquals(0, new BigDecimal("10").compareTo(captor.getValue().getUnitCost()));
        assertEquals(0, new BigDecimal("12").compareTo(captor.getValue().getBalanceAfter()));
        verify(stockMapper, never()).selectOne(any());
    }

    // ---------- 入库 ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-5"})
    void inbound_qtyMustBePositive(BigDecimal qty) {
        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.inbound("PURCHASE", "P0001", 200L, PRODUCT_ID, WAREHOUSE_ID,
                qty, new BigDecimal("10"), "SUPPLIER", 8L, new Date(), 1));
        assertEquals("入库数量必须大于0", ex.getMessage());
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    @ParameterizedTest(name = "[{index}] 旧{0}@{1} + 入{2}@{3} → 均价{4}")
    @CsvSource(value = {
        "0|0|100|10.6666|10.6666",
        "50|10.0000|50|12.0000|11.0000",
        "50|10.6666|10|31.7590|14.1820",
        "15|7.3333|3|20|9.4444",
        "0|0|10|0|0.0000"
    }, delimiter = '|')
    void inbound_weightedAverage(String oldQty, String oldCost, String inQty, String inCost, String expectedAvg) {
        stubStockRowExists();
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID))
            .thenReturn(stockRow(oldQty, oldCost));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("10", "6.0000"));

        service.inbound("PURCHASE", "P0001", 200L, PRODUCT_ID, WAREHOUSE_ID,
            new BigDecimal(inQty), new BigDecimal(inCost), "SUPPLIER", 8L, new Date(0), 1);

        ArgumentCaptor<BigDecimal> avgCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockMapper).updateAvgCost(eq(PRODUCT_ID), eq(WAREHOUSE_ID), avgCaptor.capture());
        assertEquals(0, new BigDecimal(expectedAvg).compareTo(avgCaptor.getValue()));

        ArgumentCaptor<ErpStockLog> logCaptor = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(stockLogMapper).insert(logCaptor.capture());
        ErpStockLog log = logCaptor.getValue();
        assertEquals(0, new BigDecimal(inQty).compareTo(log.getQty()));
        assertEquals(0, new BigDecimal(inCost).compareTo(log.getUnitCost()));
        assertEquals(0, new BigDecimal(inQty).multiply(new BigDecimal(inCost)).compareTo(log.getAmount()));
        assertEquals(0, new BigDecimal(oldQty).add(new BigDecimal(inQty)).compareTo(log.getBalanceAfter()));
        verify(stockMapper, never()).selectOne(any());
    }

    @Test
    void inbound_withoutLockedRowDoesNotWriteBalanceOrLog() {
        stubStockRowExists();
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(null);
        var rejected = assertThrows(ServiceException.class, () -> service.inbound(
            "PURCHASE", "P0001", 200L, PRODUCT_ID, WAREHOUSE_ID, BigDecimal.TEN, BigDecimal.ONE,
            "SUPPLIER", 8L, new Date(0), 1));
        assertEquals(40902, rejected.getCode());
        verify(stockMapper, never()).addQty(any(), any(), any());
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    @Test
    void inbound_createsStockRowWhenMissing() {
        when(stockMapper.selectCount(any())).thenReturn(0L);
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("0", "0"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("0", "0"));
        when(stockMapper.addQty(any(), any(), any())).thenReturn(1);

        service.inbound("PURCHASE", "P0001", 200L, PRODUCT_ID, WAREHOUSE_ID,
            new BigDecimal("10"), new BigDecimal("5"), null, null, new Date(0), 1);

        ArgumentCaptor<ErpStock> rowCaptor = ArgumentCaptor.forClass(ErpStock.class);
        verify(stockMapper).insert(rowCaptor.capture());
        assertEquals(PRODUCT_ID, rowCaptor.getValue().getProductId());
        assertEquals(WAREHOUSE_ID, rowCaptor.getValue().getWarehouseId());

        ArgumentCaptor<ErpStockLog> logCaptor = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(stockLogMapper).insert(logCaptor.capture());
        assertEquals("NONE", logCaptor.getValue().getPartnerType());
    }

    // ---------- 数量精度（DOC/26 §10） ----------

    @ParameterizedTest(name = "数量 {0} 在 {1} 位精度内应放行")
    @CsvSource(value = {
        "3|0",
        "1.5|1",
        "1.50|1",
        "2.500|1",
        "0.001|3"
    }, delimiter = '|')
    void inbound_qtyWithinProductScale_passes(String qty, int qtyScale) {
        stubProduct(qtyScale);
        stubStockRowExists();
        when(stockMapper.addQty(any(), any(), any())).thenReturn(1);

        service.inbound("PURCHASE", "P0001", 200L, PRODUCT_ID, WAREHOUSE_ID,
            new BigDecimal(qty), new BigDecimal("10"), "SUPPLIER", 8L, new Date(0), 1);

        verify(stockLogMapper).insert(any(ErpStockLog.class));
    }

    @Test
    void inbound_qtyExceedsProductScale_rejectedWithProductName() {
        stubProduct(0);

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.inbound("PURCHASE", "P0001", 200L, PRODUCT_ID, WAREHOUSE_ID,
                new BigDecimal("1.5"), new BigDecimal("10"), "SUPPLIER", 8L, new Date(0), 1));

        assertEquals("商品「脱模剂」数量最多支持 0 位小数", ex.getMessage());
        verify(stockMapper, never()).addQty(any(), any(), any());
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    @Test
    void outbound_bothOverloads_qtyExceedsProductScale_rejected() {
        stubProduct(2);

        ServiceException noCost = assertThrows(ServiceException.class, () ->
            service.outbound("SALE", "S0001", 100L, PRODUCT_ID, WAREHOUSE_ID,
                new BigDecimal("1.234"), "CUSTOMER", 9L, new Date(0), 1));
        ServiceException withCost = assertThrows(ServiceException.class, () ->
            service.outbound("SALE", "S0001", 100L, PRODUCT_ID, WAREHOUSE_ID,
                new BigDecimal("1.234"), new BigDecimal("10"), "CUSTOMER", 9L, new Date(0), 1));

        assertEquals("商品「脱模剂」数量最多支持 2 位小数", noCost.getMessage());
        assertEquals(noCost.getMessage(), withCost.getMessage());
        verify(stockMapper, never()).deductQty(any(), any(), any());
    }



    @Test
    void reverse_legacyQtyBeyondProductScale_notBlocked() {
        // 撤回不经过精度校验：历史 3 位小数流水在商品改为整数后仍可撤回
        stubProduct(0);
        ErpStockLog origin = purchaseLog(1, "1.234", "10.0000");
        stubReverse(List.of(origin), List.of(), List.of(origin, voidLog(2, "-1.234", "10.0000")));
        when(stockMapper.selectCount(any())).thenReturn(1L);
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("1.234", "10.0000"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("0", "0"));
        when(stockMapper.deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("1.234"))).thenReturn(1);

        service.reverse("P0001", 200L, PRODUCT_ID, WAREHOUSE_ID, new Date(0), 1);

        verify(stockMapper).deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("1.234"));
    }

    // ---------- 撤回 ----------

    @Test
    void reverse_noOriginLog_rejected() {
        when(stockLogMapper.selectBillRowsForUpdate(any(), any(), any())).thenReturn(List.of());
        when(stockLogMapper.selectBillStockKeys(any())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.reverse("S9999", 300L, PRODUCT_ID, WAREHOUSE_ID, new Date(), 1));
        assertTrue(ex.getMessage().contains("未找到可回退的库存流水"));
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    @Test
    void reverse_alreadyVoided_rejected() {
        // 原始流水 id=1 已被 VOID（partnerId=1）配对 → 全部代次已回退，拒绝
        ErpStockLog origin = purchaseLog(1, "50", "10.0000");
        ErpStockLog voidRow = voidLog(2, "-50", "10.0000");
        voidRow.setPartnerId(1L);
        stubReverse(List.of(origin), List.of(voidRow), List.of());

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.reverse("P0001", 200L, PRODUCT_ID, WAREHOUSE_ID, new Date(), 1));
        assertTrue(ex.getMessage().contains("禁止重复回退"));
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    @Test
    void reverse_inbound_deductsQty_writesVoidLog_replaysToZero() {
        ErpStockLog origin = purchaseLog(1, "50", "10.0000");
        stubReverse(List.of(origin), List.of(), List.of(origin, voidLog(2, "-50", "10.0000")));
        when(stockMapper.selectCount(any())).thenReturn(1L);
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("50", "10.0000"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("0", "0"));
        when(stockMapper.deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("50"))).thenReturn(1);

        service.reverse("P0001", 200L, PRODUCT_ID, WAREHOUSE_ID, new Date(0), 1);

        verify(stockMapper).deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("50"));

        ArgumentCaptor<ErpStockLog> logCaptor = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(stockLogMapper).insert(logCaptor.capture());
        ErpStockLog voidLog = logCaptor.getValue();
        assertEquals("VOID", voidLog.getBizType());
        assertEquals("P0001", voidLog.getBizNo());
        assertEquals(0, new BigDecimal("-50").compareTo(voidLog.getQty()));
        // 反向金额 = 原金额取负
        assertEquals(0, new BigDecimal("-500.00").compareTo(voidLog.getAmount()));

        // 重放后全部清零
        ArgumentCaptor<BigDecimal> avgCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockMapper).updateAvgCost(eq(PRODUCT_ID), eq(WAREHOUSE_ID), avgCaptor.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(avgCaptor.getValue()));
    }

    @Test
    void reverse_outbound_addsQtyBack_replaysAvgCost() {
        // 已有 +30@10 入库、-10 销售出库；撤回销售 → 回补 10，重放后 30@10
        ErpStockLog purchase = purchaseLog(1, "30", "10.0000");
        ErpStockLog sale = saleLog(2, "-10", "10.0000");
        stubReverse(List.of(sale), List.of(), List.of(purchase, sale, voidLog(3, "10", "10.0000")));
        when(stockMapper.selectCount(any())).thenReturn(1L);
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("20", "10.0000"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("30", "10.0000"));
        when(stockMapper.addQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("10"))).thenReturn(1);

        service.reverse("S0001", 100L, PRODUCT_ID, WAREHOUSE_ID, new Date(0), 1);

        verify(stockMapper).addQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("10"));
        ArgumentCaptor<BigDecimal> avgCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockMapper).updateAvgCost(eq(PRODUCT_ID), eq(WAREHOUSE_ID), avgCaptor.capture());
        assertEquals(0, new BigDecimal("10.0000").compareTo(avgCaptor.getValue()));
    }

    @Test
    void reverse_现录现卖同单先回补出库再扣减入库() {
        // 现录现卖：QUICK_IN +100（id=1）后 SALE -100（id=2），库存归零；
        // 回退必须先回补出库再扣回入库，否则先扣入库会误判库存不足（DOC/26 §8.3）
        ErpStockLog quickIn = logRow(1, "QUICK_IN", "XS0001", "100", "8.0000", new Date(0));
        ErpStockLog sale = logRow(2, "SALE", "XS0001", "-100", "8.0000", new Date(0));
        stubReverse(List.of(quickIn, sale), List.of(), List.of(quickIn, sale,
            logRow(3, "VOID", "XS0001", "100", "8.0000", new Date(0)),
            logRow(4, "VOID", "XS0001", "-100", "8.0000", new Date(0))));
        when(stockMapper.selectCount(any())).thenReturn(1L);
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("0", "8.0000"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("0", "0"));
        when(stockMapper.addQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("100"))).thenReturn(1);
        when(stockMapper.deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("100"))).thenReturn(1);

        service.reverse("XS0001", 100L, PRODUCT_ID, WAREHOUSE_ID, new Date(0), 1);

        InOrder order = inOrder(stockMapper);
        order.verify(stockMapper).addQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("100"));
        order.verify(stockMapper).deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("100"));
        ArgumentCaptor<ErpStockLog> logCaptor = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(stockLogMapper, org.mockito.Mockito.times(2)).insert(logCaptor.capture());
        // 第一条反向流水冲销出库（+100），第二条冲销入库（-100）
        assertEquals(0, new BigDecimal("100").compareTo(logCaptor.getAllValues().get(0).getQty()));
        assertEquals(0, new BigDecimal("-100").compareTo(logCaptor.getAllValues().get(1).getQty()));
    }

    @Test
    void reverse_secondGeneration_notBlockedByFirstGenVoids() {
        // 致命缺陷回归：confirm→withdraw→confirm→void 第二代次撤回不被第一代 VOID 拦截
        // 第一代：P0001 入库 50（id=1）已被 VOID（id=2, partnerId=1）
        // 第二代：重确认后新入库 50（id=3），应可正常撤回
        ErpStockLog gen1Origin = purchaseLog(1, "50", "10.0000");
        ErpStockLog gen1Void = voidLog(2, "-50", "10.0000");
        gen1Void.setPartnerId(1L);
        ErpStockLog gen2Origin = purchaseLog(3, "50", "12.0000");

        stubReverse(List.of(gen1Origin, gen2Origin), List.of(gen1Void), List.of(gen1Origin, gen1Void, gen2Origin));
        when(stockMapper.selectCount(any())).thenReturn(1L);
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("50", "12.0000"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("0", "0"));
        when(stockMapper.deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("50"))).thenReturn(1);

        service.reverse("P0001", 200L, PRODUCT_ID, WAREHOUSE_ID, new Date(0), 1);

        verify(stockMapper).deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("50"));
        // 新 VOID 行 partnerId 应指向第二代原始流水 id=3（配对判重）
        ArgumentCaptor<ErpStockLog> logCaptor = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(stockLogMapper).insert(logCaptor.capture());
        assertEquals(3L, logCaptor.getValue().getPartnerId());
    }

    @Test
    void reverse_allGenerationsVoided_rejected() {
        // 全部原始流水都已被 VOID 配对 → 拒绝重复回退
        ErpStockLog gen1Origin = purchaseLog(1, "50", "10.0000");
        ErpStockLog gen2Origin = purchaseLog(3, "50", "12.0000");
        ErpStockLog void1 = voidLog(2, "-50", "10.0000");
        void1.setPartnerId(1L);
        ErpStockLog void2 = voidLog(4, "-50", "12.0000");
        void2.setPartnerId(3L);

        stubReverse(List.of(gen1Origin, gen2Origin), List.of(void1, void2), List.of());
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("0", "0"));

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.reverse("P0001", 200L, PRODUCT_ID, WAREHOUSE_ID, new Date(0), 1));
        assertTrue(ex.getMessage().contains("禁止重复回退"));
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    // ---------- D2 重放 ----------

    @Test
    void replay_pureInOut_avgUnchangedByOutbound() {
        stubForReplay(List.of(
            purchaseLog(1, "100", "10.6666"),
            saleLog(2, "-40", "10.6666")
        ));

        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        assertEquals(0, new BigDecimal("10.6666").compareTo(capturedAvg()));
    }

    @Test
    void replay_multipleInbound_weighted() {
        stubForReplay(List.of(
            purchaseLog(1, "100", "10.0000"),
            purchaseLog(2, "100", "12.0000")
        ));

        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        // (100*10 + 100*12)/200 = 11
        assertEquals(0, new BigDecimal("11.0000").compareTo(capturedAvg()));
    }

    @Test
    void replay_voidOfOutbound_addBackWithoutChangingAvg() {
        stubForReplay(List.of(
            purchaseLog(1, "100", "10.6666"),
            saleLog(2, "-50", "10.6666"),
            voidLog(3, "50", "10.6666")
        ));

        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        assertEquals(0, new BigDecimal("10.6666").compareTo(capturedAvg()));
    }

    @Test
    void replay_voidOfInboundToZero() {
        stubForReplay(List.of(
            purchaseLog(1, "100", "10.6666"),
            voidLog(2, "-100", "10.6666")
        ));

        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        assertEquals(0, BigDecimal.ZERO.compareTo(capturedAvg()));
    }

    @Test
    void replay_voidOfPartialInbound_removesItsCost() {
        stubForReplay(List.of(
            purchaseLog(1, "100", "10.6666"),
            purchaseLog(2, "50", "20.0000"),
            voidLog(3, "-100", "10.6666")
        ));

        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        // 前 100 件均价 13.7777；剔除 100@10.6666 后 (150*13.7777-100*10.6666)/50 = 19.9999
        assertEquals(0, new BigDecimal("19.9999").compareTo(capturedAvg()));
    }

    @Test
    void replay_sortsByIdWithinOpeningFirst() {
        // 写入序（id 升序）重放：id=1 的入库先于 id=2 的入库加权
        ErpStockLog first = purchaseLog(1, "100", "10.0000");
        first.setBizDate(new Date(86400000L));
        ErpStockLog second = purchaseLog(2, "100", "12.0000");
        second.setBizDate(new Date(0));

        // 故意乱序传入，且业务日期倒挂（id 大的日期反而早），按 id 重放结果为 11
        stubForReplay(List.of(second, first));

        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        assertEquals(0, new BigDecimal("11.0000").compareTo(capturedAvg()));
    }

    @Test
    void replay_negativeStock_rejected() {
        stubForReplay(List.of(saleLog(1, "-10", "5.0000")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID));
        assertTrue(ex.getMessage().contains("负库存"));
    }

    @Test
    @DisplayName("出库流水写入时账面必然够扣，先入库（id 小）后出库（id 大）重放不会负")
    void replay_writeOrder_outboundAfterInbound_neverNegative() {
        // 入库 id=1、出库 id=2：写入序重放 100 - 40 = 60，均价 10
        stubForReplay(List.of(
            purchaseLog(1, "100", "10.0000"),
            saleLog(2, "-40", "10.0000")
        ));

        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        assertEquals(0, new BigDecimal("10.0000").compareTo(capturedAvg()));
    }

    @Test
    @DisplayName("客户真实故障场景：出库业务日期早、入库后补且业务日期更晚，写入序重放不再熔断")
    void replay_backdatedInboundAfterEarlierOutbound_replayedByWriteOrder() {
        // 客户先录销售出库（业务日期 11-03），后补采购入库（业务日期 11-05），
        // 旧逻辑按业务日期序重放：出库排到入库前 → 中途负库存熔断「流水重放出现负库存」；
        // 新逻辑按写入序：入库(id=1) 在前 → 50 - 30 = 20，不报错。
        ErpStockLog lateInbound = logRow(1, "PURCHASE", "CG202611050001", "50", "10.0000", new Date(86400000L * 2));
        ErpStockLog earlyOutbound = logRow(2, "SALE", "XS202611030002", "-30", "10.0000", new Date(0));

        stubForReplay(List.of(earlyOutbound, lateInbound));

        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockMapper).updateAvgCost(eq(PRODUCT_ID), eq(WAREHOUSE_ID), captor.capture());
        assertEquals(0, new BigDecimal("10.0000").compareTo(captor.getValue()));
    }

    @Test
    @DisplayName("期初后补录入（id 大于历史单据）时仍须强制排在最前重放，否则重放中途负库存")
    void replay_openingWithLaterBizDate_stillSortedFirst() {
        // 客户先录业务单据（id=1，业务日期早），期初后补录入（id=2，录入当天日期）：
        // 仅按 id 排会把期初排出库之后 → 中途负库存，OPENING 分组规则兜底
        ErpStockLog outbound = logRow(1, "PROD_OUT", "SC1", "-100", "30.0000", new Date(0));
        ErpStockLog opening = logRow(2, "OPENING", "QC1", "120", "30.0000", new Date(86400000L * 30));

        stubForReplay(List.of(outbound, opening));

        // 期初在前 → 120 - 100 = 20，不报负库存
        service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID);

        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockMapper).updateAvgCost(eq(PRODUCT_ID), eq(WAREHOUSE_ID), captor.capture());
        assertEquals(0, new BigDecimal("30.0000").compareTo(captor.getValue()));
    }

    @Test
    void replay_zeroQtyLog_rejected() {
        stubForReplay(List.of(purchaseLog(1, "0", "5.0000")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.replayAvgCost(PRODUCT_ID, WAREHOUSE_ID));
        assertTrue(ex.getMessage().contains("零数量"));
    }

    // ---------- 查询 ----------

    @Test
    void snapshotCost_returnsFirstWarehouseAvg() {
        ErpStock wh2 = stockRow("10", "20.0000");
        wh2.setWarehouseId(2L);
        when(stockMapper.selectList(any())).thenReturn(List.of(stockRow("10", "10.0000"), wh2));

        assertEquals(0, new BigDecimal("10.0000").compareTo(service.snapshotCost(PRODUCT_ID)));
    }

    @Test
    void snapshotCost_noRow_returnsZero() {
        when(stockMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, BigDecimal.ZERO.compareTo(service.snapshotCost(PRODUCT_ID)));
    }

    @Test
    void getQtyAndAvgCost_noRow_returnZero() {
        when(stockMapper.selectOne(any())).thenReturn(null);
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getQty(PRODUCT_ID, WAREHOUSE_ID)));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getAvgCost(PRODUCT_ID, WAREHOUSE_ID)));
    }

    // ---------- B3 往来单位名称 ----------

    @Test
    void listLogs_供应商流水填充供应商名称() {
        ErpStockLog log = purchaseLog(1, "50", "10.0000");
        log.setPartnerType("SUPPLIER");
        log.setPartnerId(8L);
        stubLogs(List.of(log));
        ErpSupplier supplier = new ErpSupplier();
        supplier.setId(8L);
        supplier.setName("钢材供应商");
        when(supplierMapper.selectByIds(any())).thenReturn(List.of(supplier));

        var voList = logs();

        assertEquals(1, voList.size());
        assertEquals("钢材供应商", voList.get(0).getPartnerName());
    }

    @Test
    void listLogs_客户流水填充客户名称() {
        ErpStockLog log = saleLog(2, "-10", "10.0000");
        log.setPartnerType("CUSTOMER");
        log.setPartnerId(9L);
        stubLogs(List.of(log));
        ErpCustomer customer = new ErpCustomer();
        customer.setId(9L);
        customer.setName("恒达设备");
        when(customerMapper.selectByIds(any())).thenReturn(List.of(customer));

        var voList = logs();

        assertEquals(1, voList.size());
        assertEquals("恒达设备", voList.get(0).getPartnerName());
    }

    @Test
    void listLogs_无往来单位时名称为空() {
        ErpStockLog log = purchaseLog(1, "50", "10.0000");
        log.setPartnerType("NONE");
        stubLogs(List.of(log));

        var voList = logs();

        assertNull(voList.get(0).getPartnerName());
    }

    @Test
    void listLogs_批量查询往来单位且只查一次() {
        ErpStockLog l1 = purchaseLog(1, "50", "10.0000");
        l1.setPartnerType("SUPPLIER");
        l1.setPartnerId(8L);
        ErpStockLog l2 = purchaseLog(2, "20", "10.0000");
        l2.setPartnerType("SUPPLIER");
        l2.setPartnerId(8L);
        ErpStockLog l3 = saleLog(3, "-5", "10.0000");
        l3.setPartnerType("CUSTOMER");
        l3.setPartnerId(9L);
        stubLogs(List.of(l1, l2, l3));
        ErpSupplier supplier = new ErpSupplier();
        supplier.setId(8L);
        supplier.setName("钢材供应商");
        when(supplierMapper.selectByIds(any())).thenReturn(List.of(supplier));
        ErpCustomer customer = new ErpCustomer();
        customer.setId(9L);
        customer.setName("恒达设备");
        when(customerMapper.selectByIds(any())).thenReturn(List.of(customer));

        var voList = logs();

        assertEquals("钢材供应商", voList.get(0).getPartnerName());
        assertEquals("钢材供应商", voList.get(1).getPartnerName());
        assertEquals("恒达设备", voList.get(2).getPartnerName());
        // 去重后各一次批量查询
        ArgumentCaptor<java.util.Collection<Long>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(supplierMapper, org.mockito.Mockito.times(1)).selectByIds(captor.capture());
        assertEquals(List.of(8L), List.copyOf(captor.getValue()));
        verify(customerMapper, org.mockito.Mockito.times(1)).selectByIds(any());
    }

    // ---------- B4 单产品库存快照 ----------

    @Test
    void snapshot_返回主仓数量均价与产品信息() {
        ErpStock wh1 = stockRow("30", "10.0000");
        ErpStock wh2 = stockRow("5", "20.0000");
        wh2.setWarehouseId(2L);
        when(stockMapper.selectList(any())).thenReturn(List.of(wh1, wh2));
        ErpProduct p = new ErpProduct();
        p.setId(PRODUCT_ID);
        p.setCode("XH001");
        p.setName("钢管");
        p.setSpec("Φ50");
        p.setUnit("件");
        p.setType("MATERIAL");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(p);

        var vo = service.snapshot(PRODUCT_ID);

        assertEquals(PRODUCT_ID, vo.getProductId());
        assertEquals(WAREHOUSE_ID, vo.getWarehouseId());
        assertEquals(0, new BigDecimal("30").compareTo(vo.getQty()));
        assertEquals(0, new BigDecimal("10.0000").compareTo(vo.getAvgCost()));
        assertEquals(0, new BigDecimal("300.00").compareTo(vo.getStockAmount()));
        assertEquals("XH001", vo.getProductCode());
        assertEquals("钢管", vo.getProductName());
        assertEquals("Φ50", vo.getSpec());
        assertEquals("件", vo.getUnit());
        assertEquals("MATERIAL", vo.getType());
    }

    @Test
    void snapshot_无库存行时数量为零() {
        when(stockMapper.selectList(any())).thenReturn(List.of());

        var vo = service.snapshot(PRODUCT_ID);

        assertEquals(PRODUCT_ID, vo.getProductId());
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getAvgCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getStockAmount()));
    }

    @Test
    void pageStock_databaseFilterAndPage_keepsMetadataAndComputesAmount() {
        ErpStockVo row = new ErpStockVo();
        row.setProductId(PRODUCT_ID);
        row.setWarehouseId(WAREHOUSE_ID);
        row.setProductCode("XH001");
        row.setProductName("钢管");
        row.setType("MATERIAL");
        row.setQty(new BigDecimal("10"));
        row.setAvgCost(new BigDecimal("10.0000"));
        row.setQtyScale(3);
        row.setMinStock(new BigDecimal("20"));
        when(stockMapper.countBalances("%xh001%", "MATERIAL")).thenReturn(201L);
        when(stockMapper.selectBalancePage("%xh001%", "MATERIAL", 100L, 50)).thenReturn(List.of(row));

        var result = service.pageStock(" XH001 ", "MATERIAL", 3, 50);

        assertEquals(201, result.getTotal());
        assertEquals(1, result.getRows().size());
        assertEquals("XH001", result.getRows().get(0).getProductCode());
        assertEquals("钢管", result.getRows().get(0).getProductName());
        assertEquals(3, result.getRows().get(0).getQtyScale());
        assertEquals(new BigDecimal("100.00"), result.getRows().get(0).getStockAmount());
        assertEquals(false, result.getRows().get(0).getCostMissing());
        verify(stockMapper, never()).selectList(any());
        verify(productMapper, never()).selectByIds(any());
    }

    @Test
    void pageStock_outOfRange_doesNotReturnFirstPage() {
        when(stockMapper.countBalances(null, null)).thenReturn(1L);
        var result = service.pageStock(null, null, 2, 100);
        assertEquals(1, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verify(stockMapper, never()).selectBalancePage(any(), any(), org.mockito.ArgumentMatchers.anyLong(), anyInt());
    }

    @Test
    void pageStock_keywordWildcardsAreLiteral_andZeroCostFlagSurvives() {
        ErpStockVo row = new ErpStockVo();
        row.setQty(BigDecimal.TEN);
        row.setAvgCost(BigDecimal.ZERO);
        when(stockMapper.countBalances("%a!%!!!_%", null)).thenReturn(1L);
        when(stockMapper.selectBalancePage("%a!%!!!_%", null, 0, 50)).thenReturn(List.of(row));
        var result = service.pageStock("A%!_", "  ", 1, 50);
        assertTrue(result.getRows().get(0).getCostMissing());
        assertEquals(new BigDecimal("0.00"), result.getRows().get(0).getStockAmount());
    }

    @Test
    void pageStock_invalidPagingTypeAndKeyword_failBeforeQuery() {
        assertThrows(ServiceException.class, () -> service.pageStock(null, null, 0, 50));
        assertThrows(ServiceException.class, () -> service.pageStock(null, null, 1, 0));
        assertThrows(ServiceException.class, () -> service.pageStock(null, null, 1, 101));
        assertThrows(ServiceException.class, () -> service.pageStock(null, "BAD", 1, 50));
        assertThrows(ServiceException.class, () -> service.pageStock("a".repeat(101), null, 1, 50));
        verify(stockMapper, never()).countBalances(any(), any());
    }

    // ---------- 单据级撤回 reverseByBill（DOC/31 §13.4 P0-2） ----------

    private static final long MATERIAL_A = 501L;
    private static final long MATERIAL_B = 502L;
    private static final long FINISHED = 900L;

    @Test
    void reverseByBill_同商品多行_每行各冲一次且只重放一次() {
        // 同一张入库单里同商品两行（100、50）：按行调用 reverse 的第二行会被判「已撤回过」，
        // 单据级撤回必须一次冲完两行，且每个商品只重放一次均价
        ErpStockLog line1 = purchaseLog(1, "100", "10.0000");
        ErpStockLog line2 = purchaseLog(3, "50", "10.0000");
        stubReverse(List.of(line1, line2), List.of(), List.of(line1, line2,
            voidLogOf(PRODUCT_ID, 2, "-100", "10.0000", 1L),
            voidLogOf(PRODUCT_ID, 4, "-50", "10.0000", 3L)));
        when(stockMapper.selectCount(any())).thenReturn(1L);
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("150", "10.0000"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("0", "0"));
        when(stockMapper.deductQty(eq(PRODUCT_ID), eq(WAREHOUSE_ID), any())).thenReturn(1);

        service.reverseByBill("P0001", 200L, new Date(0));

        InOrder order = inOrder(stockMapper);
        order.verify(stockMapper).deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("100"));
        order.verify(stockMapper).deductQty(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("50"));
        // 同商品两行只锁一次、只重放一次
        verify(stockMapper).selectForUpdate(PRODUCT_ID, WAREHOUSE_ID);
        verify(stockMapper, times(1)).updateAvgCost(eq(PRODUCT_ID), eq(WAREHOUSE_ID), any());

        ArgumentCaptor<ErpStockLog> captor = ArgumentCaptor.forClass(ErpStockLog.class);
        verify(stockLogMapper, times(2)).insert(captor.capture());
        assertEquals(0, new BigDecimal("-100").compareTo(captor.getAllValues().get(0).getQty()));
        assertEquals(1L, captor.getAllValues().get(0).getPartnerId());
        assertEquals(0, new BigDecimal("-50").compareTo(captor.getAllValues().get(1).getQty()));
        assertEquals(3L, captor.getAllValues().get(1).getPartnerId());
    }

    @Test
    void reverseByBill_生产单原料与成品_先扣成品再回补原料且按productId升序加锁() {
        // 生产单确认：原料出库（负数 PROD_OUT）+ 成品入库（正数 PROD_IN）。
        // 撤回顺序沿用「先冲销出库流水（原料负数 → 回补）再冲销入库流水（成品正数 → 扣减）」：
        // 先回补原料 5、3，再扣成品 10；锁序固定 product_id 升序（501→502→900），与流水顺序无关。
        // id 模拟真实写入因果序：入库(10/11) < 出库(30/31/32) < VOID(40/41/42)，
        // 写入序重放要求负数流水之前必有其依赖的正数入库流水（出库写入时 deductQty 熔断保证）
        ErpStockLog finished = logRow(FINISHED, 32, "PROD_IN", "SC0001", "10", "30.0000", new Date(0));
        ErpStockLog materialB = logRow(MATERIAL_B, 31, "PROD_OUT", "SC0001", "-3", "7.0000", new Date(0));
        ErpStockLog materialA = logRow(MATERIAL_A, 30, "PROD_OUT", "SC0001", "-5", "10.0000", new Date(0));
        when(stockLogMapper.selectBillStockKeys("SC0001")).thenReturn(List.of(finished, materialB, materialA));
        when(stockLogMapper.selectBillRowsForUpdate("SC0001", null, null))
            .thenReturn(List.of(finished, materialB, materialA));
        when(stockLogMapper.selectReplayRowsForUpdate(MATERIAL_A, WAREHOUSE_ID)).thenReturn(List.of(
            logRow(MATERIAL_A, 10, "PURCHASE", "P0009", "10", "10.0000", new Date(-86400000L)),
            materialA, voidLogOf(MATERIAL_A, 40, "5", "10.0000", 30L)));
        when(stockLogMapper.selectReplayRowsForUpdate(MATERIAL_B, WAREHOUSE_ID)).thenReturn(List.of(
            logRow(MATERIAL_B, 11, "PURCHASE", "P0009", "10", "7.0000", new Date(-86400000L)),
            materialB, voidLogOf(MATERIAL_B, 41, "3", "7.0000", 31L)));
        when(stockLogMapper.selectReplayRowsForUpdate(FINISHED, WAREHOUSE_ID)).thenReturn(List.of(
            finished, voidLogOf(FINISHED, 42, "-10", "30.0000", 32L)));
        when(stockMapper.selectCount(any())).thenReturn(1L);
        when(stockMapper.selectForUpdate(MATERIAL_A, WAREHOUSE_ID)).thenReturn(stockRow("10", "10.0000"));
        when(stockMapper.selectForUpdate(MATERIAL_B, WAREHOUSE_ID)).thenReturn(stockRow("10", "7.0000"));
        when(stockMapper.selectForUpdate(FINISHED, WAREHOUSE_ID)).thenReturn(stockRow("10", "30.0000"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("0", "0"));
        when(stockMapper.deductQty(eq(FINISHED), eq(WAREHOUSE_ID), any())).thenReturn(1);

        service.reverseByBill("SC0001", 300L, new Date(0));

        InOrder order = inOrder(stockMapper);
        order.verify(stockMapper).selectForUpdate(MATERIAL_A, WAREHOUSE_ID);
        order.verify(stockMapper).selectForUpdate(MATERIAL_B, WAREHOUSE_ID);
        order.verify(stockMapper).selectForUpdate(FINISHED, WAREHOUSE_ID);
        // 先冲销出库流水（原料负数 → 回补），再冲销入库流水（成品正数 → 扣减）
        order.verify(stockMapper).addQty(MATERIAL_A, WAREHOUSE_ID, new BigDecimal("5"));
        order.verify(stockMapper).addQty(MATERIAL_B, WAREHOUSE_ID, new BigDecimal("3"));
        order.verify(stockMapper).deductQty(FINISHED, WAREHOUSE_ID, new BigDecimal("10"));

        verify(stockMapper, times(1)).updateAvgCost(eq(MATERIAL_A), eq(WAREHOUSE_ID), any());
        verify(stockMapper, times(1)).updateAvgCost(eq(MATERIAL_B), eq(WAREHOUSE_ID), any());
        verify(stockMapper, times(1)).updateAvgCost(eq(FINISHED), eq(WAREHOUSE_ID), any());
        verify(stockLogMapper, times(3)).insert(any(ErpStockLog.class));
    }

    @Test
    void reverseByBill_已全部冲销时拒绝重复撤回() {
        ErpStockLog origin = purchaseLog(1, "50", "10.0000");
        stubReverse(List.of(origin), List.of(voidLogOf(PRODUCT_ID, 2, "-50", "10.0000", 1L)), List.of());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.reverseByBill("P0001", 200L, new Date(0)));

        assertTrue(ex.getMessage().contains("禁止重复回退"));
        verify(stockLogMapper, never()).insert(any(ErpStockLog.class));
    }

    @Test
    void reverseByBill_无原始流水时抛出未找到() {
        when(stockLogMapper.selectBillRowsForUpdate(any(), any(), any())).thenReturn(List.of());
        when(stockLogMapper.selectBillStockKeys(any())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.reverseByBill("S9999", 300L, new Date(0)));

        assertTrue(ex.getMessage().contains("未找到可回退的库存流水"));
    }

    @Test
    void lockStockRows_打乱输入顺序_总是去重后按productId升序批量加锁() {
        List<Long> first = List.of(3L, 1L, 2L, 1L);
        List<Long> second = List.of(2L, 3L, 1L);

        service.lockStockRows(first);
        service.lockStockRows(second);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(stockMapper, times(2)).selectByProductIdsForUpdate(captor.capture());
        // 两次不同入参得到同一有序列表：[1,2,3]
        assertEquals(List.of(1L, 2L, 3L), captor.getAllValues().get(0));
        assertEquals(List.of(1L, 2L, 3L), captor.getAllValues().get(1));
    }

    @Test
    void lockStockRows_空集合不加锁() {
        service.lockStockRows(null);
        service.lockStockRows(List.of());

        verify(stockMapper, never()).selectByProductIdsForUpdate(any());
    }

    // ---------- 辅助 ----------

    private ErpStock stockRow(String qty, String avgCost) {
        ErpStock row = new ErpStock();
        row.setProductId(PRODUCT_ID);
        row.setWarehouseId(WAREHOUSE_ID);
        row.setQty(new BigDecimal(qty));
        row.setAvgCost(new BigDecimal(avgCost));
        return row;
    }

    private ErpStockLog purchaseLog(int id, String qty, String unitCost) {
        return logRow(id, "PURCHASE", "P0001", qty, unitCost, new Date(0));
    }

    private ErpStockLog saleLog(int id, String qty, String unitCost) {
        return logRow(id, "SALE", "S0001", qty, unitCost, new Date(0));
    }

    private ErpStockLog voidLog(int id, String qty, String unitCost) {
        return logRow(id, "VOID", "P0001", qty, unitCost, new Date(0));
    }

    private ErpStockLog logRow(int id, String bizType, String bizNo, String qty, String unitCost, Date bizDate) {
        ErpStockLog log = new ErpStockLog();
        log.setId((long) id);
        log.setBizType(bizType);
        log.setBizNo(bizNo);
        log.setBizId(999L);
        log.setProductId(PRODUCT_ID);
        log.setWarehouseId(WAREHOUSE_ID);
        log.setQty(new BigDecimal(qty));
        log.setUnitCost(new BigDecimal(unitCost));
        log.setAmount(new BigDecimal(qty).multiply(new BigDecimal(unitCost)));
        log.setPartnerType("NONE");
        log.setBizDate(bizDate);
        log.setSeq(0);
        return log;
    }

    private ErpStockLog logRow(long productId, int id, String bizType, String bizNo, String qty,
                               String unitCost, Date bizDate) {
        ErpStockLog log = logRow(id, bizType, bizNo, qty, unitCost, bizDate);
        log.setProductId(productId);
        return log;
    }

    /**
     * 构造指定商品的 VOID 反向流水，partnerId 指向被冲销的原始流水。
     *
     * @param productId 商品ID
     * @param id        流水ID
     * @param qty       反向数量
     * @param unitCost  成本单价
     * @param originId  被冲销的原始流水ID
     * @return VOID 流水
     */
    private ErpStockLog voidLogOf(long productId, int id, String qty, String unitCost, long originId) {
        ErpStockLog log = logRow(productId, id, "VOID", "P0001", qty, unitCost, new Date(0));
        log.setPartnerId(originId);
        return log;
    }

    private void stubStockRowExists() {
        when(stockMapper.selectCount(any())).thenReturn(1L);
        when(stockMapper.selectForUpdate(PRODUCT_ID, WAREHOUSE_ID)).thenReturn(stockRow("50", "10.0000"));
        when(stockMapper.selectOne(any())).thenReturn(stockRow("97", "10.6666"));
    }

    /**
     * 商品数量精度桩：用于校验 DOC/26 §10 的数量小数位规则。
     *
     * @param qtyScale 商品数量精度位数
     */
    private void stubProduct(int qtyScale) {
        ErpProduct product = new ErpProduct();
        product.setId(PRODUCT_ID);
        product.setName("脱模剂");
        product.setQtyScale(qtyScale);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
    }

    private void stubForReplay(List<ErpStockLog> logs) {
        when(stockLogMapper.selectReplayRowsForUpdate(any(), any())).thenReturn(logs);
        when(stockMapper.selectCount(any())).thenReturn(1L);
    }

    private void stubReverse(List<ErpStockLog> origins, List<ErpStockLog> voids, List<ErpStockLog> replayRows) {
        List<ErpStockLog> rows = new ArrayList<>(origins);
        rows.addAll(voids);
        when(stockLogMapper.selectBillRowsForUpdate(any(), any(), any())).thenReturn(rows);
        when(stockLogMapper.selectBillStockKeys(any())).thenReturn(origins);
        stubForReplay(replayRows);
    }

    private void stubLogs(List<ErpStockLog> logs) {
        List<ErpStockLogVo> rows = logs.stream().map(r -> {
            ErpStockLogVo vo = new ErpStockLogVo();
            vo.setId(r.getId());
            vo.setBizType(r.getBizType());
            vo.setPartnerType(r.getPartnerType());
            vo.setPartnerId(r.getPartnerId());
            vo.setBizDate(LocalDate.of(1970, 1, 1));
            return vo;
        }).toList();
        when(stockLogMapper.selectLogPage(eq(PRODUCT_ID), any(), any(), any(), any(), anyInt())).thenReturn(rows);
    }

    private List<ErpStockLogVo> logs() {
        return service.pageLogs(PRODUCT_ID, LocalDate.of(1970, 1, 1), LocalDate.of(1970, 12, 31), 50, null, null).rows();
    }

    private BigDecimal capturedAvg() {
        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockMapper).updateAvgCost(eq(PRODUCT_ID), eq(WAREHOUSE_ID), captor.capture());
        return captor.getValue();
    }
}
