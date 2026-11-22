package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.erp.common.enums.StatusText;
import org.erp.common.sequence.SequenceService;
import org.erp.fin.domain.FinOtherFlow;
import org.erp.fin.domain.bo.FinOtherFlowBo;
import org.erp.fin.domain.vo.FinOtherFlowVo;
import org.erp.fin.mapper.FinOtherFlowMapper;
import org.erp.fin.service.IFinCostLedgerService;
import org.erp.fin.service.IFinOtherFlowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * 其他收支服务实现
 *
 * 规则（DOC/02 S7/R19/R20、DOC/12 §8）：
 * - 独立于应收应付，不冲抵台账；
 * - 支出 affect_cost=1：确认写 fin_cost_ledger(OTHER_EXPENSE, source=OTHER_FLOW)，
 *   撤回置 REVERSED；收入/affect_cost=0 不写台账；
 * - affect_cost 仅支出有效（收入强制 0）。
 */
@RequiredArgsConstructor
@Service
public class FinOtherFlowServiceImpl implements IFinOtherFlowService {

    private final FinOtherFlowMapper flowMapper;
    private final org.erp.fin.mapper.FinAccountMapper accountMapper;
    private final IFinCostLedgerService costLedgerService;
    private final SequenceService sequenceService;

    @Override
    public TableDataInfo<FinOtherFlowVo> selectPage(String flowType, String category, String status,
                                                    PageQuery pageQuery) {
        LambdaQueryWrapper<FinOtherFlow> lqw = new LambdaQueryWrapper<>();
        lqw.eq(flowType != null && !flowType.isBlank(), FinOtherFlow::getFlowType, flowType);
        lqw.eq(category != null && !category.isBlank(), FinOtherFlow::getCategory, category);
        lqw.eq(status != null && !status.isBlank(), FinOtherFlow::getStatus, status);
        lqw.orderByDesc(FinOtherFlow::getId);
        Page<FinOtherFlow> page = flowMapper.selectPage(pageQuery.build(), lqw);
        Page<FinOtherFlowVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public FinOtherFlowVo selectById(Long id) {
        FinOtherFlow head = flowMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("其他收支单不存在");
        }
        return toVo(head);
    }

    private FinOtherFlowVo toVo(FinOtherFlow head) {
        FinOtherFlowVo vo = new FinOtherFlowVo();
        vo.setId(head.getId());
        vo.setNo(head.getNo());
        vo.setFlowType(head.getFlowType());
        vo.setCategory(head.getCategory());
        vo.setAmount(head.getAmount());
        vo.setBizDate(head.getBizDate());
        vo.setAccount(head.getAccount());
        vo.setAccountId(head.getAccountId());
        vo.setAffectCost(head.getAffectCost());
        vo.setRemark(head.getRemark());
        vo.setStatus(head.getStatus());
        vo.setStatusText(StatusText.bill(head.getStatus()));
        vo.setCreateTime(head.getCreateTime());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FinOtherFlowVo insert(FinOtherFlowBo bo) {
        FinOtherFlow head = new FinOtherFlow();
        head.setNo(sequenceService.nextNo("INCOME".equals(bo.getFlowType()) ? "SR" : "ZC"));
        saveHead(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(head.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FinOtherFlowVo update(Long id, FinOtherFlowBo bo) {
        FinOtherFlow head = lockBill(id);
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可编辑", 40902);
        }
        saveHead(head, bo);
        if (Boolean.TRUE.equals(bo.getConfirm())) {
            confirmInternal(head);
        }
        return selectById(id);
    }

    private void saveHead(FinOtherFlow head, FinOtherFlowBo bo) {
        head.setFlowType(bo.getFlowType());
        head.setCategory(bo.getCategory() == null || bo.getCategory().isBlank() ? "OTHER" : bo.getCategory());
        head.setAmount(bo.getAmount().setScale(2, RoundingMode.HALF_UP));
        head.setBizDate(bo.getBizDate());
        // DOC/25 §3.6：新增必须选账户；存量草稿编辑（id 非空）不强制回填
        if (head.getId() == null && bo.getAccountId() == null) {
            throw new ServiceException("请选择结算账户");
        }
        applyAccount(head, bo.getAccountId(), bo.getAccount());
        // 收入不计入综合成本（R19：支出才可选）
        head.setAffectCost("EXPENSE".equals(bo.getFlowType()) && Integer.valueOf(1).equals(bo.getAffectCost()) ? 1 : 0);
        head.setRemark(bo.getRemark());
        head.setStatus("DRAFT");
        if (head.getId() == null) {
            flowMapper.insert(head);
        } else {
            requireOneRow(flowMapper.update(head, statusOf(head.getId(), "DRAFT")));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        FinOtherFlow head = lockBill(id);
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿可确认", 40902);
        }
        confirmInternal(head);
    }

    private void confirmInternal(FinOtherFlow head) {
        if (head.getAffectCost() != null && head.getAffectCost() == 1) {
            costLedgerService.record("OTHER_EXPENSE", "OTHER_FLOW", head.getId(), head.getNo(),
                head.getBizDate(), head.getAmount(), head.getRemark());
        }
        head.setStatus("CONFIRMED");
        head.setConfirmedAt(new Date());
        head.setConfirmedBy(currentUserId());
        requireOneRow(flowMapper.update(head, statusOf(head.getId(), "DRAFT")));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        FinOtherFlow head = lockBill(id);
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("仅已确认单据可撤回", 40902);
        }
        withdrawLocked(head);
    }

    private void withdrawLocked(FinOtherFlow head) {
        costLedgerService.reverseBySource("OTHER_FLOW", head.getId());
        head.setStatus("DRAFT");
        head.setWithdrawnAt(new Date());
        head.setWithdrawnBy(currentUserId());
        requireOneRow(flowMapper.update(head, statusOf(head.getId(), "CONFIRMED")));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBill(Long id) {
        FinOtherFlow head = lockBill(id);
        if ("VOID".equals(head.getStatus())) {
            throw new ServiceException("单据已作废");
        }
        if (!"CONFIRMED".equals(head.getStatus())) {
            throw new ServiceException("草稿单据请使用删除", 40902);
        }
        withdrawLocked(head);
        head.setStatus("VOID");
        head.setVoidedAt(new Date());
        head.setVoidedBy(currentUserId());
        requireOneRow(flowMapper.update(head, statusOf(id, "DRAFT")));
    }

    /**
     * 账户引用写入（DOC/25 §3.6）：accountId 非空时写 account_id 并回填账户名称到 account 展示列；
     * 为空（存量草稿）时保留原 account 文本不动。
     */
    private void applyAccount(FinOtherFlow head, Long accountId, String accountText) {
        if (accountId != null) {
            org.erp.fin.domain.FinAccount acc = accountMapper.selectById(accountId);
            if (acc == null) {
                throw new ServiceException("结算账户不存在：" + accountId);
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
     * 删除草稿其他收支单（逻辑删除）。
     *
     * 规则：
     * - 仅 DRAFT 可删，CONFIRMED/VOID 一律 40902 拒绝；
     * - 逻辑删除（del_flag=1），不物理 DELETE；
     * - 单号不回收（uk_fin_other_flow_no 含已删行，新建同名单号会被唯一键挡住）；
     * - 成本台账只在确认时写入，草稿无 fin_cost_ledger 残留，无需冲销。
     *
     * @param id 其他收支单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        FinOtherFlow head = lockBill(id);
        if (!"DRAFT".equals(head.getStatus())) {
            throw new ServiceException("仅草稿单据可删除，已确认/已作废单据请先撤回或作废处理", 40902);
        }
        requireOneRow(flowMapper.delete(new LambdaQueryWrapper<FinOtherFlow>()
            .eq(FinOtherFlow::getId, id).eq(FinOtherFlow::getStatus, "DRAFT")));
    }

    private FinOtherFlow lockBill(Long id) {
        FinOtherFlow head = flowMapper.lockById(id);
        if (head == null) {
            throw new ServiceException("其他收支单不存在");
        }
        return head;
    }

    private static LambdaUpdateWrapper<FinOtherFlow> statusOf(Long id, String status) {
        return new LambdaUpdateWrapper<FinOtherFlow>().eq(FinOtherFlow::getId, id).eq(FinOtherFlow::getStatus, status);
    }

    private static void requireOneRow(int rows) {
        if (rows != 1) {
            throw new ServiceException("单据状态已变更，请刷新后重试", 40902);
        }
    }
}
