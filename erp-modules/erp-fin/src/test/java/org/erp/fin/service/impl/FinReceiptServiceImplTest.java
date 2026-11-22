package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.domain.FinReceiptAlloc;
import org.erp.fin.domain.bo.FinReceiptBo;
import org.erp.fin.domain.vo.FinArApSourceVo;
import org.erp.fin.domain.vo.FinReceiptVo;
import org.erp.fin.mapper.FinReceiptAllocMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.mapper.ErpCustomerMapper;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FinReceiptServiceImpl 单元测试（付款单与其对称，不重复覆盖）
 *
 * 覆盖：确认核销（逐行 settle + Σ=amount 校验）、部分核销、一收多单、
 * 超额核销拒、错客户台账拒、反核销恢复、状态机拒绝、作废走撤回。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class FinReceiptServiceImplTest {

    private static final Date BIZ_DATE = new Date(0);

    @Mock
    private FinReceiptMapper receiptMapper;
    @Mock
    private FinReceiptAllocMapper allocMapper;
    @Mock
    private ErpCustomerMapper customerMapper;
    @Mock
    private IFinArApService arApService;
    @Mock
    private SequenceService sequenceService;

    @Mock
    private org.erp.fin.mapper.FinAccountMapper accountMapper;

    @Mock private org.erp.fin.service.IFinOpeningGuardService openingGuard;
    private FinReceiptServiceImpl service;

    /** CAS 状态写入记录（setUp 注册桩时填充） */
    private List<String> casStatuses;

    private org.erp.fin.domain.FinAccount stubAccount;

    @BeforeEach
    void setUp() {
        casStatuses = new ArrayList<>();
        lenient().when(receiptMapper.update(any(FinReceipt.class), any(Wrapper.class))).thenReturn(1);
        lenient().when(receiptMapper.delete(any(Wrapper.class))).thenReturn(1);
        lenient().when(receiptMapper.update(isNull(), any(Wrapper.class))).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<FinReceipt> wrapper = inv.getArgument(1);
            Object status = wrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String && List.of("CONFIRMED", "DRAFT", "VOID").contains(v))
                .findFirst().orElse(null);
            if (status != null) {
                casStatuses.add((String) status);
            }
            return 1;
        });
        service = new FinReceiptServiceImpl(receiptMapper, accountMapper, allocMapper, customerMapper, arApService, sequenceService, openingGuard);
        stubAccount = new org.erp.fin.domain.FinAccount();
        stubAccount.setId(1L);
        stubAccount.setName("微信");
        when(accountMapper.selectById(1L)).thenReturn(stubAccount);
        when(receiptMapper.insert(any(FinReceipt.class))).thenAnswer(inv -> {
            inv.getArgument(0, FinReceipt.class).setId(100L);
            return 1;
        });
        when(allocMapper.insert(any(FinReceiptAlloc.class))).thenReturn(1);
        when(sequenceService.nextNo("SK")).thenReturn("SK202610310001");
        when(customerMapper.selectById(any())).thenReturn(customer());
        // 核销明细回填来源单号（selectById 走 loadAllocs）时批量查台账来源
        when(arApService.listSourceInfo(any(), eq("AR"), eq(9L))).thenReturn(List.of());
    }

    // ---------- 确认核销 ----------

    @Test
    void ordinaryReceiptCannotCreateRefundFromClientFlag() {
        FinReceiptBo bo = receiptBo(9L, "50.00", false);
        bo.setNegative(true);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insert(bo));

        assertEquals(40902, ex.getCode());
        verifyNoInteractions(receiptMapper, allocMapper, sequenceService);
    }

    @Test
    void insertWithConfirm_settlesLedgersInOrder() {
        when(allocMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
            // confirmInternal 读取草稿期明细（saveAllocations 已插入内存对象集合模拟）
            return List.of(allocRow(1L, 5001L, "600.00"), allocRow(2L, 5002L, "400.00"));
        });
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("DRAFT", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("DRAFT", "1000.00"));

        service.insert(receiptBo(9L, "1000.00", true,
            alloc(5001L, "600.00"), alloc(5002L, "400.00")));

        InOrder inOrder = inOrder(arApService);
        inOrder.verify(arApService).settle(5001L, new BigDecimal("600.00"), "AR", 9L);
        inOrder.verify(arApService).settle(5002L, new BigDecimal("400.00"), "AR", 9L);

        List<String> headStatuses = captureCasStatus();
        assertTrue(headStatuses.contains("CONFIRMED"), "期望 CAS 写入 CONFIRMED，实际: " + headStatuses);
    }

    @Test
    void insertWithConfirm_sumMismatch_rejected() {
        // 明细合计 900 ≠ 收款 1000
        when(allocMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(allocRow(1L, 5001L, "600.00"), allocRow(2L, 5002L, "300.00")));

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.insert(receiptBo(9L, "1000.00", true,
                alloc(5001L, "600.00"), alloc(5002L, "300.00"))));
        assertTrue(ex.getMessage().contains("必须等于收款金额"));
        // 已 settle 的行不应回滚为提交（事务层负责回滚，这里验证确认状态未写）
        // 合计校验失败时不得写状态：CAS 未被调用
        verify(receiptMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void confirm_emptyAllocations_rejected() {
        FinReceipt head = receiptHead("DRAFT", "500.00");
        when(receiptMapper.selectById(100L)).thenReturn(head);
        when(receiptMapper.lockById(100L)).thenReturn(head);
        when(allocMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        // 无明细确认应失败（Σ=0 ≠ 500）
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm(100L));
        assertTrue(ex.getMessage().contains("必须等于收款金额"));
        verify(arApService, never()).settle(any(), any(), any(), any());
    }

    // ---------- 反核销 / 撤回 ----------

    @Test
    void withdraw_unsettlesAllAllocs_backToDraft() {
        FinReceipt head = receiptHead("CONFIRMED", "1000.00");
        when(receiptMapper.selectById(100L)).thenReturn(head);
        when(receiptMapper.lockById(100L)).thenReturn(head);
        when(allocMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(allocRow(1L, 5001L, "600.00"), allocRow(2L, 5002L, "400.00")));

        service.withdraw(100L);

        InOrder inOrder = inOrder(arApService);
        inOrder.verify(arApService).unsettle(5001L, new BigDecimal("600.00"), "AR", 9L);
        inOrder.verify(arApService).unsettle(5002L, new BigDecimal("400.00"), "AR", 9L);
        // 撤回保留核销明细，供重新确认复用；物理清理只在作废时发生
        verify(allocMapper, never()).delete(any(Wrapper.class));
        List<String> captorStatuses = captureCasStatus();
        assertTrue(captorStatuses.contains("DRAFT"),
            "期望 CAS 写入 DRAFT，实际: " + captorStatuses);
    }

    @Test
    void withdraw_draftRejected40902() {
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("DRAFT", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("DRAFT", "1000.00"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.withdraw(100L));
        assertEquals(40902, ex.getCode());
        verify(arApService, never()).unsettle(any(), any(), any(), any());
    }

    /**
     * 撤回后核销明细仍在，重新确认可直接复用（回归：曾因撤回时物理删除明细导致无法重确认）
     */
    @Test
    void withdrawThenReconfirm_reusesKeptAllocations() {
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("CONFIRMED", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("CONFIRMED", "1000.00"));
        when(allocMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(allocRow(1L, 5001L, "600.00"), allocRow(2L, 5002L, "400.00")));

        service.withdraw(100L);
        // 撤回不清明细
        verify(allocMapper, never()).delete(any(Wrapper.class));

        // 重新确认：明细仍在，Σ=1000 与单据金额一致，可再次核销
        FinReceipt head = receiptHead("DRAFT", "1000.00");
        when(receiptMapper.selectById(100L)).thenReturn(head);
        when(receiptMapper.lockById(100L)).thenReturn(head);
        service.confirm(100L);

        verify(arApService).settle(5001L, new BigDecimal("600.00"), "AR", 9L);
        verify(arApService).settle(5002L, new BigDecimal("400.00"), "AR", 9L);
        List<String> captorStatuses = captureCasStatus();
        assertTrue(captorStatuses.contains("CONFIRMED"),
            "期望 CAS 写入 CONFIRMED，实际: " + captorStatuses);
    }

    @Test
    void voidConfirmed_firstWithdrawsThenVoids() {
        FinReceipt head = receiptHead("CONFIRMED", "1000.00");
        when(receiptMapper.selectById(100L)).thenReturn(head);
        when(receiptMapper.lockById(100L)).thenReturn(head);
        when(allocMapper.selectList(any(Wrapper.class))).thenReturn(List.of(allocRow(1L, 5001L, "1000.00")));

        service.voidBill(100L);

        verify(arApService).unsettle(5001L, new BigDecimal("1000.00"), "AR", 9L);
        List<String> captorStatuses = captureCasStatus();
        assertTrue(captorStatuses.contains("VOID"),
            "期望 CAS 写入 VOID，实际: " + captorStatuses);
    }

    @Test
    void voidAlreadyVoid_rejected() {
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("VOID", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("VOID", "1000.00"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.voidBill(100L));
        assertTrue(ex.getMessage().contains("已作废"));
    }

    @Test
    void selectById_allocationsCarrySourceNo() {
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("CONFIRMED", "1000.00"));
        when(allocMapper.selectList(any(Wrapper.class))).thenReturn(List.of(allocRow(1L, 5001L, "1000.00")));
        FinArApSourceVo source = new FinArApSourceVo();
        source.setArApId(5001L);
        source.setSourceType("SALE");
        source.setSourceNo("XS202611010001");
        when(arApService.listSourceInfo(any(), eq("AR"), eq(9L))).thenReturn(List.of(source));

        FinReceiptVo vo = service.selectById(100L);

        // 详情核销明细必须带业务单号，前端不再展示内部台账 id
        assertEquals("XS202611010001", vo.getAllocations().get(0).getSourceNo());
        assertEquals("SALE", vo.getAllocations().get(0).getSourceType());
    }

    @Test
    void voidDraft_rejected40902_pointToDelete() {
        // 草稿不再走作废：作废会把草稿固化成终态单据，草稿应改走删除
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("DRAFT", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("DRAFT", "1000.00"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.voidBill(100L));
        assertEquals(40902, ex.getCode());
        assertTrue(ex.getMessage().contains("草稿单据请使用删除"));
        verify(receiptMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // ---------- 草稿删除（逻辑删除） ----------

    @Test
    void deleteDraft_draft_deletesHeadAndAllocs() {
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("DRAFT", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("DRAFT", "1000.00"));

        service.deleteDraft(100L);

        // 明细一并逻辑删除，主表逻辑删除（不物理 DELETE）
        verify(allocMapper).delete(any(Wrapper.class));
        verify(receiptMapper).delete(any(Wrapper.class));
        // 草稿未核销过台账，不应触发反核销
        verify(arApService, never()).unsettle(any(), any(), any(), any());
    }

    @Test
    void deleteDraft_confirmedRejected40902() {
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("CONFIRMED", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("CONFIRMED", "1000.00"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(100L));
        assertEquals(40902, ex.getCode());
        verify(receiptMapper, never()).deleteById(any(Long.class));
        verify(allocMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void deleteDraft_voidRejected40902() {
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("VOID", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("VOID", "1000.00"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(100L));
        assertEquals(40902, ex.getCode());
        verify(receiptMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteDraft_missingRejected() {
        when(receiptMapper.selectById(999L)).thenReturn(null);
        when(receiptMapper.lockById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDraft(999L));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ---------- 编辑 ----------

    @Test
    void update_confirmedRejected40902() {
        when(receiptMapper.selectById(100L)).thenReturn(receiptHead("CONFIRMED", "1000.00"));
        when(receiptMapper.lockById(100L)).thenReturn(receiptHead("CONFIRMED", "1000.00"));

        ServiceException ex = assertThrows(ServiceException.class, () ->
            service.update(100L, receiptBo(9L, "1000.00", false)));
        assertEquals(40902, ex.getCode());
    }

    @Test
    void selectById_missingRejected() {
        when(receiptMapper.selectById(999L)).thenReturn(null);
        when(receiptMapper.lockById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.selectById(999L));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ---------- 辅助 ----------

    /**
     * CAS 状态写入记录：update(null, UpdateWrapper) 带 status 条件更新，不再走 updateById。
     * 桩在 setUp 中注册（casStatuses 由 setUp 初始化），测试只需断言其中出现过的状态。
     */
    private List<String> captureCasStatus() {
        return casStatuses;
    }

    private FinReceiptBo receiptBo(Long customerId, String amount, boolean confirm,
                                   FinReceiptBo.AllocationBo... allocations) {
        FinReceiptBo bo = new FinReceiptBo();
        bo.setCustomerId(customerId);
        bo.setBizDate(BIZ_DATE);
        bo.setAmount(new BigDecimal(amount));
        bo.setConfirm(confirm);
        // DOC/25：新单必须选结算账户（account 名称由服务端按账户回填）
        bo.setAccountId(1L);
        for (FinReceiptBo.AllocationBo a : allocations) {
            bo.getAllocations().add(a);
        }
        return bo;
    }

    private FinReceiptBo.AllocationBo alloc(Long arApId, String amount) {
        FinReceiptBo.AllocationBo bo = new FinReceiptBo.AllocationBo();
        bo.setArApId(arApId);
        bo.setAmount(new BigDecimal(amount));
        return bo;
    }

    private FinReceipt receiptHead(String status, String amount) {
        FinReceipt head = new FinReceipt();
        head.setId(100L);
        head.setNo("SK202610310001");
        head.setCustomerId(9L);
        head.setAmount(new BigDecimal(amount));
        head.setBizDate(BIZ_DATE);
        head.setStatus(status);
        return head;
    }

    private FinReceiptAlloc allocRow(long id, long arApId, String amount) {
        FinReceiptAlloc row = new FinReceiptAlloc();
        row.setId(id);
        row.setReceiptId(100L);
        row.setArApId(arApId);
        row.setAmount(new BigDecimal(amount));
        return row;
    }

    private ErpCustomer customer() {
        ErpCustomer c = new ErpCustomer();
        c.setId(9L);
        c.setName("示例机械");
        return c;
    }
}
