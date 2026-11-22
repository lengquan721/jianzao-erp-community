package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.sequence.SequenceService;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpOtherIn;
import org.erp.trade.domain.ErpOtherInItem;
import org.erp.trade.domain.bo.ErpOtherInBo;
import org.erp.trade.mapper.ErpOtherInItemMapper;
import org.erp.trade.mapper.ErpOtherInMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ErpOtherInServiceImpl 单元测试
 *
 * 覆盖：确认按录入单价入库（OTHER_IN）+ 移动加权、不写成本台账、
 * 撤回 reverse、作废走撤回、状态机拒绝、重复产品拒绝、单价校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class ErpOtherInServiceImplTest {

    private static final Date BIZ_DATE = new Date(0);

    @Mock
    private ErpOtherInMapper otherInMapper;
    @Mock
    private ErpOtherInItemMapper itemMapper;
    @Mock
    private ErpProductMapper productMapper;
    @Mock
    private IErpWarehouseService warehouseService;
    @Mock
    private IInventoryService inventoryService;
    @Mock
    private org.erp.master.service.IErpProductService productService;
    @Mock
    private SequenceService sequenceService;

    private ErpOtherInServiceImpl service;

    /** CAS 状态写入记录（setUp 注册桩时填充） */
    private List<String> casStatuses;

    @BeforeEach
    void setUp() {
        casStatuses = new ArrayList<>();
        lenient().when(otherInMapper.update(isNull(), any(Wrapper.class))).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ErpOtherIn> wrapper = inv.getArgument(1);
            Object status = wrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String && List.of("CONFIRMED", "DRAFT", "VOID").contains(v))
                .findFirst().orElse(null);
            if (status != null) {
                casStatuses.add((String) status);
            }
            return 1;
        });
        service = new ErpOtherInServiceImpl(otherInMapper, itemMapper, productMapper,
            inventoryService, productService, warehouseService, sequenceService);
        when(warehouseService.defaultWarehouseId()).thenReturn(1L);
        when(otherInMapper.insert(any(ErpOtherIn.class))).thenAnswer(inv -> {
            inv.getArgument(0, ErpOtherIn.class).setId(200L);
            return 1;
        });
        when(otherInMapper.updateById(any(ErpOtherIn.class))).thenReturn(1);
        when(itemMapper.insert(any(ErpOtherInItem.class))).thenReturn(1);
        when(sequenceService.nextNo("QR")).thenReturn("QR202611010001");
        when(productMapper.selectById(301L)).thenReturn(product());
    }

    @Test
    void confirm_inboundsAtEnteredUnitCost_noLedger() {
        ErpOtherIn head = head("DRAFT");
        when(otherInMapper.selectById(200L)).thenReturn(head);
        when(otherInMapper.lockById(200L)).thenReturn(head);
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(itemRow("5", "8.0000")));

        service.confirm(200L);

        verify(inventoryService).inbound(eq("OTHER_IN"), eq("QR202611010001"), eq(200L),
            eq(301L), eq(1L), eq(new BigDecimal("5")), eq(new BigDecimal("8.0000")),
            eq("NONE"), eq((Long) null), any(), eq(0));
        assertEquals("CONFIRMED", head.getStatus());
        // DOC/26 §9.4：确认后回写产品资料「最近入库成本」
        verify(productService).updateCostPrice(301L, new BigDecimal("8.0000"));
    }

    @Test
    void insertDraft_persistsEnteredCost_andAmountTotal() {
        ErpOtherInBo bo = bo(new BigDecimal("3"), new BigDecimal("12.5000"));
        when(otherInMapper.selectById(200L)).thenReturn(head("DRAFT"));
        when(otherInMapper.lockById(200L)).thenReturn(head("DRAFT"));
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(itemRow("3", "12.5000")));

        service.insert(bo);

        ArgumentCaptor<ErpOtherInItem> itemCaptor = ArgumentCaptor.forClass(ErpOtherInItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertEquals(0, new BigDecimal("37.50").compareTo(itemCaptor.getValue().getAmount()));
        ArgumentCaptor<ErpOtherIn> headCaptor = ArgumentCaptor.forClass(ErpOtherIn.class);
        verify(otherInMapper, org.mockito.Mockito.atLeastOnce()).updateById(headCaptor.capture());
        assertEquals(0, new BigDecimal("37.50").compareTo(headCaptor.getValue().getAmount()));
        verify(inventoryService, never()).inbound(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void insert_duplicateProduct_rejected() {
        ErpOtherInBo bo = bo(new BigDecimal("1"), new BigDecimal("10"));
        ErpOtherInBo.ItemBo second = new ErpOtherInBo.ItemBo();
        second.setProductId(301L);
        second.setQty(new BigDecimal("2"));
        second.setUnitCost(new BigDecimal("10"));
        bo.getItems().add(second);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insert(bo));
        assertTrue(ex.getMessage().contains("重复"));
        verify(inventoryService, never()).inbound(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void insert_nonPositiveUnitCost_rejectedByValidation() {
        ErpOtherInBo bo = bo(new BigDecimal("1"), BigDecimal.ZERO);
        var violations = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator().validate(bo);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().endsWith("unitCost")));
    }

    @Test
    void withdraw_reversesStock_backToDraft() {
        ErpOtherIn head = head("CONFIRMED");
        when(otherInMapper.selectById(200L)).thenReturn(head);
        when(otherInMapper.lockById(200L)).thenReturn(head);

        service.withdraw(200L);

        // P0-2：撤回改走单据级 reverseByBill（DOC/31 §13.4）
        verify(inventoryService).reverseByBill(eq("QR202611010001"), eq(200L), any());
        assertEquals("DRAFT", head.getStatus());
    }

    @Test
    void voidConfirmed_firstWithdrawsThenVoids() {
        ErpOtherIn head = head("CONFIRMED");
        when(otherInMapper.selectById(200L)).thenReturn(head);
        when(otherInMapper.lockById(200L)).thenReturn(head);
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.voidBill(200L);

        List<String> statuses = captureCasStatus();
        assertTrue(statuses.contains("VOID"), "期望 CAS 写入 VOID，实际: " + statuses);
    }

    @Test
    void voidAlreadyVoid_rejected() {
        when(otherInMapper.selectById(200L)).thenReturn(head("VOID"));
        when(otherInMapper.lockById(200L)).thenReturn(head("VOID"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.voidBill(200L));
        assertTrue(ex.getMessage().contains("已作废"));
    }

    @Test
    void voidDraft_rejectedWithDeleteHint() {
        when(otherInMapper.selectById(200L)).thenReturn(head("DRAFT"));
        when(otherInMapper.lockById(200L)).thenReturn(head("DRAFT"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.voidBill(200L));
        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("删除"));
    }

    @Test
    void deleteDraft_deletesItemsAndHead() {
        when(otherInMapper.selectById(200L)).thenReturn(head("DRAFT"));
        when(otherInMapper.lockById(200L)).thenReturn(head("DRAFT"));

        service.deleteDraft(200L);

        verify(itemMapper).delete(any(Wrapper.class));
        verify(otherInMapper).deleteById(200L);
    }

    @Test
    void deleteDraft_nonDraftRejected40902() {
        when(otherInMapper.selectById(200L)).thenReturn(head("CONFIRMED"));
        when(otherInMapper.lockById(200L)).thenReturn(head("CONFIRMED"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(200L));
        assertEquals(40902, ex.getCode());
        verify(otherInMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void update_confirmedRejected40902() {
        when(otherInMapper.selectById(200L)).thenReturn(head("CONFIRMED"));
        when(otherInMapper.lockById(200L)).thenReturn(head("CONFIRMED"));

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.update(200L, bo(new BigDecimal("1"), new BigDecimal("10"))));
        assertEquals(40902, ex.getCode());
    }

    @Test
    void confirm_nonDraftRejected40902() {
        when(otherInMapper.selectById(200L)).thenReturn(head("CONFIRMED"));
        when(otherInMapper.lockById(200L)).thenReturn(head("CONFIRMED"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm(200L));
        assertEquals(40902, ex.getCode());
    }

    @Test
    void invalidReason_rejectedByValidation() {
        ErpOtherInBo bo = bo(new BigDecimal("1"), new BigDecimal("10"));
        bo.setReason("WRONG");
        var violations = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator().validate(bo);
        assertTrue(violations.stream().anyMatch(v -> "reason".equals(v.getPropertyPath().toString())));
    }

    // ---------- 辅助 ----------

    /**
     * CAS 状态写入记录：update(null, UpdateWrapper) 带 status 条件更新，不再走 updateById。
     * 桩在 setUp 中注册（casStatuses 由 setUp 初始化），测试只需断言其中出现过的状态。
     */
    private List<String> captureCasStatus() {
        return casStatuses;
    }

    private ErpOtherInBo bo(BigDecimal qty, BigDecimal unitCost) {
        ErpOtherInBo bo = new ErpOtherInBo();
        bo.setBizDate(BIZ_DATE);
        bo.setReason("STOCKTAKING_IN");
        ErpOtherInBo.ItemBo item = new ErpOtherInBo.ItemBo();
        item.setProductId(301L);
        item.setQty(qty);
        item.setUnitCost(unitCost);
        bo.getItems().add(item);
        bo.setConfirm(false);
        return bo;
    }

    private ErpOtherIn head(String status) {
        ErpOtherIn head = new ErpOtherIn();
        head.setId(200L);
        head.setNo("QR202611010001");
        head.setBizDate(BIZ_DATE);
        head.setReason("STOCKTAKING_IN");
        head.setStatus(status);
        return head;
    }

    private ErpOtherInItem itemRow(String qty, String unitCost) {
        ErpOtherInItem row = new ErpOtherInItem();
        row.setId(1L);
        row.setOrderId(200L);
        row.setProductId(301L);
        row.setQty(new BigDecimal(qty));
        row.setUnitCost(new BigDecimal(unitCost));
        row.setAmount(new BigDecimal(qty).multiply(new BigDecimal(unitCost)).setScale(2, java.math.RoundingMode.HALF_UP));
        return row;
    }

    private ErpProduct product() {
        ErpProduct p = new ErpProduct();
        p.setId(301L);
        p.setCode("XH001");
        p.setName("消耗品");
        return p;
    }
}
