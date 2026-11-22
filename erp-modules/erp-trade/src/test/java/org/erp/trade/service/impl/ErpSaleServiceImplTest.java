package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpProductService;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpSale;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleMapper;
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
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ErpSaleServiceImpl 单元测试（B3 往来单位名称下沉）
 *
 * 覆盖：单据详情/列表填充客户名称、供应商缺失时名称为空、列表批量查询只查一次（防 N+1）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class ErpSaleServiceImplTest {

    private static final Date CUTOFF_DATE = java.sql.Date.valueOf("2026-01-01");

    @Mock
    private ErpSaleMapper saleMapper;

    @Mock
    private org.erp.trade.mapper.ErpSaleReturnMapper saleReturnMapper;
    @Mock
    private ErpSaleItemMapper itemMapper;
    @Mock
    private org.erp.trade.mapper.ErpSaleOrderMapper saleOrderMapper;
    @Mock
    private org.erp.trade.mapper.ErpSaleOrderItemMapper saleOrderItemMapper;
    @Mock
    private ErpProductMapper productMapper;
    @Mock
    private IErpProductService productService;
    @Mock
    private ErpCustomerMapper customerMapper;
    @Mock
    private IErpWarehouseService warehouseService;
    @Mock
    private IInventoryService inventoryService;
    @Mock
    private SequenceService sequenceService;
    @Mock
    private IFinArApService finArApService;
    @Mock
    private IErpOrderProgressService orderProgressService;
    @Mock
    private IFinOpeningGuardService openingGuardService;

    private ErpSaleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ErpSaleServiceImpl(saleMapper, saleReturnMapper, itemMapper, saleOrderMapper, saleOrderItemMapper,
            productMapper, productService, customerMapper, inventoryService, warehouseService, sequenceService, finArApService,
            orderProgressService, openingGuardService);
        when(warehouseService.defaultWarehouseId()).thenReturn(1L);
        when(customerMapper.selectByIds(any())).thenReturn(List.of());
        when(itemMapper.selectList(any())).thenReturn(List.of());
        ErpSale head = new ErpSale();
        head.setId(1L);
        head.setNo("XS202601010001");
        head.setCustomerId(10L);
        head.setStatus("DRAFT");
        when(saleMapper.selectById(1L)).thenReturn(head);
        // 确认/撤回/作废入口先做单据头锁定读，单测按同一行返回
        when(saleMapper.lockById(any())).thenReturn(head);
        // 状态 CAS（update ... where status=...) 默认命中 1 行
        when(saleMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void 单据详情填充客户名称() {
        ErpCustomer customer = new ErpCustomer();
        customer.setId(10L);
        customer.setName("恒达设备");
        when(customerMapper.selectByIds(any())).thenReturn(List.of(customer));

        var vo = service.selectById(1L);

        assertEquals(10L, vo.getCustomerId());
        assertEquals("恒达设备", vo.getCustomerName());
    }

    @Test
    void 客户缺失时名称为空() {
        when(customerMapper.selectByIds(any())).thenReturn(List.of());

        var vo = service.selectById(1L);

        assertEquals(10L, vo.getCustomerId());
        assertNull(vo.getCustomerName());
    }

    @Test
    void 列表批量查询客户名称且只查一次() {
        ErpSale s1 = new ErpSale();
        s1.setId(1L);
        s1.setNo("XS001");
        s1.setCustomerId(10L);
        ErpSale s2 = new ErpSale();
        s2.setId(2L);
        s2.setNo("XS002");
        s2.setCustomerId(10L);
        ErpSale s3 = new ErpSale();
        s3.setId(3L);
        s3.setNo("XS003");
        s3.setCustomerId(20L);
        when(saleMapper.selectPage(any(), any())).thenReturn(pageOf(s1, s2, s3));
        ErpCustomer c1 = new ErpCustomer();
        c1.setId(10L);
        c1.setName("客户甲");
        ErpCustomer c2 = new ErpCustomer();
        c2.setId(20L);
        c2.setName("客户乙");
        when(customerMapper.selectByIds(any())).thenReturn(List.of(c1, c2));

        var result = service.selectPage(null, null, null, new PageQuery(20, 1));

        assertEquals(3, result.getRows().size());
        assertEquals("客户甲", result.getRows().get(0).getCustomerName());
        assertEquals("客户甲", result.getRows().get(1).getCustomerName());
        assertEquals("客户乙", result.getRows().get(2).getCustomerName());
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(customerMapper, times(1)).selectByIds(captor.capture());
        assertEquals(List.of(10L, 20L), List.copyOf(captor.getValue()));
    }

    @Test
    void 客户名称不参与销售金额口径() {
        ErpCustomer customer = new ErpCustomer();
        customer.setId(10L);
        customer.setName("恒达设备");
        when(customerMapper.selectByIds(any())).thenReturn(List.of(customer));
        ErpSale head = new ErpSale();
        head.setId(1L);
        head.setNo("XS001");
        head.setCustomerId(10L);
        // 已确认单据：成本/毛利/毛利率按落库快照原样回显，不受预估口径影响
        head.setStatus("CONFIRMED");
        head.setTotalAmount(new BigDecimal("1000.00"));
        head.setCostAmount(new BigDecimal("800.00"));
        head.setGrossProfit(new BigDecimal("200.00"));
        head.setGrossMargin(new BigDecimal("20.00"));
        when(saleMapper.selectById(1L)).thenReturn(head);

        var vo = service.selectById(1L);

        assertEquals(0, new BigDecimal("1000.00").compareTo(vo.getTotalAmount()));
        assertEquals(0, new BigDecimal("200.00").compareTo(vo.getGrossProfit()));
        assertEquals(0, new BigDecimal("20.00").compareTo(vo.getGrossMargin()));
        assertEquals("恒达设备", vo.getCustomerName());
    }

    @Test
    void 草稿单按库存均价与运杂费分摊预估成本() {
        ErpSale head = saleMapper.selectById(1L);
        head.setStatus("DRAFT");
        head.setTotalAmount(new BigDecimal("1000.00"));
        head.setFreightAmount(new BigDecimal("100.00"));
        org.erp.trade.domain.ErpSaleItem row = new org.erp.trade.domain.ErpSaleItem();
        row.setProductId(301L);
        row.setQty(new BigDecimal("10"));
        row.setAmount(new BigDecimal("1000.00"));
        when(itemMapper.selectList(any())).thenReturn(List.of(row));
        when(inventoryService.snapshotCostBatch(List.of(301L)))
            .thenReturn(Map.of(301L, new BigDecimal("50.0000")));

        var vo = service.selectById(1L);

        // 成本 = 10×(50 + 100/10) = 600，毛利 = 1000 - 600 = 400，毛利率 40%
        assertEquals(0, new BigDecimal("600.00").compareTo(vo.getCostAmount()));
        assertEquals(0, new BigDecimal("400.00").compareTo(vo.getGrossProfit()));
        assertEquals(0, new BigDecimal("40.00").compareTo(vo.getGrossMargin()));
        // 预估只回显不落库
        verify(saleMapper, never()).updateById(any(ErpSale.class));
    }

    @Test
    void 草稿空明细预估成本为0不触发分摊() {
        ErpSale head = saleMapper.selectById(1L);
        head.setStatus("DRAFT");
        head.setTotalAmount(BigDecimal.ZERO);
        when(itemMapper.selectList(any())).thenReturn(List.of());

        var vo = service.selectById(1L);

        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getCostAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getGrossMargin()));
    }

    @Test
    void 确认时成本为0的商品名随结果返回() {
        org.erp.trade.domain.ErpSaleItem item = new org.erp.trade.domain.ErpSaleItem();
        item.setProductId(301L);
        item.setQty(new BigDecimal("2"));
        item.setAmount(new BigDecimal("100.00"));
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        stubOutboundCost(301L, "0");
        org.erp.master.domain.ErpProduct product = new org.erp.master.domain.ErpProduct();
        product.setId(301L);
        product.setName("铝板");
        when(productMapper.selectById(301L)).thenReturn(product);

        var vo = service.confirm(1L);

        // 成本锚点缺失只提示不阻断：确认照常完成，商品名随 costWarnings 回传
        assertEquals(List.of("铝板"), vo.getCostWarnings());
        assertEquals("CONFIRMED", vo.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "insert", "update"})
    void openingCutoffStopsSaleConfirmationBeforeStockOrderAndFinanceWrites(String entry) {
        ErpSale persisted = new ErpSale();
        persisted.setId(1L);
        persisted.setCustomerId(10L);
        persisted.setBizDate(CUTOFF_DATE);
        persisted.setStatus("DRAFT");
        when(saleMapper.lockById(1L)).thenReturn(persisted);
        ErpSaleItem row = saleItem(0L, "1", "100.00");
        row.setNewProductSnapshot(snapshot("临时法兰", "1"));
        when(itemMapper.selectList(any())).thenReturn(List.of(row));

        var bo = quickBo(quickItem("临时法兰", "1", null));
        bo.setConfirm(true);
        // 模拟 DATE 列去除时分秒；确认必须取锁定后的表头，不直接用 BO。
        bo.setBizDate(new Date(CUTOFF_DATE.getTime() + 1_000));
        if ("insert".equals(entry)) {
            doAnswer(inv -> {
                inv.<ErpSale>getArgument(0).setId(1L);
                return 1;
            }).when(saleMapper).insert(any(ErpSale.class));
        }
        ServiceException denial = new ServiceException("请先撤回期初", 40902);
        doThrow(denial).when(openingGuardService).lockAndCheckBusinessDate("AR", 10L, CUTOFF_DATE);

        assertSame(denial, assertThrows(ServiceException.class, () -> {
            switch (entry) {
                case "confirm" -> service.confirm(1L);
                case "insert" -> service.insert(bo);
                case "update" -> service.update(1L, bo);
                default -> throw new AssertionError(entry);
            }
        }));

        verify(openingGuardService).lockAndCheckBusinessDate("AR", 10L, CUTOFF_DATE);
        verifyNoInteractions(inventoryService, productService, finArApService);
        verify(orderProgressService, never()).recalc(any(), any(), any());
        verify(saleMapper, never()).update(isNull(), any());
    }

    private void stubOutboundCost(long productId, String cost) {
        when(inventoryService.outbound(eq("SALE"), any(), any(), eq(productId), any(), any(),
            any(), any(), any(), anyInt())).thenReturn(new BigDecimal(cost));
    }

    private Page<ErpSale> pageOf(ErpSale... rows) {
        Page<ErpSale> page = new Page<>(1, 20, rows.length);
        page.setRecords(List.of(rows));
        return page;
    }

    // ---------- 表头化：发票类型 / 税率 / 运杂费（DOC/26 §6） ----------

    private org.erp.master.domain.ErpProduct product(Long id, String name, String taxType) {
        org.erp.master.domain.ErpProduct p = new org.erp.master.domain.ErpProduct();
        p.setId(id);
        p.setName(name);
        p.setUnit("件");
        p.setType("PRODUCT");
        p.setTaxType(taxType);
        return p;
    }

    private org.erp.trade.domain.bo.ErpSaleBo saleBo(String taxType, BigDecimal taxRate, BigDecimal freight, BigDecimal qty,
                                                    BigDecimal price, Long productId) {
        org.erp.trade.domain.bo.ErpSaleBo bo = new org.erp.trade.domain.bo.ErpSaleBo();
        bo.setCustomerId(10L);
        bo.setTaxType(taxType);
        bo.setTaxRate(taxRate);
        bo.setFreightAmount(freight);
        org.erp.trade.domain.bo.ErpSaleBo.SaleItemBo item = new org.erp.trade.domain.bo.ErpSaleBo.SaleItemBo();
        item.setProductId(productId);
        item.setQty(qty);
        item.setPrice(price);
        bo.setItems(List.of(item));
        return bo;
    }

    private ErpSaleItem saleItem(Long productId, String qty, String amount) {
        ErpSaleItem item = new ErpSaleItem();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setAmount(new BigDecimal(amount));
        return item;
    }

    @Test
    void 表头默认增值税发票_税率13_分摊方式按数量且明细继承() {
        when(productMapper.selectById(301L)).thenReturn(product(301L, "铝板", "VAT_INVOICE"));

        service.update(1L, saleBo(null, null, new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("100"), 301L));

        ArgumentCaptor<ErpSaleItem> itemCaptor = ArgumentCaptor.forClass(ErpSaleItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertEquals("VAT_INVOICE", itemCaptor.getValue().getTaxType());
        assertEquals(0, new BigDecimal("13").compareTo(itemCaptor.getValue().getTaxRate()));

        ArgumentCaptor<ErpSale> headCaptor = ArgumentCaptor.forClass(ErpSale.class);
        verify(saleMapper, org.mockito.Mockito.atLeastOnce()).updateById(headCaptor.capture());
        ErpSale saved = headCaptor.getValue();
        assertEquals("VAT_INVOICE", saved.getTaxType());
        assertEquals(0, new BigDecimal("13").compareTo(saved.getTaxRate()));
        assertEquals("BY_QTY", saved.getFreightAllocMethod());
        assertEquals(0, new BigDecimal("1000.00").compareTo(saved.getGoodsAmount()));
    }

    @Test
    void 发票类型不合法拒绝() {
        assertThrows(ServiceException.class, () -> service.update(1L,
            saleBo("BAD_TAX_TYPE", null, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 301L)));
    }

    @Test
    void 税率超出范围拒绝() {
        assertThrows(ServiceException.class, () -> service.update(1L,
            saleBo(null, new BigDecimal("101"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 301L)));
    }

    @Test
    void 运杂费为负拒绝() {
        assertThrows(ServiceException.class, () -> service.update(1L,
            saleBo(null, null, new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ONE, 301L)));
    }

    @Test
    void 现录现卖名称为空白拒绝() {
        assertThrows(ServiceException.class, () -> service.insert(quickBo(quickItem("   ", "1", "1"))));
    }

    @Test
    void 明细税值取表头而非产品资料() {        when(productMapper.selectById(301L)).thenReturn(product(301L, "铝板", "VAT_INVOICE"));

        service.update(1L, saleBo("NO_TAX", new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("2"), new BigDecimal("50"), 301L));

        ArgumentCaptor<ErpSaleItem> itemCaptor = ArgumentCaptor.forClass(ErpSaleItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertEquals("NO_TAX", itemCaptor.getValue().getTaxType());
        assertEquals(0, new BigDecimal("3").compareTo(itemCaptor.getValue().getTaxRate()));
    }

    @Test
    void 确认时按数量分摊运杂费并计入成本() {
        ErpSaleItem a = saleItem(301L, "10", "1000.00");
        a.setSourceOrderItemId(100L);
        ErpSaleItem b = saleItem(302L, "30", "900.00");
        b.setSourceOrderItemId(101L);
        when(itemMapper.selectList(any())).thenReturn(List.of(a, b));
        stubOutboundCost(301L, "20.0000");
        stubOutboundCost(302L, "10.0000");
        ErpSale head = saleMapper.selectById(1L);
        head.setTotalAmount(new BigDecimal("1900.00"));
        head.setFreightAmount(new BigDecimal("100"));
        head.setSourceOrderId(20L);
        head.setBizDate(java.sql.Date.valueOf("2026-01-02"));

        var vo = service.confirm(1L);
        InOrder order = inOrder(saleMapper, orderProgressService, openingGuardService, inventoryService, finArApService);
        order.verify(saleMapper, times(2)).lockById(1L);
        order.verify(orderProgressService).lockAndValidate(IErpOrderProgressService.TYPE_SALE, 20L,
            List.of(new IErpOrderProgressService.ExecLine(100L, a.getQty()),
                new IErpOrderProgressService.ExecLine(101L, b.getQty())));
        order.verify(openingGuardService).lockAndCheckBusinessDate("AR", 10L, head.getBizDate());
        order.verify(inventoryService).lockStockRows(List.of(301L, 302L));
        order.verify(orderProgressService).recalc(IErpOrderProgressService.TYPE_SALE, 20L, null);
        order.verify(finArApService).createBySale(1L, head.getNo(), 10L, head.getTotalAmount(), head.getBizDate());
        verify(inventoryService, never()).snapshotCost(any());
        verify(inventoryService, times(2)).outbound(eq("SALE"), any(), eq(1L), any(), eq(1L),
            any(), eq("CUSTOMER"), eq(10L), any(), anyInt());

        // 每件运杂费 = 100 / 40 = 2.5000，成本单价 = 库存均价 + 每件运杂费
        assertEquals(0, new BigDecimal("22.5000").compareTo(a.getUnitCost()));
        assertEquals(0, new BigDecimal("225.00").compareTo(a.getCostAmount()));
        assertEquals(0, new BigDecimal("12.5000").compareTo(b.getUnitCost()));
        assertEquals(0, new BigDecimal("375.00").compareTo(b.getCostAmount()));
        assertEquals(0, new BigDecimal("600.00").compareTo(vo.getCostAmount()));
        assertEquals(0, new BigDecimal("1300.00").compareTo(vo.getGrossProfit()));
        // 1300 / 1900 × 100 = 68.42
        assertEquals(0, new BigDecimal("68.42").compareTo(vo.getGrossMargin()));
    }

    @Test
    void 运杂费按数量分摊_尾差补最后一行() {
        ErpSaleItem a = saleItem(301L, "1", "100.00");
        ErpSaleItem b = saleItem(302L, "1", "100.00");
        ErpSaleItem c = saleItem(303L, "1", "100.00");
        when(itemMapper.selectList(any())).thenReturn(List.of(a, b, c));
        stubOutboundCost(301L, "10.0000");
        stubOutboundCost(302L, "10.0000");
        stubOutboundCost(303L, "10.0000");
        ErpSale head = saleMapper.selectById(1L);
        head.setTotalAmount(new BigDecimal("300.00"));
        head.setFreightAmount(new BigDecimal("100"));

        var vo = service.confirm(1L);

        // 100 / 3 = 33.33：前两行 33.33，尾差 33.34 入最后一行，Σ行分摊 = 总额
        assertEquals(0, new BigDecimal("43.33").compareTo(a.getUnitCost()));
        assertEquals(0, new BigDecimal("43.33").compareTo(b.getUnitCost()));
        assertEquals(0, new BigDecimal("43.34").compareTo(c.getUnitCost()));
        assertEquals(0, new BigDecimal("130.00").compareTo(vo.getCostAmount()));
    }

    @Test
    void 运杂费为零时成本不含运杂费() {
        ErpSaleItem a = saleItem(301L, "10", "1000.00");
        when(itemMapper.selectList(any())).thenReturn(List.of(a));
        stubOutboundCost(301L, "20.0000");
        ErpSale head = saleMapper.selectById(1L);
        head.setTotalAmount(new BigDecimal("1000.00"));
        head.setFreightAmount(BigDecimal.ZERO);

        var vo = service.confirm(1L);

        assertEquals(0, new BigDecimal("20.0000").compareTo(a.getUnitCost()));
        assertEquals(0, new BigDecimal("200.00").compareTo(vo.getCostAmount()));
    }

    // ---------- 现录现卖（DOC/26 §8：草稿只存快照，确认时建品+入库+出库） ----------

    /** 现录现卖明细：不带 productId，只带商品快照 */
    private org.erp.trade.domain.bo.ErpSaleBo.SaleItemBo quickItem(String name, String saleQty, String inQty) {
        org.erp.trade.domain.bo.ErpSaleBo.SaleItemBo item = new org.erp.trade.domain.bo.ErpSaleBo.SaleItemBo();
        item.setQty(new BigDecimal(saleQty));
        item.setPrice(new BigDecimal("100"));
        org.erp.trade.domain.bo.ErpSaleBo.NewProduct np = new org.erp.trade.domain.bo.ErpSaleBo.NewProduct();
        np.setName(name);
        np.setCostPrice(new BigDecimal("8"));
        np.setQty(inQty == null ? null : new BigDecimal(inQty));
        item.setNewProduct(np);
        return item;
    }

    private org.erp.trade.domain.bo.ErpSaleBo quickBo(org.erp.trade.domain.bo.ErpSaleBo.SaleItemBo... items) {
        org.erp.trade.domain.bo.ErpSaleBo bo = new org.erp.trade.domain.bo.ErpSaleBo();
        bo.setCustomerId(10L);
        bo.setBizDate(new java.util.Date());
        bo.setItems(List.of(items));
        return bo;
    }

    /** 模拟库中已有的 new_product_snapshot 列值 */
    private String snapshot(String name, String inQty) {
        org.erp.trade.domain.bo.ErpSaleBo.NewProduct np = new org.erp.trade.domain.bo.ErpSaleBo.NewProduct();
        np.setName(name);
        np.setCostPrice(new BigDecimal("8"));
        np.setQty(new BigDecimal(inQty));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(np);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 新建草稿现录现卖只写快照不建商品不动库存() {
        doAnswer(inv -> {
            ((ErpSale) inv.getArgument(0)).setId(5L);
            return 1;
        }).when(saleMapper).insert(any(ErpSale.class));
        ErpSale saved = new ErpSale();
        saved.setId(5L);
        saved.setStatus("DRAFT");
        saved.setNo("XS202611020001");
        when(saleMapper.selectById(5L)).thenReturn(saved);
        when(sequenceService.nextNo("XS")).thenReturn("XS202611020001");

        service.insert(quickBo(quickItem("临时法兰", "10", null)));

        ArgumentCaptor<ErpSaleItem> captor = ArgumentCaptor.forClass(ErpSaleItem.class);
        verify(itemMapper).insert(captor.capture());
        ErpSaleItem row = captor.getValue();
        assertEquals(0L, row.getProductId());
        assertTrue(row.getNewProductSnapshot().contains("临时法兰"));
        // 未填入库数量时默认等于销售数量
        assertTrue(row.getNewProductSnapshot().contains("\"qty\":10"));
        verify(productMapper, never()).insert(any(org.erp.master.domain.ErpProduct.class));
        verify(inventoryService, never()).inbound(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
        verifyNoInteractions(openingGuardService);
    }

    @Test
    void 现录现卖草稿反复保存不重复建品() {
        service.update(1L, quickBo(quickItem("临时法兰", "10", null)));
        service.update(1L, quickBo(quickItem("临时法兰", "10", "20")));

        verify(productMapper, never()).insert(any(org.erp.master.domain.ErpProduct.class));
        ArgumentCaptor<ErpSaleItem> captor = ArgumentCaptor.forClass(ErpSaleItem.class);
        verify(itemMapper, times(2)).insert(captor.capture());
        assertEquals(0L, captor.getAllValues().get(0).getProductId());
        assertTrue(captor.getAllValues().get(1).getNewProductSnapshot().contains("\"qty\":20"));
        verifyNoInteractions(openingGuardService);
    }

    @Test
    void 现录现卖确认时建品按入库数量入库再出库() {
        ErpSaleItem row = new ErpSaleItem();
        row.setProductId(0L);
        row.setQty(new BigDecimal("10"));
        row.setAmount(new BigDecimal("1000.00"));
        row.setNewProductSnapshot(snapshot("临时法兰", "12"));
        when(itemMapper.selectList(any())).thenReturn(List.of(row));
        // 建品统一走商品资料服务：编码生成/唯一校验/库存上下限都在服务内，这里只固定返回主键
        when(productService.insert(any(org.erp.master.domain.bo.ErpProductBo.class))).thenReturn(900L);
        stubOutboundCost(900L, "8.0000");
        ErpSale head = saleMapper.selectById(1L);
        head.setTotalAmount(new BigDecimal("1000.00"));

        service.confirm(1L);

        ArgumentCaptor<org.erp.master.domain.bo.ErpProductBo> boCaptor =
            ArgumentCaptor.forClass(org.erp.master.domain.bo.ErpProductBo.class);
        verify(productService).insert(boCaptor.capture());
        org.erp.master.domain.bo.ErpProductBo bo = boCaptor.getValue();
        // 编码不在此处生成：留空交由商品资料同一套规则（名称拼音首字母），与「商品保存」完全一致
        assertNull(bo.getCode());
        assertEquals("临时法兰", bo.getName());
        assertEquals("PRODUCT", bo.getType());
        assertEquals(900L, row.getProductId());
        // 先按入库数量 12 入库（QUICK_IN），再按销售数量 10 出库，商品ID 回填
        InOrder order = inOrder(inventoryService);
        order.verify(inventoryService).inbound(eq("QUICK_IN"), eq("XS202601010001"), eq(1L), eq(900L), eq(1L),
            eq(new BigDecimal("12")), eq(new BigDecimal("8")), eq("NONE"), isNull(), any(), eq(0));
        order.verify(inventoryService).outbound(eq("SALE"), eq("XS202601010001"), eq(1L), eq(900L), eq(1L),
            eq(new BigDecimal("10")), eq("CUSTOMER"), eq(10L), any(), eq(0));
        // 建品时成本被服务归零（只由入库回写），入库后按本次成本回写
        verify(productService).updateCostPrice(900L, new BigDecimal("8"));
    }

    @Test
    void 现录现卖入库数量小于销售数量被拒绝() {
        org.dromara.common.core.exception.ServiceException ex = assertThrows(
            org.dromara.common.core.exception.ServiceException.class,
            () -> service.update(1L, quickBo(quickItem("临时法兰", "10", "5"))));

        assertTrue(ex.getMessage().contains("入库数量不能小于销售数量"));
        verify(itemMapper, never()).insert(any(ErpSaleItem.class));
    }

    @Test
    void 现录现卖撤回后重新确认只补入库不重复建品() {
        // 撤回已把入库反向冲销，重新确认必须再入库一次才能正常出库，且不得重复建品
        ErpSaleItem row = new ErpSaleItem();
        row.setProductId(900L);
        row.setQty(new BigDecimal("10"));
        row.setAmount(new BigDecimal("1000.00"));
        row.setNewProductSnapshot(snapshot("临时法兰", "12"));
        when(itemMapper.selectList(any())).thenReturn(List.of(row));
        stubOutboundCost(900L, "8.0000");
        ErpSale head = saleMapper.selectById(1L);
        head.setTotalAmount(new BigDecimal("1000.00"));

        service.confirm(1L);

        verify(productMapper, never()).insert(any(org.erp.master.domain.ErpProduct.class));
        InOrder order = inOrder(inventoryService);
        order.verify(inventoryService).inbound(eq("QUICK_IN"), eq("XS202601010001"), eq(1L), eq(900L), eq(1L),
            eq(new BigDecimal("12")), eq(new BigDecimal("8")), eq("NONE"), isNull(), any(), eq(0));
        order.verify(inventoryService).outbound(eq("SALE"), eq("XS202601010001"), eq(1L), eq(900L), eq(1L),
            eq(new BigDecimal("10")), eq("CUSTOMER"), eq(10L), any(), eq(0));
    }

    @Test
    void 撤回后继续编辑保留已回填商品与快照() {
        when(productMapper.selectById(900L)).thenReturn(product(900L, "临时法兰", "NO_TAX"));
        org.erp.trade.domain.bo.ErpSaleBo.SaleItemBo item = quickItem("临时法兰", "10", "12");
        item.setProductId(900L);

        service.update(1L, quickBo(item));

        ArgumentCaptor<ErpSaleItem> captor = ArgumentCaptor.forClass(ErpSaleItem.class);
        verify(itemMapper).insert(captor.capture());
        assertEquals(900L, captor.getValue().getProductId());
        assertTrue(captor.getValue().getNewProductSnapshot().contains("临时法兰"));
        verify(productMapper, never()).insert(any(org.erp.master.domain.ErpProduct.class));
    }

    @Test
    void 详情对已回填商品的现录现卖行仍返回快照() {
        ErpSaleItem row = new ErpSaleItem();
        row.setProductId(900L);
        row.setQty(new BigDecimal("10"));
        row.setPrice(new BigDecimal("100"));
        row.setNewProductSnapshot(snapshot("临时法兰", "12"));
        when(itemMapper.selectList(any())).thenReturn(List.of(row));
        when(productMapper.selectById(900L)).thenReturn(product(900L, "临时法兰", "NO_TAX"));

        var vo = service.selectById(1L);

        // 撤回后商品已回填，快照仍要回传，前端编辑保存才能重建 QUICK_IN 入库语义
        assertEquals(900L, vo.getItems().get(0).getProductId());
        assertEquals("临时法兰", vo.getItems().get(0).getNewProduct().getName());
        assertEquals(0, new BigDecimal("12").compareTo(vo.getItems().get(0).getNewProduct().getQty()));
        assertEquals("临时法兰", vo.getItems().get(0).getProductName());
    }

    @Test
    void 草稿单作废被拒并提示走删除() {
        org.dromara.common.core.exception.ServiceException ex = assertThrows(
            org.dromara.common.core.exception.ServiceException.class,
            () -> service.voidBill(1L));

        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("删除"));
    }

    @Test
    void 删除草稿单同时删明细与主表() {
        service.deleteDraft(1L);

        verify(itemMapper).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(saleMapper).deleteById(1L);
    }

    @Test
    void 删除已确认单被拒40902() {
        ErpSale head = saleMapper.selectById(1L);
        head.setStatus("CONFIRMED");

        org.dromara.common.core.exception.ServiceException ex = assertThrows(
            org.dromara.common.core.exception.ServiceException.class,
            () -> service.deleteDraft(1L));

        assertEquals(40902, ex.getCode());
        verify(saleMapper, never()).deleteById(any(Long.class));
    }
}
