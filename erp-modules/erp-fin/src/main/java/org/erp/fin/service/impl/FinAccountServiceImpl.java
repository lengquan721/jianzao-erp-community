package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.fin.domain.FinAccount;
import org.erp.fin.domain.FinAccountMovement;
import org.erp.fin.domain.FinOtherFlow;
import org.erp.fin.domain.bo.FinAccountBo;
import org.erp.fin.domain.vo.FinAccountFlowVo;
import org.erp.fin.domain.vo.FinAccountVo;
import org.erp.fin.mapper.FinAccountMapper;
import org.erp.fin.mapper.FinOtherFlowMapper;
import org.erp.fin.mapper.FinPaymentMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.erp.fin.service.IFinAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算账户服务实现（DOC/25 §3.4/§3.5）
 *
 * 规则：
 * - 余额 = 期初 + Σ收款 − Σ付款 + Σ其他收入 − Σ其他支出（仅 account_id 命中且 CONFIRMED），实时计算不落表；
 * - code 不可改；期初余额仅无动账时可改；余额≠0 拒绝停用；
 * - 删除（逻辑删）：名下存在任意状态（含 DRAFT）收付款/其他收支单或余额≠0 时拒绝；
 * - 流水：SQL UNION ALL 计数和真分页，按 biz_date、id 倒序，跨表同键沿用收/付/其他的稳定顺序。
 */
@RequiredArgsConstructor
@Service
public class FinAccountServiceImpl implements IFinAccountService {

    private final FinAccountMapper accountMapper;
    private final FinReceiptMapper receiptMapper;
    private final FinPaymentMapper paymentMapper;
    private final FinOtherFlowMapper otherFlowMapper;

    @Override
    public TableDataInfo<FinAccountVo> selectPage(String keyword, String type, Integer status, PageQuery pageQuery) {
        LambdaQueryWrapper<FinAccount> lqw = new LambdaQueryWrapper<>();
        lqw.eq(type != null && !type.isBlank(), FinAccount::getType, type);
        lqw.eq(status != null, FinAccount::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            lqw.and(w -> w.like(FinAccount::getCode, keyword).or().like(FinAccount::getName, keyword));
        }
        lqw.orderByAsc(FinAccount::getCode);
        Page<FinAccount> page = accountMapper.selectPage(pageQuery.build(), lqw);
        Page<FinAccountVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<FinAccount> records = page.getRecords();
        Map<Long, BigDecimal> movements = new HashMap<>();
        if (!records.isEmpty()) {
            // 账户实体来自租户受限的当前页，显式把同一租户和本页 ID 集合下推每个聚合分支。
            List<Long> ids = records.stream().map(FinAccount::getId).toList();
            for (FinAccountMovement row : accountMapper.selectMovements(records.get(0).getTenantId(), ids)) {
                movements.put(row.getAccountId(), row.getMovement() == null ? BigDecimal.ZERO : row.getMovement());
            }
        }
        voPage.setRecords(records.stream().map(r -> {
            FinAccountVo vo = toVo(r, false);
            BigDecimal opening = r.getOpeningBalance() == null ? BigDecimal.ZERO : r.getOpeningBalance();
            vo.setBalance(opening.add(movements.getOrDefault(r.getId(), BigDecimal.ZERO))
                .setScale(2, RoundingMode.HALF_UP));
            return vo;
        }).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public FinAccountVo selectById(Long id) {
        FinAccount head = accountMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("结算账户不存在");
        }
        return toVo(head, true);
    }

    private FinAccountVo toVo(FinAccount head, boolean withBalance) {
        FinAccountVo vo = new FinAccountVo();
        vo.setId(head.getId());
        vo.setCode(head.getCode());
        vo.setName(head.getName());
        vo.setType(head.getType());
        vo.setOpeningBalance(head.getOpeningBalance());
        vo.setStatus(head.getStatus());
        vo.setRemark(head.getRemark());
        vo.setCreateTime(head.getCreateTime());
        if (withBalance) {
            vo.setBalance(balance(head.getId()));
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insert(FinAccountBo bo) {
        // 同码已删档：复用该行重建，避免 uk(tenant_id, code) 含已删行导致"删了建不回"（ErpProductMapper 先例）
        Long deletedId = accountMapper.selectDeletedIdByCode(bo.getCode());
        if (deletedId != null) {
            throw new ServiceException("编码已被已删除账户占用：" + bo.getCode() + "，请更换编码", 40903);
        }
        checkCodeUnique(null, bo.getCode());
        FinAccount head = new FinAccount();
        head.setCode(bo.getCode());
        head.setName(bo.getName());
        head.setType(bo.getType());
        head.setOpeningBalance(bo.getOpeningBalance() == null ? BigDecimal.ZERO
            : bo.getOpeningBalance().setScale(2, RoundingMode.HALF_UP));
        head.setStatus(bo.getStatus() == null ? 1 : bo.getStatus());
        head.setRemark(bo.getRemark());
        accountMapper.insert(head);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(FinAccountBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("账户ID不能为空");
        }
        FinAccount head = accountMapper.selectById(bo.getId());
        if (head == null) {
            throw new ServiceException("结算账户不存在");
        }
        if (bo.getCode() != null && !bo.getCode().equals(head.getCode())) {
            throw new ServiceException("账户编码不可修改", 40902);
        }
        boolean hasMovement = hasAnyBill(head.getId());
        // 期初余额仅无动账时可改（有动账后期初语义已锚定）
        if (bo.getOpeningBalance() != null
            && bo.getOpeningBalance().setScale(2, RoundingMode.HALF_UP).compareTo(head.getOpeningBalance()) != 0
            && hasMovement) {
            throw new ServiceException("账户已发生收支动账，期初余额不可修改", 40902);
        }
        // 余额≠0 拒绝停用（欠款账户停用后动账不可见）
        if (bo.getStatus() != null && bo.getStatus() == 0 && balance(head.getId()).compareTo(BigDecimal.ZERO) != 0) {
            throw new ServiceException("账户余额不为零，不允许停用", 40902);
        }
        head.setName(bo.getName());
        head.setType(bo.getType());
        if (bo.getOpeningBalance() != null) {
            head.setOpeningBalance(bo.getOpeningBalance().setScale(2, RoundingMode.HALF_UP));
        }
        if (bo.getStatus() != null) {
            head.setStatus(bo.getStatus());
        }
        head.setRemark(bo.getRemark());
        accountMapper.updateById(head);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Long id) {
        FinAccount head = accountMapper.selectById(id);
        if (head == null) {
            throw new ServiceException("结算账户不存在");
        }
        // 引用校验含 DRAFT：放任"建草稿→删户→确认草稿"会把钱记入已删除、列表不可见的账户
        if (hasAnyBill(id)) {
            throw new ServiceException("账户已被收付款/其他收支单据引用，不允许删除", 40902);
        }
        if (balance(id).compareTo(BigDecimal.ZERO) != 0) {
            throw new ServiceException("账户余额不为零，不允许删除", 40902);
        }
        accountMapper.deleteById(id);
    }

    @Override
    public List<FinAccountVo> options() {
        List<FinAccount> rows = accountMapper.selectList(new LambdaQueryWrapper<FinAccount>()
            .select(FinAccount::getId, FinAccount::getCode, FinAccount::getName, FinAccount::getType, FinAccount::getStatus)
            .eq(FinAccount::getStatus, 1)
            .orderByAsc(FinAccount::getCode));
        // OR 扩权仅供下拉：不用详情投影，绝不带余额/期初/备注/创建时间等财务元数据。
        return rows.stream().map(row -> {
            FinAccountVo vo = new FinAccountVo();
            vo.setId(row.getId());
            vo.setCode(row.getCode());
            vo.setName(row.getName());
            vo.setType(row.getType());
            vo.setStatus(row.getStatus());
            return vo;
        }).toList();
    }

    @Override
    public TableDataInfo<FinAccountFlowVo> flows(Long accountId, PageQuery pageQuery) {
        FinAccount head = accountMapper.selectById(accountId);
        if (head == null) {
            throw new ServiceException("结算账户不存在");
        }
        // 固定业务排序不接收外部排序列；复用公共分页参数的默认值、页码归一化和大小上限。
        Page<FinAccountFlowVo> page = new PageQuery(pageQuery.getPageSize(), pageQuery.getPageNum()).build();
        long total = accountMapper.countFlows(head.getTenantId(), accountId);
        page.setTotal(total);
        long offset = (page.getCurrent() - 1L) * page.getSize();
        // 空集合/超过最后一页保留真实 total，不做名称连接，也不因 overflow=true 回到第一页。
        page.setRecords(offset >= total ? List.of()
            : accountMapper.selectFlowPage(head.getTenantId(), accountId, offset, page.getSize()));
        return TableDataInfo.build(page);
    }

    /**
     * 实时余额 = 期初 + Σ普通收款 − Σ退货退款 − Σ付款 + Σ其他收入 − Σ其他支出。
     */
    private BigDecimal balance(Long accountId) {
        BigDecimal receipt = receiptMapper.selectList(new LambdaQueryWrapper<org.erp.fin.domain.FinReceipt>()
                .select(org.erp.fin.domain.FinReceipt::getAmount, org.erp.fin.domain.FinReceipt::getNegative)
                .eq(org.erp.fin.domain.FinReceipt::getAccountId, accountId)
                .eq(org.erp.fin.domain.FinReceipt::getStatus, "CONFIRMED"))
            .stream().map(r -> {
                BigDecimal amount = r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
                return Integer.valueOf(1).equals(r.getNegative()) ? amount.negate() : amount;
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payment = paymentMapper.selectList(new LambdaQueryWrapper<org.erp.fin.domain.FinPayment>()
                .select(org.erp.fin.domain.FinPayment::getAmount)
                .eq(org.erp.fin.domain.FinPayment::getAccountId, accountId)
                .eq(org.erp.fin.domain.FinPayment::getStatus, "CONFIRMED"))
            .stream().map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<FinOtherFlow> flows = otherFlowMapper.selectList(new LambdaQueryWrapper<FinOtherFlow>()
            .select(FinOtherFlow::getAmount, FinOtherFlow::getFlowType)
            .eq(FinOtherFlow::getAccountId, accountId)
            .eq(FinOtherFlow::getStatus, "CONFIRMED"));
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (FinOtherFlow f : flows) {
            BigDecimal amt = f.getAmount() == null ? BigDecimal.ZERO : f.getAmount();
            if ("INCOME".equals(f.getFlowType())) {
                income = income.add(amt);
            } else {
                expense = expense.add(amt);
            }
        }
        FinAccount head = accountMapper.selectById(accountId);
        BigDecimal opening = head == null || head.getOpeningBalance() == null
            ? BigDecimal.ZERO : head.getOpeningBalance();
        return opening.add(receipt).subtract(payment).add(income).subtract(expense)
            .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 名下是否存在任意状态（含草稿）的收付款/其他收支单。
     */
    private boolean hasAnyBill(Long accountId) {
        Long n1 = receiptMapper.selectCount(new LambdaQueryWrapper<org.erp.fin.domain.FinReceipt>()
            .eq(org.erp.fin.domain.FinReceipt::getAccountId, accountId));
        Long n2 = paymentMapper.selectCount(new LambdaQueryWrapper<org.erp.fin.domain.FinPayment>()
            .eq(org.erp.fin.domain.FinPayment::getAccountId, accountId));
        Long n3 = otherFlowMapper.selectCount(new LambdaQueryWrapper<FinOtherFlow>()
            .eq(FinOtherFlow::getAccountId, accountId));
        return (n1 != null && n1 > 0) || (n2 != null && n2 > 0) || (n3 != null && n3 > 0);
    }

    private void checkCodeUnique(Long id, String code) {
        Long count = accountMapper.selectCount(new LambdaQueryWrapper<FinAccount>()
            .eq(FinAccount::getCode, code)
            .ne(id != null, FinAccount::getId, id));
        if (count != null && count > 0) {
            throw new ServiceException("账户编码已存在：" + code, 40903);
        }
    }
}
