package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.service.IFinCostLedgerService;
import org.erp.master.domain.ErpProduct;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.service.IErpWarehouseService;
import org.erp.stock.service.IInventoryService;
import org.erp.trade.domain.ErpOtherOut;
import org.erp.trade.domain.ErpOtherOutItem;
import org.erp.trade.domain.bo.ErpOtherOutBo;
import org.erp.trade.mapper.ErpOtherOutItemMapper;
import org.erp.trade.mapper.ErpOtherOutMapper;
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
 * ErpOtherOutServiceImpl 单元测试
 *
 * 覆盖：确认快照成本+扣库存+成本台账、不产生应收、撤回 reverseByBill+台账 REVERSED、
 * 作废走撤回、状态机拒绝、库存不足透传 40901。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class ErpOtherOutServiceImplTest {

    private static final Date BIZ_DATE = new Date(0);

    @Mock
    private ErpOtherOutMapper otherOutMapper;
    @Mock
    private ErpOtherOutItemMapper itemMapper;
    @Mock
    private ErpProductMapper productMapper;
    @Mock
    private IErpWarehouseService warehouseService;
    @Mock
    private IInventoryService inventoryService;
    @Mock
    private IFinCostLedgerService costLedgerService;
    @Mock
    private SequenceService sequenceService;

    private ErpOtherOutServiceImpl service;

    /** CAS 状态写入记录（setUp 注册桩时填充） */
    private List<String> casStatuses;

    @BeforeEach
    void setUp() {
        casStatuses = new ArrayList<>();
        lenient().when(otherOutMapper.update(isNull(), any(Wrapper.class))).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ErpOtherOut> wrapper = inv.getArgument(1);
            Object status = wrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String && List.of("CONFIRMED", "DRAFT", "VOID").contains(v))
                .findFirst().orElse(null);
            if (status != null) {
                casStatuses.add((String) status);
            }
            return 1;
        });
        service = new ErpOtherOutServiceImpl(otherOutMapper, itemMapper, productMapper,
            inventoryService, warehouseService, costLedgerService, sequenceService);
        when(warehouseService.defaultWarehouseId()).thenReturn(1L);
        when(otherOutMapper.insert(any(ErpOtherOut.class))).thenAnswer(inv -> {
            inv.getArgument(0, ErpOtherOut.class).setId(100L);
            return 1;
        });
        when(otherOutMapper.updateById(any(ErpOtherOut.class))).thenReturn(1);
        when(itemMapper.insert(any(ErpOtherOutItem.class))).thenReturn(1);
        when(sequenceService.nextNo("QT")).thenReturn("QT202610310001");
        when(productMapper.selectById(301L)).thenReturn(product());
    }

    @Test
    void confirm_snapshotsCost_outbounds_recordsLedger() {
        ErpOtherOut head = head("DRAFT");
        when(otherOutMapper.selectById(100L)).thenReturn(head);
        when(otherOutMapper.lockById(100L)).thenReturn(head);
        when(itemMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(itemRow("2", "0", "0")))
            .thenReturn(List.of(itemRow("2", "10.5000", "21.00")));
        when(inventoryService.outbound(eq("OTHER_OUT"), any(), eq(100L), eq(301L), eq(1L),
            eq(new BigDecimal("2")), eq("NONE"), eq((Long) null), any(), eq(0)))
            .thenReturn(new BigDecimal("10.5000"));

        service.confirm(100L);
        verify(inventoryService, never()).snapshotCost(any());

        InOrder inOrder = inOrder(inventoryService, costLedgerService);
        inOrder.verify(inventoryService).outbound(eq("OTHER_OUT"), eq("QT202610310001"), eq(100L),
            eq(301L), eq(1L), eq(new BigDecimal("2")), eq("NONE"), eq((Long) null), any(), eq(0));
        inOrder.verify(costLedgerService).record(eq("OTHER_OUT"), eq("OTHER_OUT"), eq(100L),
            eq("QT202610310001"), any(), eq(new BigDecimal("21.00")), any());
        assertEquals("CONFIRMED", head.getStatus());
        assertEquals(0, new BigDecimal("21.00").compareTo(head.getCostAmount()));
    }

    @Test
    void confirm_insufficientStock_propagates40901() {
        when(otherOutMapper.selectById(100L)).thenReturn(head("DRAFT"));
        when(otherOutMapper.lockById(100L)).thenReturn(head("DRAFT"));
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(itemRow("2", "0", "0")));
        org.mockito.Mockito.doThrow(new ServiceException("库存不足，单据保存失败", 40901))
            .when(inventoryService).outbound(any(), any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm(100L));
        assertEquals(40901, ex.getCode());
        verify(costLedgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void withdraw_reversesStock_andCostLedger() {
        ErpOtherOut head = head("CONFIRMED");
        when(otherOutMapper.selectById(100L)).thenReturn(head);
        when(otherOutMapper.lockById(100L)).thenReturn(head);

        service.withdraw(100L);

        InOrder inOrder = inOrder(inventoryService, costLedgerService);
        // P0-2：撤回改走单据级 reverseByBill（DOC/31 §13.4）
        inOrder.verify(inventoryService).reverseByBill(eq("QT202610310001"), eq(100L), any());
        inOrder.verify(costLedgerService).reverseBySource("OTHER_OUT", 100L);
        assertEquals("DRAFT", head.getStatus());
    }

    @Test
    void voidConfirmed_firstWithdrawsThenVoids() {
        ErpOtherOut head = head("CONFIRMED");
        when(otherOutMapper.selectById(100L)).thenReturn(head);
        when(otherOutMapper.lockById(100L)).thenReturn(head);
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.voidBill(100L);

        List<String> statuses = captureCasStatus();
        assertTrue(statuses.contains("VOID"), "期望 CAS 写入 VOID，实际: " + statuses);
    }

    @Test
    void voidAlreadyVoid_rejected() {
        when(otherOutMapper.selectById(100L)).thenReturn(head("VOID"));
        when(otherOutMapper.lockById(100L)).thenReturn(head("VOID"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.voidBill(100L));
        assertTrue(ex.getMessage().contains("已作废"));
    }

    @Test
    void voidDraft_rejectedWithDeleteHint() {
        when(otherOutMapper.selectById(100L)).thenReturn(head("DRAFT"));
        when(otherOutMapper.lockById(100L)).thenReturn(head("DRAFT"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.voidBill(100L));
        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("删除"));
    }

    @Test
    void deleteDraft_deletesItemsAndHead() {
        when(otherOutMapper.selectById(100L)).thenReturn(head("DRAFT"));
        when(otherOutMapper.lockById(100L)).thenReturn(head("DRAFT"));

        service.deleteDraft(100L);

        verify(itemMapper).delete(any(Wrapper.class));
        verify(otherOutMapper).deleteById(100L);
    }

    @Test
    void deleteDraft_nonDraftRejected40902() {
        when(otherOutMapper.selectById(100L)).thenReturn(head("CONFIRMED"));
        when(otherOutMapper.lockById(100L)).thenReturn(head("CONFIRMED"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(100L));
        assertEquals(40902, ex.getCode());
        verify(otherOutMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void update_confirmedRejected40902() {
        when(otherOutMapper.selectById(100L)).thenReturn(head("CONFIRMED"));
        when(otherOutMapper.lockById(100L)).thenReturn(head("CONFIRMED"));

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.update(100L, bo()));
        assertEquals(40902, ex.getCode());
    }

    @Test
    void insert_invalidCostCategory_rejectedByValidation() {
        ErpOtherOutBo bo = bo();
        bo.setCostCategory("WRONG");
        // Bean Validation 由 Controller 层 @Valid 强制；此用例校验 Bo 注解生效
        var violations = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator().validate(bo);
        assertTrue(violations.stream().anyMatch(v -> "costCategory".equals(v.getPropertyPath().toString())));
    }

    // ---------- 辅助 ----------

    /**
     * CAS 状态写入记录：update(null, UpdateWrapper) 带 status 条件更新，不再走 updateById。
     * 桩在 setUp 中注册（casStatuses 由 setUp 初始化），测试只需断言其中出现过的状态。
     */
    private List<String> captureCasStatus() {
        return casStatuses;
    }

    private ErpOtherOutBo bo() {
        ErpOtherOutBo bo = new ErpOtherOutBo();
        bo.setBizDate(BIZ_DATE);
        bo.setCostCategory("PRODUCTION_CONSUMABLE");
        ErpOtherOutBo.ItemBo item = new ErpOtherOutBo.ItemBo();
        item.setProductId(301L);
        item.setQty(new BigDecimal("2"));
        bo.getItems().add(item);
        bo.setConfirm(false);
        return bo;
    }

    private ErpOtherOut head(String status) {
        ErpOtherOut head = new ErpOtherOut();
        head.setId(100L);
        head.setNo("QT202610310001");
        head.setBizDate(BIZ_DATE);
        head.setCostCategory("PRODUCTION_CONSUMABLE");
        head.setStatus(status);
        return head;
    }

    private ErpOtherOutItem itemRow(String qty, String unitCost, String amount) {
        ErpOtherOutItem row = new ErpOtherOutItem();
        row.setId(1L);
        row.setOrderId(100L);
        row.setProductId(301L);
        row.setQty(new BigDecimal(qty));
        row.setUnitCost(new BigDecimal(unitCost));
        row.setAmount(new BigDecimal(amount));
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
