package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.domain.FinOtherFlow;
import org.erp.fin.domain.bo.FinOtherFlowBo;
import org.erp.fin.mapper.FinOtherFlowMapper;
import org.erp.fin.service.IFinCostLedgerService;
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
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FinOtherFlowServiceImpl 单元测试
 *
 * 覆盖：SR/ZC 单号分派、支出 affect_cost 写台账/撤回 REVERSED、
 * 收入不写台账、affect_cost 对收入强制 0（R19）、状态机拒绝。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class FinOtherFlowServiceImplTest {

    private static final Date BIZ_DATE = new Date(0);

    @Mock
    private FinOtherFlowMapper flowMapper;
    @Mock
    private IFinCostLedgerService costLedgerService;
    @Mock
    private SequenceService sequenceService;

    @Mock
    private org.erp.fin.mapper.FinAccountMapper accountMapper;

    private FinOtherFlowServiceImpl service;

    private org.erp.fin.domain.FinAccount stubAccount;

    @BeforeEach
    void setUp() {
        service = new FinOtherFlowServiceImpl(flowMapper, accountMapper, costLedgerService, sequenceService);
        stubAccount = new org.erp.fin.domain.FinAccount();
        stubAccount.setId(1L);
        stubAccount.setName("现金");
        when(accountMapper.selectById(1L)).thenReturn(stubAccount);
        when(flowMapper.insert(any(FinOtherFlow.class))).thenAnswer(inv -> {
            inv.getArgument(0, FinOtherFlow.class).setId(100L);
            return 1;
        });
        when(flowMapper.update(any(FinOtherFlow.class), any(Wrapper.class))).thenReturn(1);
        when(flowMapper.delete(any(Wrapper.class))).thenReturn(1);
        when(flowMapper.selectById(100L)).thenAnswer(inv -> draftHead());
        when(sequenceService.nextNo("SR")).thenReturn("SR202610310001");
        when(sequenceService.nextNo("ZC")).thenReturn("ZC202610310001");
    }

    private FinOtherFlow draftHead() {
        FinOtherFlow head = new FinOtherFlow();
        head.setId(100L);
        head.setNo("ZC202610310001");
        head.setFlowType("EXPENSE");
        head.setAmount(new BigDecimal("300.00"));
        head.setBizDate(BIZ_DATE);
        head.setAffectCost(1);
        head.setStatus("DRAFT");
        return head;
    }

    @Test
    void insert_expenseWithAffectCost_recordsLedger() {
        FinOtherFlow flow = draftHead();
        when(flowMapper.lockById(100L)).thenReturn(flow);

        service.confirm(100L);

        verify(costLedgerService).record(eq("OTHER_EXPENSE"), eq("OTHER_FLOW"), eq(100L),
            eq("ZC202610310001"), any(), eq(new BigDecimal("300.00")), any());
        assertEquals("CONFIRMED", flow.getStatus());
    }

    @Test
    void insert_expenseWithoutAffectCost_noLedger() {
        FinOtherFlow flow = draftHead();
        flow.setAffectCost(0);
        when(flowMapper.lockById(100L)).thenReturn(flow);

        service.confirm(100L);

        verify(costLedgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
        assertEquals("CONFIRMED", flow.getStatus());
    }

    @Test
    void insert_income_neverRecordsLedger_evenIfAffectCostSet() {
        // R19：收入强制 affect_cost=0（saveHead 归一化）
        service.insert(bo("INCOME", "500", false, 1));

        verify(sequenceService).nextNo("SR");
        ArgumentCaptor<FinOtherFlow> captor = ArgumentCaptor.forClass(FinOtherFlow.class);
        verify(flowMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getAffectCost());
    }

    @Test
    void withdraw_confirmedExpense_reversesLedger() {
        FinOtherFlow head = draftHead();
        head.setStatus("CONFIRMED");
        when(flowMapper.lockById(100L)).thenReturn(head);

        service.withdraw(100L);

        verify(costLedgerService).reverseBySource("OTHER_FLOW", 100L);
        assertEquals("DRAFT", head.getStatus());
    }

    @Test
    void withdraw_draftRejected40902() {
        when(flowMapper.lockById(100L)).thenReturn(draftHead());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.withdraw(100L));
        assertEquals(40902, ex.getCode());
        verify(costLedgerService, never()).reverseBySource(any(), any());
    }

    @Test
    void update_confirmedRejected40902() {
        FinOtherFlow head = draftHead();
        head.setStatus("CONFIRMED");
        when(flowMapper.lockById(100L)).thenReturn(head);

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.update(100L, bo("EXPENSE", "300", false, 0)));
        assertEquals(40902, ex.getCode());
    }

    @Test
    void selectById_missingRejected() {
        when(flowMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.selectById(999L));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void voidConfirmed_withdrawsFirstThenVoids() {
        FinOtherFlow head = draftHead();
        head.setStatus("CONFIRMED");
        when(flowMapper.lockById(100L)).thenReturn(head);

        service.voidBill(100L);

        verify(costLedgerService).reverseBySource("OTHER_FLOW", 100L);
        ArgumentCaptor<FinOtherFlow> captor = ArgumentCaptor.forClass(FinOtherFlow.class);
        verify(flowMapper, org.mockito.Mockito.atLeastOnce()).update(any(FinOtherFlow.class), any(Wrapper.class));
        assertEquals("VOID", head.getStatus());
    }

    @Test
    void voidDraft_rejected40902_pointToDelete() {
        // 草稿不再走作废：作废会把草稿固化成终态单据，草稿应改走删除
        when(flowMapper.lockById(100L)).thenReturn(draftHead());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.voidBill(100L));
        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("草稿单据请使用删除"));
        verify(flowMapper, never()).update(any(FinOtherFlow.class), any(Wrapper.class));
    }

    // ---------- 草稿删除（逻辑删除） ----------

    @Test
    void deleteDraft_draft_deletesHead() {
        when(flowMapper.lockById(100L)).thenReturn(draftHead());

        service.deleteDraft(100L);

        verify(flowMapper).delete(any(Wrapper.class));
        // 成本台账只在确认时写入，草稿删除无需冲销
        verify(costLedgerService, never()).reverseBySource(any(), any());
    }

    @Test
    void deleteDraft_confirmedRejected40902() {
        FinOtherFlow head = draftHead();
        head.setStatus("CONFIRMED");
        when(flowMapper.lockById(100L)).thenReturn(head);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(100L));
        assertEquals(40902, ex.getCode());
        verify(flowMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteDraft_missingRejected() {
        when(flowMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(999L));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ---------- 辅助 ----------

    private FinOtherFlowBo bo(String flowType, String amount, boolean confirm, Integer affectCost) {
        FinOtherFlowBo bo = new FinOtherFlowBo();
        bo.setFlowType(flowType);
        bo.setAmount(new BigDecimal(amount));
        bo.setBizDate(BIZ_DATE);
        bo.setAffectCost(affectCost);
        bo.setConfirm(confirm);
        bo.setCategory("OFFICE");
        // DOC/25：新单必须选结算账户
        bo.setAccountId(1L);
        return bo;
    }
}
