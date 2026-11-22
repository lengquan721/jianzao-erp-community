package org.erp.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.stock.domain.StOpening;
import org.erp.stock.domain.bo.StOpeningBo;
import org.erp.stock.domain.vo.StOpeningVo;
import org.erp.stock.mapper.StOpeningMapper;
import org.erp.stock.service.IInventoryService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StOpeningServiceImpl 单元测试
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class StOpeningServiceImplTest {

    @Mock
    private StOpeningMapper openingMapper;
    @Mock
    private ErpProductMapper productMapper;
    @Mock
    private IInventoryService inventoryService;
    @Mock
    private org.erp.master.service.IErpProductService productService;

    private StOpeningServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StOpeningServiceImpl(openingMapper, productMapper, inventoryService, productService);
        when(openingMapper.insert(any(StOpening.class))).thenReturn(1);
        when(openingMapper.updateById(any(StOpening.class))).thenReturn(1);
        when(openingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    }

    @Test
    void save_newRow_upsertsAsDraft() {
        StOpeningVo vo = service.save(bo("100", "10.5000"));

        ArgumentCaptor<StOpening> captor = ArgumentCaptor.forClass(StOpening.class);
        verify(openingMapper).insert(captor.capture());
        assertEquals("DRAFT", captor.getValue().getStatus());
        assertEquals(0, new BigDecimal("100.000").compareTo(vo.getQty()));
        assertEquals(0, new BigDecimal("10.5000").compareTo(vo.getUnitCost()));
    }

    @Test
    void save_zeroCost_rejected() {
        ErpProduct p = new ErpProduct();
        p.setId(301L);
        p.setName("铝板");
        when(productMapper.selectById(301L)).thenReturn(p);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.save(bo("100", "0")));

        assertTrue(ex.getMessage().contains("商品「铝板」期初成本必须大于 0"));
        verify(openingMapper, never()).insert(any(StOpening.class));
    }

    @Test
    void save_qtyZero_allowsZeroCost() {
        StOpeningVo vo = service.save(bo("0", "0"));

        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getQty()));
        verify(openingMapper).insert(any(StOpening.class));
    }

    @Test
    void save_confirmedRow_rejected40902() {
        StOpening exist = new StOpening();
        exist.setStatus("CONFIRMED");
        when(openingMapper.selectOne(any(Wrapper.class))).thenReturn(exist);

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.save(bo("100", "10")));
        assertEquals(40902, ex.getCode());
    }

    @Test
    void confirm_writesOpeningInbound_andLocks() {
        StOpening row = openingRow("DRAFT");
        when(openingMapper.selectById(1L)).thenReturn(row);

        service.confirm(1L);

        verify(inventoryService).inbound(eq("OPENING"), eq("QC1"), eq(1L),
            eq(301L), eq(1L), eq(new BigDecimal("100.000")), eq(new BigDecimal("10.5000")),
            eq("NONE"), eq((Long) null), any(), eq(0));
        assertEquals("CONFIRMED", row.getStatus());
        // DOC/26 §9.4：确认后回写产品资料「最近入库成本」
        verify(productService).updateCostPrice(301L, new BigDecimal("10.5000"));
    }

    @Test
    void confirm_zeroCostDraft_rejected() {
        StOpening row = openingRow("DRAFT");
        row.setUnitCost(BigDecimal.ZERO);
        when(openingMapper.selectById(1L)).thenReturn(row);

        assertThrows(ServiceException.class, () -> service.confirm(1L));
        verify(inventoryService, never()).inbound(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(productService, never()).updateCostPrice(any(), any());
    }

    @Test
    void confirm_alreadyConfirmed_rejected40902() {
        when(openingMapper.selectById(1L)).thenReturn(openingRow("CONFIRMED"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm(1L));
        assertEquals(40902, ex.getCode());
        verify(inventoryService, never()).inbound(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void unconfirm_reversesFlow_backToDraft() {
        StOpening row = openingRow("CONFIRMED");
        when(openingMapper.selectById(1L)).thenReturn(row);

        service.unconfirm(1L);

        // P0-2：反确认改走单据级 reverseByBill（DOC/31 §13.4）
        verify(inventoryService).reverseByBill(eq("QC1"), eq(1L), any());
        assertEquals("DRAFT", row.getStatus());
    }

    @Test
    void unconfirm_draftRejected40902() {
        when(openingMapper.selectById(1L)).thenReturn(openingRow("DRAFT"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.unconfirm(1L));
        assertEquals(40902, ex.getCode());
        verify(inventoryService, never()).reverseByBill(any(), any(), any());
    }

    @Test
    void pageDetails_keepsDatabaseProjectionAndTotal_withoutNameNPlusOne() {
        StOpeningVo row = new StOpeningVo();
        row.setId(1L);
        row.setProductCode("YL001");
        row.setProductName("铝板");
        row.setWarehouseCode("WH1");
        row.setWarehouseName("主仓");
        row.setStatus("DRAFT");
        when(openingMapper.countDetails(1L)).thenReturn(151L);
        when(openingMapper.selectDetails(1L, 100, 50)).thenReturn(List.of(row));

        var page = service.pageDetails(1L, 3, 50);

        assertEquals(151, page.getTotal());
        assertEquals(1, page.getRows().size());
        assertEquals("YL001", page.getRows().get(0).getProductCode());
        assertEquals("铝板", page.getRows().get(0).getProductName());
        assertEquals("主仓", page.getRows().get(0).getWarehouseName());
        assertEquals("草稿", page.getRows().get(0).getStatusText());
        verify(productMapper, never()).selectById(any());
        verify(openingMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void pageDetails_outOfRange_emptyNotFirstPage() {
        when(openingMapper.countDetails(null)).thenReturn(1L);
        var page = service.pageDetails(null, 2, 50);
        assertEquals(1, page.getTotal());
        assertTrue(page.getRows().isEmpty());
        verify(openingMapper, never()).selectDetails(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void export_limitIsIndependentOfUiPaging_noSilentTruncation() {
        StOpeningVo row = new StOpeningVo();
        row.setStatus("DRAFT");
        when(openingMapper.selectDetails(null, 0, 5001)).thenReturn(java.util.Collections.nCopies(5000, row));
        assertEquals(5000, service.listForExport(null).size());
        when(openingMapper.selectDetails(null, 0, 5001)).thenReturn(java.util.Collections.nCopies(5001, row));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.listForExport(null));
        assertTrue(ex.getMessage().contains("5000"));
    }

    @Test
    void pageDetails_invalidPaging_rejectedBeforeQuery() {
        assertThrows(ServiceException.class, () -> service.pageDetails(null, 0, 10));
        assertThrows(ServiceException.class, () -> service.pageDetails(null, 1, 101));
        assertThrows(ServiceException.class, () -> service.pageDetails(null, 1, -1));
        org.mockito.Mockito.verifyNoInteractions(openingMapper, productMapper);
    }

    @Test
    void deleteDraft_draftRow_logicDeleted() {
        when(openingMapper.selectById(1L)).thenReturn(openingRow("DRAFT"));

        service.deleteDraft(1L);

        verify(openingMapper).deleteById(1L);
    }

    @Test
    void deleteDraft_confirmedRow_rejected40902() {
        when(openingMapper.selectById(1L)).thenReturn(openingRow("CONFIRMED"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(1L));
        assertEquals(40902, ex.getCode());
        verify(openingMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteDraft_notFound_rejected() {
        when(openingMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(999L));
        assertTrue(ex.getMessage().contains("不存在"));
        verify(openingMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void save_deletedRow_revivesInsteadOfInsert() {
        // 唯一键 uk_st_opening 不含 del_flag：已删除行仍占键，必须复活原行而非插入新行
        when(openingMapper.selectDeletedId(301L, 1L)).thenReturn(7L);

        StOpeningVo vo = service.save(bo("100", "10.5"));

        verify(openingMapper).reviveDeleted(eq(7L), eq(new BigDecimal("100.000")), eq(new BigDecimal("10.5000")));
        verify(openingMapper, never()).insert(any(StOpening.class));
        assertEquals(7L, vo.getId());
        assertEquals("DRAFT", vo.getStatus());
    }

    // ---------- 辅助 ----------

    private StOpeningBo bo(String qty, String unitCost) {
        StOpeningBo bo = new StOpeningBo();
        bo.setProductId(301L);
        bo.setWarehouseId(1L);
        bo.setQty(new BigDecimal(qty));
        bo.setUnitCost(new BigDecimal(unitCost));
        return bo;
    }

    private StOpening openingRow(String status) {
        StOpening row = new StOpening();
        row.setId(1L);
        row.setProductId(301L);
        row.setWarehouseId(1L);
        row.setQty(new BigDecimal("100.000"));
        row.setUnitCost(new BigDecimal("10.5000"));
        row.setStatus(status);
        return row;
    }
}
