package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.service.IErpProductService;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpPurchase;
import org.erp.trade.domain.ErpPurchaseItem;
import org.erp.trade.domain.bo.ErpPurchaseBo;
import org.erp.trade.domain.vo.ErpPurchaseVo;
import org.erp.trade.mapper.ErpPurchaseItemMapper;
import org.erp.trade.mapper.ErpPurchaseMapper;
import org.erp.trade.service.IErpOrderProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ErpPurchaseServiceImpl 单元测试
 *
 * 覆盖（DOC/26 §6）：表头发票类型/税率默认值与明细继承、运杂费固定按数量分摊（忽略传入方式）、
 * 尾差补最后一行、每件运杂费与成本单价精度口径、预览不落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class ErpPurchaseServiceImplTest {

    private static final Date BIZ_DATE = new Date(0);
    private static final Date CUTOFF_DATE = java.sql.Date.valueOf("2026-01-01");

    @Mock
    private ErpPurchaseMapper purchaseMapper;

    @Mock
    private org.erp.trade.mapper.ErpPurchaseReturnMapper purchaseReturnMapper;
    @Mock
    private ErpPurchaseItemMapper itemMapper;
    @Mock
    private org.erp.trade.mapper.ErpPurchaseOrderMapper purchaseOrderMapper;
    @Mock
    private org.erp.trade.mapper.ErpPurchaseOrderItemMapper purchaseOrderItemMapper;
    @Mock
    private ErpProductMapper productMapper;
    @Mock
    private ErpSupplierMapper supplierMapper;
    @Mock
    private IErpWarehouseService warehouseService;
    @Mock
    private IInventoryService inventoryService;
    @Mock
    private IErpProductService productService;
    @Mock
    private SequenceService sequenceService;
    @Mock
    private IFinArApService finArApService;
    @Mock
    private IErpOrderProgressService orderProgressService;
    @Mock
    private IFinOpeningGuardService openingGuardService;

    private ErpPurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ErpPurchaseServiceImpl(purchaseMapper, purchaseReturnMapper, itemMapper, purchaseOrderMapper,
            purchaseOrderItemMapper, productMapper, supplierMapper, inventoryService, productService, warehouseService,
            sequenceService, finArApService, orderProgressService, openingGuardService);
        lenient().when(warehouseService.defaultWarehouseId()).thenReturn(1L);
        lenient().when(sequenceService.nextNo(any())).thenReturn("CG202601010001");
        lenient().when(supplierMapper.selectByIds(any())).thenReturn(List.of());
        // insert 末尾回查头部用于组装返回值
        ErpPurchase head = new ErpPurchase();
        head.setId(1L);
        head.setNo("CG202601010001");
        head.setSupplierId(10L);
        head.setBizDate(BIZ_DATE);
        head.setStatus("DRAFT");
        head.setTaxType("VAT_INVOICE");
        head.setTaxRate(BigDecimal.valueOf(13));
        head.setFreightAllocMethod("BY_QTY");
        lenient().when(purchaseMapper.selectById(any())).thenReturn(head);
        // 确认/撤回/作废入口先做单据头锁定读，单测按同一行返回
        lenient().when(purchaseMapper.lockById(any())).thenReturn(head);
        // 状态 CAS（update ... where status=...) 默认命中 1 行
        lenient().when(purchaseMapper.update(any(), any())).thenReturn(1);
        lenient().when(purchaseMapper.insert(any(ErpPurchase.class))).thenAnswer(inv -> {
            ErpPurchase p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        });
    }

    private ErpProduct product(Long id, String code, String name) {
        ErpProduct p = new ErpProduct();
        p.setId(id);
        p.setCode(code);
        p.setName(name);
        p.setUnit("件");
        p.setType("MATERIAL");
        p.setTaxType("NO_TAX");
        return p;
    }

    /**
     * 组装采购单：表头税值可为空（走默认），明细只带数量、单价。
     */
    private ErpPurchaseBo bo(String taxType, BigDecimal taxRate, BigDecimal freightTotal, ErpPurchaseBo.PurchaseItemBo... items) {
        ErpPurchaseBo bo = new ErpPurchaseBo();
        bo.setSupplierId(10L);
        bo.setBizDate(BIZ_DATE);
        bo.setTaxType(taxType);
        bo.setTaxRate(taxRate);
        bo.setFreightAmount(freightTotal);
        bo.setItems(List.of(items));
        return bo;
    }

    private ErpPurchaseBo.PurchaseItemBo item(Long productId, String qty, String price) {
        ErpPurchaseBo.PurchaseItemBo item = new ErpPurchaseBo.PurchaseItemBo();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setPrice(new BigDecimal(price));
        return item;
    }

    private List<ErpPurchaseItem> capturedItems() {
        ArgumentCaptor<ErpPurchaseItem> captor = ArgumentCaptor.forClass(ErpPurchaseItem.class);
        verify(itemMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    private ErpPurchase capturedHead() {
        ArgumentCaptor<ErpPurchase> captor = ArgumentCaptor.forClass(ErpPurchase.class);
        verify(purchaseMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void 表头默认增值税发票_税率13_分摊方式固定按数量() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "YL001", "钢管"));

        service.insert(bo(null, null, BigDecimal.ZERO, item(1L, "100", "28")));

        ErpPurchase head = capturedHead();
        assertEquals("VAT_INVOICE", head.getTaxType());
        assertEquals(0, new BigDecimal("13").compareTo(head.getTaxRate()));
        assertEquals("BY_QTY", head.getFreightAllocMethod());
        assertEquals(0, new BigDecimal("2800.00").compareTo(head.getGoodsAmount()));
        assertEquals(0, new BigDecimal("2800.00").compareTo(head.getTotalAmount()));
        verifyNoInteractions(openingGuardService);
    }

    @Test
    void 运杂费按数量分摊_尾差补最后一行() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "YL001", "钢管"));
        when(productMapper.selectById(2L)).thenReturn(product(2L, "YL002", "角钢"));
        ErpPurchaseBo bo = bo(null, null, new BigDecimal("100"), item(1L, "100", "28"), item(2L, "300", "12"));

        service.insert(bo);

        List<ErpPurchaseItem> items = capturedItems();
        assertEquals(0, new BigDecimal("25.00").compareTo(items.get(0).getFreightAmount()));
        assertEquals(0, new BigDecimal("75.00").compareTo(items.get(1).getFreightAmount()));
        assertEquals(0, new BigDecimal("0.2500").compareTo(items.get(0).getFreightPerUnit()));
        assertEquals(0, new BigDecimal("0.2500").compareTo(items.get(1).getFreightPerUnit()));
    }

    @Test
    void 传入按金额分摊时仍按数量分摊() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "YL001", "钢管"));
        when(productMapper.selectById(2L)).thenReturn(product(2L, "YL002", "角钢"));
        ErpPurchaseBo bo = bo(null, null, new BigDecimal("100"), item(1L, "100", "28"), item(2L, "300", "12"));
        bo.setFreightAllocMethod("BY_AMOUNT");

        service.insert(bo);

        // 按金额分会是 43.75 / 56.25，按数量分才是 25.00 / 75.00
        List<ErpPurchaseItem> items = capturedItems();
        assertEquals(0, new BigDecimal("25.00").compareTo(items.get(0).getFreightAmount()));
        assertEquals(0, new BigDecimal("75.00").compareTo(items.get(1).getFreightAmount()));
    }

    @Test
    void 明细继承表头发票类型与税率并计入成本() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "YL001", "钢管"));

        service.insert(bo("VAT_INVOICE", new BigDecimal("13"), BigDecimal.ZERO, item(1L, "100", "28")));

        ErpPurchaseItem row = capturedItems().get(0);
        assertEquals("VAT_INVOICE", row.getTaxType());
        assertEquals(0, new BigDecimal("13").compareTo(row.getTaxRate()));
        // 28 / 1.13 = 24.778761 → 4 位 24.7788
        assertEquals(0, new BigDecimal("24.7788").compareTo(row.getCostPrice()));
    }

    @Test
    void 不含税表头不做含税还原() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "YL001", "钢管"));

        service.insert(bo("NO_TAX", new BigDecimal("13"), BigDecimal.ZERO, item(1L, "100", "28")));

        ErpPurchaseItem row = capturedItems().get(0);
        assertEquals("NO_TAX", row.getTaxType());
        assertEquals(0, new BigDecimal("28.0000").compareTo(row.getCostPrice()));
    }

    @Test
    void 运杂费为零时行分摊与成本单价不含运杂费() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "YL001", "钢管"));

        service.insert(bo("NO_TAX", BigDecimal.ZERO, BigDecimal.ZERO, item(1L, "100", "28")));

        ErpPurchaseItem row = capturedItems().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getFreightAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getFreightPerUnit()));
        assertEquals(0, new BigDecimal("28.0000").compareTo(row.getCostPrice()));
    }

    @Test
    void 预览按数量分摊且不落库() {
        when(productMapper.selectByIds(any())).thenReturn(List.of(
            product(1L, "YL001", "钢管"), product(2L, "YL002", "角钢")));

        var result = service.previewAllocation(
            bo("VAT_INVOICE", new BigDecimal("13"), new BigDecimal("100"), item(1L, "100", "28"), item(2L, "300", "12")));

        assertEquals(2, result.size());
        assertEquals("VAT_INVOICE", result.get(0).getTaxType());
        assertEquals(0, new BigDecimal("25.00").compareTo(result.get(0).getFreightAmount()));
        assertEquals(0, new BigDecimal("75.00").compareTo(result.get(1).getFreightAmount()));
        // 24.778761 + 0.25 = 25.028761 → 4 位 25.0288
        assertEquals(0, new BigDecimal("25.0288").compareTo(result.get(0).getCostPrice()));
        verify(itemMapper, never()).insert(any(ErpPurchaseItem.class));
    }

    @Test
    void 预览对重复产品ID仅做一次去重批量查询() {
        when(productMapper.selectByIds(any())).thenReturn(List.of(product(1L, "YL001", "钢管"),
            product(2L, "YL002", "角钢")));

        var result = service.previewAllocation(bo("NO_TAX", BigDecimal.ZERO, BigDecimal.ZERO,
            item(1L, "1", "10"), item(2L, "2", "20"), item(1L, "3", "30")));

        assertEquals(3, result.size());
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(productMapper).selectByIds(ids.capture());
        assertEquals(List.of(1L, 2L), List.copyOf(ids.getValue()));
        verify(productMapper, never()).selectById(any());
    }

    @Test
    void 预览空批次不发产品查询且缺失产品仍拒绝() {
        var empty = service.previewAllocation(bo("NO_TAX", BigDecimal.ZERO, BigDecimal.ZERO));
        assertTrue(empty.isEmpty());
        verify(productMapper, never()).selectByIds(any());

        ErpPurchaseBo missing = bo("NO_TAX", BigDecimal.ZERO, BigDecimal.ZERO, item(99L, "1", "10"));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.previewAllocation(missing));
        assertEquals("产品不存在：99", ex.getMessage());
    }

    @Test
    void 预览数量为0时拒绝() {
        when(productMapper.selectByIds(any())).thenReturn(List.of(product(1L, "YL001", "钢管")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.previewAllocation(bo(null, null, new BigDecimal("100"), item(1L, "0", "28"))));

        assertEquals("数量必须大于0", ex.getMessage());
    }

    @Test
    void 编辑草稿时表头税值与运杂费一并保存() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "YL001", "钢管"));

        ErpPurchaseVo result = service.update(1L, bo("NO_TAX", new BigDecimal("3"), new BigDecimal("50"), item(1L, "10", "2")));

        assertEquals("NO_TAX", result.getTaxType());
        assertEquals(0, new BigDecimal("50").compareTo(result.getFreightAmount()));
        assertEquals("BY_QTY", result.getFreightAllocMethod());
        assertEquals(0, new BigDecimal("20.00").compareTo(result.getGoodsAmount()));
        assertEquals(0, new BigDecimal("70.00").compareTo(result.getTotalAmount()));
        verifyNoInteractions(openingGuardService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "insert", "update"})
    void openingCutoffStopsPurchaseConfirmationBeforeStockOrderAndFinanceWrites(String entry) {
        ErpPurchase persisted = new ErpPurchase();
        persisted.setId(1L);
        persisted.setSupplierId(10L);
        persisted.setBizDate(CUTOFF_DATE);
        persisted.setStatus("DRAFT");
        when(purchaseMapper.lockById(1L)).thenReturn(persisted);
        ErpPurchaseItem row = new ErpPurchaseItem();
        row.setProductId(1L);
        row.setQty(BigDecimal.ONE);
        row.setCostPrice(BigDecimal.TEN);
        when(itemMapper.selectList(any())).thenReturn(List.of(row));
        when(productMapper.selectById(1L)).thenReturn(product(1L, "YL001", "钢管"));
        ErpPurchaseBo request = bo("NO_TAX", BigDecimal.ZERO, BigDecimal.ZERO, item(1L, "1", "10"));
        request.setConfirm(true);
        // 模拟 DATE 列去除时分秒；确认必须取锁定后的表头，不直接用 BO。
        request.setBizDate(new Date(CUTOFF_DATE.getTime() + 1_000));
        ServiceException denial = new ServiceException("请先撤回期初", 40902);
        doThrow(denial).when(openingGuardService).lockAndCheckBusinessDate("AP", 10L, CUTOFF_DATE);

        assertSame(denial, assertThrows(ServiceException.class, () -> {
            switch (entry) {
                case "confirm" -> service.confirm(1L);
                case "insert" -> service.insert(request);
                case "update" -> service.update(1L, request);
                default -> throw new AssertionError(entry);
            }
        }));

        verify(openingGuardService).lockAndCheckBusinessDate("AP", 10L, CUTOFF_DATE);
        verifyNoInteractions(inventoryService, productService, finArApService);
        verify(orderProgressService, never()).recalc(any(), any(), any());
        verify(purchaseMapper, never()).update(isNull(), any());
    }

    @Test
    void purchaseChecksOpeningAfterOrderLocksAndContinuesWhenAllowed() {
        ErpPurchase head = purchaseMapper.selectById(1L);
        head.setBizDate(java.sql.Date.valueOf("2026-01-02"));
        head.setSourceOrderId(20L);
        head.setTotalAmount(BigDecimal.TEN);
        ErpPurchaseItem row = new ErpPurchaseItem();
        row.setSourceOrderItemId(100L);
        row.setProductId(1L);
        row.setQty(BigDecimal.ONE);
        row.setCostPrice(BigDecimal.TEN);
        when(itemMapper.selectList(any())).thenReturn(List.of(row));

        service.confirm(1L);

        InOrder order = inOrder(purchaseMapper, orderProgressService, openingGuardService, inventoryService, finArApService);
        order.verify(purchaseMapper, atLeastOnce()).lockById(1L);
        order.verify(orderProgressService).lockAndValidate(IErpOrderProgressService.TYPE_PURCHASE, 20L,
            List.of(new IErpOrderProgressService.ExecLine(100L, BigDecimal.ONE)));
        order.verify(openingGuardService).lockAndCheckBusinessDate("AP", 10L, head.getBizDate());
        order.verify(inventoryService).lockStockRows(List.of(1L));
        order.verify(inventoryService).inbound("PURCHASE", head.getNo(), 1L, 1L, 1L,
            BigDecimal.ONE, BigDecimal.TEN, "SUPPLIER", 10L, head.getBizDate(), 0);
        order.verify(orderProgressService).recalc(IErpOrderProgressService.TYPE_PURCHASE, 20L, null);
        order.verify(finArApService).createByPurchase(1L, head.getNo(), 10L, BigDecimal.TEN, head.getBizDate());
    }

    // ---------- 往来单位名称下沉（B3） ----------

    @Test
    void 单据详情填充供应商名称() {
        ErpSupplier supplier = new ErpSupplier();
        supplier.setId(10L);
        supplier.setName("华东钢材供应商");
        when(supplierMapper.selectByIds(any())).thenReturn(List.of(supplier));

        var vo = service.selectById(1L);

        assertEquals(10L, vo.getSupplierId());
        assertEquals("华东钢材供应商", vo.getSupplierName());
        assertEquals("VAT_INVOICE", vo.getTaxType());
    }

    @Test
    void 供应商缺失时名称为空() {
        when(supplierMapper.selectByIds(any())).thenReturn(List.of());

        var vo = service.selectById(1L);

        assertEquals(10L, vo.getSupplierId());
        assertNull(vo.getSupplierName());
    }

    @Test
    void 列表批量查询供应商名称且只查一次() {
        ErpPurchase p1 = new ErpPurchase();
        p1.setId(1L);
        p1.setNo("CG001");
        p1.setSupplierId(10L);
        ErpPurchase p2 = new ErpPurchase();
        p2.setId(2L);
        p2.setNo("CG002");
        p2.setSupplierId(10L);
        ErpPurchase p3 = new ErpPurchase();
        p3.setId(3L);
        p3.setNo("CG003");
        p3.setSupplierId(20L);
        when(purchaseMapper.selectPage(any(), any())).thenReturn(pageOf(p1, p2, p3));
        ErpSupplier s1 = new ErpSupplier();
        s1.setId(10L);
        s1.setName("供应商甲");
        ErpSupplier s2 = new ErpSupplier();
        s2.setId(20L);
        s2.setName("供应商乙");
        when(supplierMapper.selectByIds(any())).thenReturn(List.of(s1, s2));

        var result = service.selectPage(null, null, null, pageQuery());

        assertEquals(3, result.getRows().size());
        assertEquals("供应商甲", result.getRows().get(0).getSupplierName());
        assertEquals("供应商甲", result.getRows().get(1).getSupplierName());
        assertEquals("供应商乙", result.getRows().get(2).getSupplierName());
        // 去重后只做一次批量查询，避免 N+1
        ArgumentCaptor<java.util.Collection<Long>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(supplierMapper, org.mockito.Mockito.times(1)).selectByIds(captor.capture());
        assertEquals(List.of(10L, 20L), List.copyOf(captor.getValue()));
    }

    @Test
    void 草稿单作废被拒并提示走删除() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.voidBill(1L));

        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("删除"));
    }

    @Test
    void 删除草稿单同时删明细与主表() {
        service.deleteDraft(1L);

        verify(itemMapper).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(purchaseMapper).deleteById(1L);
    }

    @Test
    void 删除已确认单被拒40902() {
        ErpPurchase head = purchaseMapper.selectById(1L);
        head.setStatus("CONFIRMED");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(1L));

        assertEquals(40902, ex.getCode());
        verify(purchaseMapper, never()).deleteById(any(Long.class));
    }

    // ---------- 辅助 ----------

    private PageQuery pageQuery() {
        return new PageQuery(20, 1);
    }
    private Page<ErpPurchase> pageOf(ErpPurchase... rows) {
        Page<ErpPurchase> page = new Page<>(1, 20, rows.length);
        page.setRecords(List.of(rows));
        return page;
    }
}
