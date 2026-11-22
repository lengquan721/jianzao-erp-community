package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.erp.common.enums.StatusText;
import org.erp.common.sequence.SequenceService;
import org.erp.common.security.ConfirmPermission;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.domain.FinReceiptAlloc;
import org.erp.fin.domain.FinSaleReturnRefundSource;
import org.erp.fin.domain.bo.FinReceiptBo;
import org.erp.fin.domain.vo.FinArApSourceVo;
import org.erp.fin.domain.vo.FinReceiptVo;
import org.erp.fin.mapper.FinReceiptAllocMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinReceiptService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.mapper.ErpCustomerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 收款单服务实现
 *
 * 规则（DOC/11 §8、DOC/13 §7.8、DOC/12 §8）：
 * - 确认：Σ核销明细 = 收款金额；逐行校验台账归属客户、未核销余额充足；
 *   落 fin_receipt_alloc 并累计 fin_ar_ap.settled_amount（OPEN/PARTIAL → SETTLED）；
 * - 撤回：先反核销（settled_amount 减回），再回 DRAFT；作废走撤回；
 * - 部分核销、一收多单支持；已确认不可编辑。
 */
@RequiredArgsConstructor
@Service
public class FinReceiptServiceImpl implements IFinReceiptService {

    private final FinReceiptMapper receiptMapper;
    private final org.erp.fin.mapper.FinAccountMapper accountMapper;
    private final FinReceiptAllocMapper allocMapper;
    private final ErpCustomerMapper customerMapper;
    private final IFinArApService arApService;
    private final SequenceService sequenceService;
    private final org.erp.fin.service.IFinOpeningGuardService openingGuard;

    @Override
    public TableDataInfo<FinReceiptVo> selectPage(Long customerId, String status, PageQuery pageQuery) {
        LambdaQueryWrapper<FinReceipt> lqw = new LambdaQueryWrapper<>();
        lqw.eq(customerId != null, FinReceipt::getCustomerId, customerId);
        lqw.eq(status != null && !status.isBlank(), FinReceipt::getStatus, status);
        lqw.orderByDesc(FinReceipt::getId);
        Page<FinReceipt> page = receiptMapper.selectPage(pageQuery.build(), lqw);
        Page<FinReceiptVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public FinReceiptVo selectById(Long id) {
        FinReceipt head = receiptMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("收款单不存在");
        }
        FinReceiptVo vo = toVo(head);
        vo.setAllocations(loadAllocs(head));
        return vo;
    }

    private FinReceiptVo toVo(FinReceipt head) {
        FinReceiptVo vo = new FinReceiptVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        vo.setCustomerId(head.getCustomerId());
        ErpCustomer c = customerMapper.selectById(head.getCustomerId());
        vo.setCustomerName(c == null ? null : c.getName());
        vo.setAmount(head.getAmount());
        vo.setBizDate(head.getBizDate());
        vo.setAccount(head.getAccount());
        vo.setAccountId(head.getAccountId());
        vo.setNegative(head.getNegative());
        vo.setRemark(head.getRemark());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setCreateTime(head.getCreateTime());
        return vo;
    }

    private List<FinReceiptVo.AllocVo> loadAllocs(FinReceipt head) {
        List<FinReceiptAlloc> rows = allocMapper.selectList(new LambdaQueryWrapper<FinReceiptAlloc>()
            .eq(FinReceiptAlloc::getReceiptId, head.getId())
            .orderByAsc(FinReceiptAlloc::getId));
        // 台账 id 换业务单号：核销明细只存 ar_ap_id，直接回显用户无法识别；无单号时退回来源类型文案
        Map<Long, FinArApSourceVo> sources = new HashMap<>();
        List<Long> arApIds = rows.stream().map(FinReceiptAlloc::getArApId).filter(Objects::nonNull).distinct().toList();
        if (!arApIds.isEmpty()) {
            for (FinArApSourceVo item : arApService.listSourceInfo(arApIds, "AR", head.getCustomerId())) {
                sources.put(item.getArApId(), item);
            }
        }
        List<FinReceiptVo.AllocVo> result = new ArrayList<>();
        for (FinReceiptAlloc row : rows) {
            FinReceiptVo.AllocVo vo = new FinReceiptVo.AllocVo();
            FinArApSourceVo source = sources.get(row.getArApId());
            vo.setId(row.getId());
            vo.setArApId(row.getArApId());
            vo.setAmount(row.getAmount());
            vo.setSourceType(source == null ? null : source.getSourceType());
            vo.setSourceNo(source == null ? null : source.getSourceNo());
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FinReceiptVo insert(FinReceiptBo bo) {
        rejectExternalNegative(bo);
        openingGuard.lockParty("AR", bo.getCustomerId());
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            openingGuard.lockAndCheckBusinessDate("AR", bo.getCustomerId(), bo.getBizDate());
        }
        FinReceipt head = new FinReceipt();
        head.setNo(sequenceService.nextNo("SK"));
        saveHead(head, bo, false);
        saveAllocations(head.getId(), bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head, null);
        }
        return selectById(head.getId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Long insertSaleReturnRefund(Long returnId) {
        ConfirmPermission.check("erp:finance:receipt:confirm", true);
        FinSaleReturnRefundSource source = receiptMapper.lockRefundSource(returnId);
        validatePendingRefund(source);
        var originalAr = arApService.getBySource("SALE", source.getSaleId());
        if (originalAr == null || !"AR".equals(originalAr.getPartyType())
            || !source.getCustomerId().equals(originalAr.getPartyId())
            || !"SALE".equals(originalAr.getSourceType())
            || !source.getSaleId().equals(originalAr.getSourceId())) {
            throw new ServiceException("原销售单应收台账不存在或归属不匹配，无法退款", 40902);
        }

        FinReceiptBo bo = new FinReceiptBo();
        bo.setCustomerId(source.getCustomerId());
        bo.setBizDate(source.getBizDate());
        bo.setAmount(source.getRefundAmount());
        bo.setAccountId(source.getRefundAccountId());
        bo.setRemark("销售退货退款 " + source.getNo());
        bo.setConfirm(true);
        FinReceiptBo.AllocationBo allocation = new FinReceiptBo.AllocationBo();
        allocation.setArApId(originalAr.getId());
        allocation.setAmount(source.getRefundAmount());
        bo.setAllocations(List.of(allocation));

        // 必须在新退款单头写入前取往来锁；期初当前读会扫描已确认收款头。
        openingGuard.lockAndCheckBusinessDate("AR", source.getCustomerId(), source.getBizDate());
        FinReceipt head = new FinReceipt();
        head.setNo(sequenceService.nextNo("SK"));
        saveHead(head, bo, true);
        saveAllocations(head.getId(), bo);
        confirmInternal(head, source.getSaleId());
        return head.getId();
    }

    private void validatePendingRefund(FinSaleReturnRefundSource source) {
        if (source == null || !"DRAFT".equals(source.getStatus())
            || !Integer.valueOf(1).equals(source.getNeedRefund())
            || source.getRefundReceiptId() != null) {
            throw new ServiceException("退货单未处于可退款草稿状态", 40902);
        }
        if (source.getSaleId() == null || source.getCustomerId() == null
            || source.getRefundAccountId() == null || source.getRefundAmount() == null
            || source.getRefundAmount().compareTo(BigDecimal.ZERO) <= 0
            || source.getTotalAmount() == null
            || source.getRefundAmount().compareTo(source.getTotalAmount()) > 0) {
            throw new ServiceException("退款账户或金额无效，退款不得超过退货总额", 40902);
        }
    }

    private void rejectExternalNegative(FinReceiptBo bo) {
        if (Boolean.TRUE.equals(bo.getNegative())) {
            throw new ServiceException("普通收款接口不允许创建红字退款单", 40902);
        }
    }

    private boolean isInternalRefund(FinReceipt head) {
        return Integer.valueOf(1).equals(head.getNegative());
    }

    private FinReceipt lockOrdinaryReceipt(Long id) {
        return lockOrdinaryReceipt(id, null);
    }

    private FinReceipt lockOrdinaryReceipt(Long id, Long nextCustomerId) {
        FinReceipt identity = receiptMapper.selectById(id);
        if (identity == null) {
            throw new ServiceException("收款单不存在");
        }
        if (!isInternalRefund(identity)) {
            if (identity.getCustomerId() == null) {
                throw new ServiceException("收款往来身份缺失，请先核对原单", 40902);
            }
            // 编辑允许更换客户；同时锁旧/新往来，固定 ID 升序，不能先持单头再等往来锁。
            java.util.stream.Stream.of(identity.getCustomerId(), nextCustomerId).filter(Objects::nonNull)
                .distinct().sorted().forEach(partyId -> openingGuard.lockParty("AR", partyId));
        }
        FinReceipt head = receiptMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("收款单不存在");
        }
        if (isInternalRefund(head)) {
            throw new ServiceException("红字退款单不可在收款单中修改，请通过关联销售退货处理", 40902);
        }
        if (!Objects.equals(identity.getCustomerId(), head.getCustomerId())) {
            throw new ServiceException("收款往来已变更，请刷新后重试", 40902);
        }
        return head;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FinReceiptVo update(Long id, FinReceiptBo bo) {
        rejectExternalNegative(bo);
        FinReceipt head = lockOrdinaryReceipt(id, bo.getCustomerId());
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可编辑", 40902);
        }
        saveHead(head, bo, false);
        saveAllocations(head.getId(), bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head, null);
        }
        return selectById(id);
    }

    private void saveHead(FinReceipt head, FinReceiptBo bo, boolean refund) {
        head.setCustomerId(bo.getCustomerId());
        head.setAmount(bo.getAmount().setScale(2, RoundingMode.HALF_UP));
        head.setBizDate(bo.getBizDate());
        // DOC/25 §3.6：新增必须选账户；存量草稿编辑（id 非空）不强制回填
        if (head.getId() == null && bo.getAccountId() == null) {
            throw new ServiceException("请选择结算账户");
        }
        head.setNegative(refund ? 1 : 0);
        applyAccount(head, bo.getAccountId(), bo.getAccount());
        head.setRemark(bo.getRemark());
        head.setStatus("DRAFT");
        if (head.getId() == null) {
            receiptMapper.insert(head);
        } else {
            requireOneRow(receiptMapper.update(head, new UpdateWrapper<FinReceipt>()
                .eq("id", head.getId()).eq("status", "DRAFT")));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        FinReceipt head = lockOrdinaryReceipt(id);
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        confirmInternal(head, null);
    }

    private List<FinReceiptAlloc> validatedAllocations(FinReceipt head) {
        // 稳定往来锁 -> 单据头 -> 台账；保留既有按 id 核销的金额和行顺序。
        List<FinReceiptAlloc> rows = allocMapper.selectList(new LambdaQueryWrapper<FinReceiptAlloc>()
            .eq(FinReceiptAlloc::getReceiptId, head.getId())
            .orderByAsc(FinReceiptAlloc::getArApId, FinReceiptAlloc::getId)
            .last("for update"));
        BigDecimal sum = rows.stream().map(FinReceiptAlloc::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (rows.isEmpty() || sum.compareTo(head.getAmount()) != 0) {
            throw new ServiceException("核销明细合计(" + sum + ")必须等于收款金额(" + head.getAmount() + ")");
        }
        return rows;
    }

    private void confirmInternal(FinReceipt head, Long refundSaleId) {
        openingGuard.lockAndCheckBusinessDate("AR", head.getCustomerId(), head.getBizDate());
        for (FinReceiptAlloc row : validatedAllocations(head)) {
            if (refundSaleId == null) {
                arApService.settle(row.getArApId(), row.getAmount(), "AR", head.getCustomerId());
            } else {
                arApService.unsettleSaleReturnRefund(row.getArApId(), row.getAmount(), head.getCustomerId(), refundSaleId);
            }
        }
        head.setConfirmedAt(new Date());
        head.setConfirmedBy(currentUserId());
        requireOneRow(receiptMapper.update(null, new UpdateWrapper<FinReceipt>()
            .eq("id", head.getId()).eq("status", "DRAFT")
            .set("status", "CONFIRMED")
            .set("confirmed_at", head.getConfirmedAt())
            .set("confirmed_by", head.getConfirmedBy())));
        head.setStatus("CONFIRMED");
    }

    private void saveAllocations(Long receiptId, FinReceiptBo bo) {
        allocMapper.delete(new LambdaQueryWrapper<FinReceiptAlloc>()
            .eq(FinReceiptAlloc::getReceiptId, receiptId));
        for (FinReceiptBo.AllocationBo alloc : bo.getAllocations()) {
            FinReceiptAlloc row = new FinReceiptAlloc();
            row.setReceiptId(receiptId);
            row.setArApId(alloc.getArApId());
            row.setAmount(alloc.getAmount().setScale(2, RoundingMode.HALF_UP));
            allocMapper.insert(row);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        withdrawOrdinary(lockOrdinaryReceipt(id));
    }

    private void withdrawOrdinary(FinReceipt head) {
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("仅已确认单据可撤回", 40902);
        }
        for (FinReceiptAlloc row : validatedAllocations(head)) {
            arApService.unsettle(row.getArApId(), row.getAmount(), "AR", head.getCustomerId());
        }
        // 保留草稿明细，用户可重新确认。
        setWithdrawalStatus(head, "DRAFT");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void withdrawSaleReturnRefund(Long returnId) {
        ConfirmPermission.check("erp:finance:receipt:withdraw", true);
        FinSaleReturnRefundSource source = receiptMapper.lockRefundSource(returnId);
        if (source == null || !"CONFIRMED".equals(source.getStatus()) || source.getRefundReceiptId() == null
            || source.getSaleId() == null || source.getCustomerId() == null || source.getRefundAmount() == null
            || !Integer.valueOf(1).equals(source.getNeedRefund())
            || source.getRefundAmount().signum() <= 0) {
            throw new ServiceException("退货单没有可撤回的已确认退款关联，请先核对原单", 40902);
        }
        openingGuard.lockParty("AR", source.getCustomerId());
        FinReceipt head = receiptMapper.lockById(source.getRefundReceiptId());
        if (head == null || !isInternalRefund(head) || !"CONFIRMED".equals(head.getStatus())
            || !source.getCustomerId().equals(head.getCustomerId())
            || source.getRefundAmount().compareTo(head.getAmount()) != 0
            || (source.getRefundAccountId() != null && !source.getRefundAccountId().equals(head.getAccountId()))) {
            throw new ServiceException("关联退款单状态、归属或金额不匹配，请先核对原单", 40902);
        }
        List<FinReceiptAlloc> rows = validatedAllocations(head);
        if (rows.size() != 1) {
            throw new ServiceException("关联退款未对应唯一原单台账，请先核对原单", 40902);
        }
        FinReceiptAlloc allocation = rows.get(0);
        arApService.settleSaleReturnRefund(allocation.getArApId(), allocation.getAmount(), head.getCustomerId(), source.getSaleId());
        // 退货撤回后链接清空，红字单作终态保留，不能作为无来源草稿重新确认。
        setWithdrawalStatus(head, "VOID");
    }

    private void setWithdrawalStatus(FinReceipt head, String nextStatus) {
        head.setWithdrawnAt(new Date());
        head.setWithdrawnBy(currentUserId());
        UpdateWrapper<FinReceipt> change = new UpdateWrapper<FinReceipt>()
            .eq("id", head.getId()).eq("status", "CONFIRMED")
            .set("status", nextStatus)
            .set("withdrawn_at", head.getWithdrawnAt())
            .set("withdrawn_by", head.getWithdrawnBy());
        if ("VOID".equals(nextStatus)) {
            change.set("voided_at", head.getWithdrawnAt()).set("voided_by", head.getWithdrawnBy());
        }
        requireOneRow(receiptMapper.update(null, change));
        head.setStatus(nextStatus);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id) {
        FinReceipt head = lockOrdinaryReceipt(id);
        if ("VOID".equals(head.getStatus())) {
            throw new ServiceException("单据已作废");
        }
        // DRAFT 草稿不再走作废：草稿应走删除（逻辑删除），作废会把草稿固化成终态单据并占用唯一键
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("草稿单据请使用删除", 40902);
        }
        // 复用已锁定的单头和反核销逻辑，不再用一致性读重取单头。
        withdrawOrdinary(head);
        allocMapper.delete(new LambdaQueryWrapper<FinReceiptAlloc>()
            .eq(FinReceiptAlloc::getReceiptId, id));
        head.setVoidedAt(new Date());
        head.setVoidedBy(currentUserId());
        // 状态 CAS：撤回后必定落在 DRAFT
        int rows = receiptMapper.update(null, new UpdateWrapper<FinReceipt>()
            .eq("id", head.getId()).eq("status", "DRAFT")
            .set("status", "VOID")
            .set("voided_at", head.getVoidedAt())
            .set("voided_by", head.getVoidedBy()));
        if (rows == 0) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
        head.setStatus("VOID");
    }

    /**
     * 账户引用写入（DOC/25 §3.6）：accountId 非空时写 account_id 并回填账户名称到 account 展示列；
     * 为空（存量草稿）时保留原 account 文本不动。
     */
    private void applyAccount(FinReceipt head, Long accountId, String accountText) {
        if (accountId != null) {
            org.erp.fin.domain.FinAccount acc = accountMapper.selectById(accountId);
            if (acc == null) {
                throw new ServiceException("结算账户不存在：" + accountId);
            }
            if (isInternalRefund(head) && !Integer.valueOf(1).equals(acc.getStatus())) {
                throw new ServiceException("退款结算账户已停用，请重新选择", 40902);
            }
            head.setAccountId(accountId);
            head.setAccount(acc.getName());
        } else if (head.getAccountId() == null && accountText != null) {
            head.setAccount(accountText);
        }
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除草稿收款单（逻辑删除）。
     *
     * 规则：
     * - 仅 DRAFT 可删，CONFIRMED/VOID 一律 40902 拒绝；
     * - 逻辑删除（del_flag=1），主表与核销明细一并逻辑删除，不物理 DELETE；
     * - 单号不回收（uk_fin_receipt_no 含已删行，新建同名单号会被唯一键挡住）；
     * - 草稿未核销过台账（核销只在确认时发生），无需反核销。
     *
     * @param id 收款单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        FinReceipt head = lockOrdinaryReceipt(id);
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿单据可删除，已确认/已作废单据请先撤回或作废处理", 40902);
        }
        // 明细一并逻辑删除，避免残留核销行影响后续查询
        allocMapper.delete(new LambdaQueryWrapper<FinReceiptAlloc>()
            .eq(FinReceiptAlloc::getReceiptId, id));
        requireOneRow(receiptMapper.delete(new LambdaQueryWrapper<FinReceipt>()
            .eq(FinReceipt::getId, id).eq(FinReceipt::getStatus, "DRAFT")));
    }

    private static void requireOneRow(int rows) {
        if (rows != 1) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
    }
}
