package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.common.enums.StatusText;
import org.erp.fin.domain.FinArAp;
import org.erp.fin.domain.FinSourceNo;
import org.erp.fin.domain.vo.FinArApSummaryVo;
import org.erp.fin.domain.vo.FinArApSummaryPageVo;
import org.erp.fin.domain.vo.FinArApVo;
import org.erp.fin.domain.vo.FinArApSourceVo;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.service.IFinOpeningGuardService;
import java.util.ArrayList;
import java.util.Comparator;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.fin.mapper.FinSourceNoMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 应收应付台账服务实现
 *
 * 规则（DOC/11 §5.5、DOC/02 D1/R7/R16、DOC/12 错误码表）：
 * - 确认销售→AR、确认采购→AP（金额=total_amount 含运杂费）、期初确认→OPENING；
 * - 撤回/作废：settled_amount=0 才能冲销（VOID），否则 40904；无台账幂等成功；
 * - 汇总（S8）：全量数据库聚合总额 + 已结 + 未结，期初仅取台账 OPENING；往来单位分组在数据库分页。
 */
@RequiredArgsConstructor
@Service
public class FinArApServiceImpl implements IFinArApService {

    private static final String PARTY_AR = "AR";
    private static final String PARTY_AP = "AP";
    private static final String STATUS_VOID = "VOID";
    private static final String STATUS_SETTLED = "SETTLED";
    private static final String SOURCE_OPENING = "OPENING";
    private static final String SOURCE_SALE = "SALE";
    private static final String SOURCE_PURCHASE = "PURCHASE";

    private final FinArApMapper arApMapper;
    private final FinSourceNoMapper sourceNoMapper;
    private final ErpCustomerMapper customerMapper;
    private final ErpSupplierMapper supplierMapper;
    private final FinOpeningMapper openingMapper;
    private final IFinOpeningGuardService openingGuard;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createBySale(Long saleId, String saleNo, Long customerId, BigDecimal amount, Date bizDate) {
        create(PARTY_AR, customerId, "SALE", saleId, amount, bizDate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createByPurchase(Long purchaseId, String purchaseNo, Long supplierId, BigDecimal amount, Date bizDate) {
        create(PARTY_AP, supplierId, "PURCHASE", purchaseId, amount, bizDate);
    }

    private void create(String partyType, Long partyId, String sourceType, Long sourceId,
                        BigDecimal amount, Date bizDate) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ServiceException("应收应付金额不能为负");
        }
        if (!SOURCE_OPENING.equals(sourceType)) {
            openingGuard.lockAndCheckBusinessDate(partyType, partyId, bizDate);
        }
        Long exists = arApMapper.selectCount(new LambdaQueryWrapper<FinArAp>()
            .eq(FinArAp::getSourceType, sourceType)
            .eq(FinArAp::getSourceId, sourceId)
            .ne(FinArAp::getStatus, STATUS_VOID));
        if (exists != null && exists > 0) {
            throw new ServiceException("该单据已生成应收应付台账", 40902);
        }
        FinArAp row = new FinArAp();
        row.setPartyType(partyType);
        row.setPartyId(partyId);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setBizDate(bizDate);
        row.setAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
        row.setSettledAmount(BigDecimal.ZERO);
        row.setStatus("OPEN");
        try {
            arApMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发确认时两个事务都通过 selectCount 判重，靠 uk_fin_ar_ap_source 兜底并转 40902（§6.8.2 防线 5）
            throw new ServiceException("该单据已生成应收应付台账", 40902);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createBySaleReturn(Long returnId, String returnNo, Long customerId, BigDecimal amount, Date bizDate) {
        createNegative(PARTY_AR, customerId, "SALE_RETURN", returnId, amount, bizDate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createByPurchaseReturn(Long returnId, String returnNo, Long supplierId, BigDecimal amount, Date bizDate) {
        createNegative(PARTY_AP, supplierId, "PURCHASE_RETURN", returnId, amount, bizDate);
    }

    /**
     * 退货负数台账行：与 create() 相同的 source 判重，但放开负数校验（DOC/24 §0「应收/应付红冲落点」）。
     * 负数行 settled 恒为 0（红字收款单对原 AR 行做 unsettle，不对负数行核销）。
     */
    private void createNegative(String partyType, Long partyId, String sourceType, Long sourceId,
                                BigDecimal amount, Date bizDate) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) >= 0) {
            throw new ServiceException("退货台账金额必须为负数");
        }
        if (!SOURCE_OPENING.equals(sourceType)) {
            openingGuard.lockAndCheckBusinessDate(partyType, partyId, bizDate);
        }
        Long exists = arApMapper.selectCount(new LambdaQueryWrapper<FinArAp>()
            .eq(FinArAp::getSourceType, sourceType)
            .eq(FinArAp::getSourceId, sourceId)
            .ne(FinArAp::getStatus, STATUS_VOID));
        if (exists != null && exists > 0) {
            throw new ServiceException("该退货单已生成红冲台账", 40902);
        }
        FinArAp row = new FinArAp();
        row.setPartyType(partyType);
        row.setPartyId(partyId);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setBizDate(bizDate);
        row.setAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
        row.setSettledAmount(BigDecimal.ZERO);
        row.setStatus("OPEN");
        try {
            arApMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 退货确认并发时同样靠唯一键兜底，转 40902 而不是通用 409（§6.8.2 防线 5）
            throw new ServiceException("该退货单已生成红冲台账", 40902);
        }
    }

    @Override
    public FinArApVo getBySource(String sourceType, Long sourceId) {
        FinArAp row = arApMapper.selectOne(new LambdaQueryWrapper<FinArAp>()
            .eq(FinArAp::getSourceType, sourceType)
            .eq(FinArAp::getSourceId, sourceId)
            .ne(FinArAp::getStatus, STATUS_VOID)
            .orderByDesc(FinArAp::getId)
            .last("limit 1"));
        return row == null ? null : toVo(row, Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOpening(String partyType, Long partyId, BigDecimal amount, Long openingId, Date bizDate) {
        if (!PARTY_AR.equals(partyType) && !PARTY_AP.equals(partyType)) {
            throw new ServiceException("期初类型必须是 AR 或 AP");
        }
        if (bizDate == null) {
            throw new ServiceException("期初截止日不能为空", 400);
        }
        openingGuard.lockParty(partyType, partyId);
        // 期初可重录：先冲销旧期初（旧期初不允许已核销）
        List<FinArAp> oldOpenings = arApMapper.selectList(new LambdaQueryWrapper<FinArAp>()
            .eq(FinArAp::getPartyType, partyType)
            .eq(FinArAp::getPartyId, partyId)
            .eq(FinArAp::getSourceType, SOURCE_OPENING)
            .ne(FinArAp::getStatus, STATUS_VOID)
            .orderByAsc(FinArAp::getId).last("for update"));
        for (FinArAp old : oldOpenings) {
            FinArAp locked = arApMapper.selectByIdForUpdate(old.getId());
            if (locked == null || STATUS_VOID.equals(locked.getStatus())) {
                throw new ServiceException("期初台账状态已变更，请刷新后重试", 40902);
            }
            if (locked.getSettledAmount().compareTo(BigDecimal.ZERO) > 0) {
                throw new ServiceException("期初已发生核销，禁止重录", 40904);
            }
            locked.setStatus(STATUS_VOID);
            arApMapper.updateById(locked);
        }
        create(partyType, partyId, SOURCE_OPENING, openingId, amount, bizDate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidBySource(String sourceType, Long sourceId) {
        List<FinArAp> rows = arApMapper.selectList(new LambdaQueryWrapper<FinArAp>()
            .eq(FinArAp::getSourceType, sourceType)
            .eq(FinArAp::getSourceId, sourceId)
            .ne(FinArAp::getStatus, STATUS_VOID)
            .orderByAsc(FinArAp::getId).last("for update"));
        // 锁定后仅修改当前读到的实体，不能把旧快照的金额/核销值写回去。
        List<FinArAp> lockedRows = new ArrayList<>();
        for (FinArAp row : rows.stream().sorted(Comparator.comparing(FinArAp::getId)).toList()) {
            FinArAp locked = arApMapper.selectByIdForUpdate(row.getId());
            if (locked == null || STATUS_VOID.equals(locked.getStatus())) {
                continue;
            }
            if (locked.getSettledAmount().compareTo(BigDecimal.ZERO) > 0) {
                throw new ServiceException("已核销的应收应付需先反核销", 40904);
            }
            lockedRows.add(locked);
        }
        for (FinArAp row : lockedRows) {
            row.setStatus(STATUS_VOID);
            arApMapper.updateById(row);
        }
    }

    @Override
    public TableDataInfo<FinArApVo> selectPage(String partyType, Long partyId, String status, PageQuery pageQuery) {
        LambdaQueryWrapper<FinArAp> lqw = new LambdaQueryWrapper<>();
        lqw.eq(partyType != null && !partyType.isBlank(), FinArAp::getPartyType, partyType);
        lqw.eq(partyId != null, FinArAp::getPartyId, partyId);
        lqw.eq(status != null && !status.isBlank(), FinArAp::getStatus, status);
        lqw.orderByDesc(FinArAp::getId);
        Page<FinArAp> page = arApMapper.selectPage(pageQuery.build(), lqw);
        Page<FinArApVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(detailVos(page.getRecords()));
        return TableDataInfo.build(voPage);
    }

    /** 兼容列表/核销候选明确上限；取上限 + 1 哨兵而非静默截断。 */
    private static final int LEGACY_READ_LIMIT = 5000;

    @Override
    @Transactional(readOnly = true)
    public FinArApSummaryVo summary(String partyType) {
        validatePartyType(partyType);
        List<FinArApSummaryVo.PartySummary> parties = arApMapper.selectSummaryParties(partyType, 0, LEGACY_READ_LIMIT + 1);
        if (parties.size() > LEGACY_READ_LIMIT) {
            throw new ServiceException("旧汇总接口最多返回 5000 个往来单位，请使用 /summary/page 分页查询", 400);
        }
        FinArApSummaryVo vo = new FinArApSummaryVo();
        fillSummary(vo, partyType, parties);
        return vo;
    }

    @Override
    @Transactional(readOnly = true)
    public FinArApSummaryPageVo summaryPage(String partyType, int pageNum, int pageSize) {
        validatePartyType(partyType);
        long offset = readPageOffset(pageNum, pageSize);
        long total = arApMapper.countSummaryParties(partyType);
        List<FinArApSummaryVo.PartySummary> parties = offset >= total ? List.of()
            : arApMapper.selectSummaryParties(partyType, offset, pageSize);
        FinArApSummaryPageVo vo = new FinArApSummaryPageVo();
        // 合计必须单独对全部匹配台账聚合，空页/末页也不能改为当页合计或零。
        fillSummary(vo, partyType, parties);
        vo.setTotal(total);
        return vo;
    }

    private void fillSummary(FinArApSummaryVo vo, String partyType, List<FinArApSummaryVo.PartySummary> parties) {
        FinArApSummaryVo totals = arApMapper.selectSummaryTotals(partyType);
        vo.setPartyType(partyType);
        vo.setTotalAmount(totals.getTotalAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        vo.setSettledAmount(totals.getSettledAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        vo.setUnsettledAmount(totals.getUnsettledAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        vo.setOpeningAmount(totals.getOpeningAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        Map<Long, String> partyNames = loadPartyNames(partyType,
            parties.stream().map(FinArApSummaryVo.PartySummary::getPartyId).toList());
        parties.forEach(party -> party.setPartyName(partyNames.get(party.getPartyId())));
        vo.setParties(parties);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinArApVo> listByParty(String partyType, Long partyId) {
        validateParty(partyType, partyId);
        List<FinArAp> rows = arApMapper.selectByPartyPage(partyType, partyId, 0, LEGACY_READ_LIMIT + 1);
        if (rows.size() > LEGACY_READ_LIMIT) {
            throw new ServiceException("旧明细接口最多返回 5000 条，请使用分页明细", 400);
        }
        return detailVos(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinArApVo> listAllocatableByParty(String partyType, Long partyId) {
        validateParty(partyType, partyId);
        List<FinArAp> rows = arApMapper.selectAllocatableByParty(partyType, partyId, LEGACY_READ_LIMIT + 1);
        if (rows.size() > LEGACY_READ_LIMIT) {
            throw new ServiceException("可核销台账超过 5000 条，请联系管理员，未返回部分候选", 400);
        }
        return detailVos(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public TableDataInfo<FinArApVo> pageByParty(String partyType, Long partyId, int pageNum, int pageSize) {
        validateParty(partyType, partyId);
        long offset = readPageOffset(pageNum, pageSize);
        long total = arApMapper.countByParty(partyType, partyId);
        List<FinArAp> rows = offset >= total ? List.of()
            : arApMapper.selectByPartyPage(partyType, partyId, offset, pageSize);
        return new TableDataInfo<>(detailVos(rows), total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long countBusinessBefore(String partyType, Long partyId, Date before) {
        validateParty(partyType, partyId);
        if (before == null) {
            throw new ServiceException("期初重叠检查的日期边界不能为空", 400);
        }
        openingGuard.lockParty(partyType, partyId);
        // 先收付款头、后台账；与收付款修改/核销的方向一致。都是当前读。
        long settlements = PARTY_AR.equals(partyType)
            ? openingMapper.countReceiptsBeforeForUpdate(partyId, before)
            : openingMapper.countPaymentsBeforeForUpdate(partyId, before);
        return settlements + openingMapper.countLedgerBeforeForUpdate(partyType, partyId, before);
    }

    @Override
    public List<FinArApSourceVo> listSourceInfo(Collection<Long> arApIds) {
        if (arApIds == null || arApIds.isEmpty()) {
            return List.of();
        }
        throw new ServiceException("来源查询须指定往来类型和往来单位，不能推断授权范围", 400);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinArApSourceVo> listSourceInfo(Collection<Long> arApIds, String partyType, Long partyId) {
        validateParty(partyType, partyId);
        if (arApIds == null || arApIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = arApIds.stream().filter(Objects::nonNull).distinct().toList();
        List<FinArAp> rows = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 200) {
            rows.addAll(arApMapper.selectList(new LambdaQueryWrapper<FinArAp>()
                .select(FinArAp::getId, FinArAp::getPartyType, FinArAp::getPartyId,
                    FinArAp::getSourceType, FinArAp::getSourceId)
                .in(FinArAp::getId, ids.subList(start, Math.min(start + 200, ids.size())))
                .eq(FinArAp::getPartyType, partyType).eq(FinArAp::getPartyId, partyId)));
        }
        // 再次校验投影身份，损坏/错向明细不带出其它单位/方向的来源；租户由插件隔离。
        List<FinArAp> scoped = rows.stream().filter(r -> partyType.equals(r.getPartyType())
            && partyId.equals(r.getPartyId()) && ids.contains(r.getId())).toList();
        Map<SourceKey, SourceInfo> nos = loadSourceNos(scoped);
        return scoped.stream().map(row -> {
            FinArApSourceVo vo = new FinArApSourceVo();
            vo.setArApId(row.getId());
            vo.setSourceType(sourceMatchesDirection(row) ? row.getSourceType() : null);
            vo.setSourceNo(sourceNo(row, nos));
            return vo;
        }).toList();
    }

    private List<FinArApVo> detailVos(List<FinArAp> rows) {
        Map<SourceKey, SourceInfo> sourceNos = loadSourceNos(rows);
        Map<Long, String> customerNames = loadPartyNames(PARTY_AR, rows.stream()
            .filter(r -> PARTY_AR.equals(r.getPartyType())).map(FinArAp::getPartyId).toList());
        // 与单详情的历史分支一致：非 AR 类型按供应商解析，客户/供应商同 ID 不混用。
        Map<Long, String> supplierNames = loadPartyNames(PARTY_AP, rows.stream()
            .filter(r -> !PARTY_AR.equals(r.getPartyType())).map(FinArAp::getPartyId).toList());
        return rows.stream().map(r -> toVo(r, sourceNos,
            (PARTY_AR.equals(r.getPartyType()) ? customerNames : supplierNames).get(r.getPartyId()))).toList();
    }

    private void validatePartyType(String partyType) {
        if (!PARTY_AR.equals(partyType) && !PARTY_AP.equals(partyType)) {
            throw new ServiceException("partyType 必须是 AR 或 AP", 400);
        }
    }

    private void validateParty(String partyType, Long partyId) {
        validatePartyType(partyType);
        if (partyId == null || partyId <= 0) {
            throw new ServiceException("partyId 必须是有效往来单位 ID", 400);
        }
    }

    private long readPageOffset(int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > PageQuery.MAX_PAGE_SIZE) {
            throw new ServiceException("分页参数有误：页码须大于 0，每页数量须为 1..200", 400);
        }
        return (long) (pageNum - 1) * pageSize;
    }

    @Override
    public void settle(Long arApId, BigDecimal amount, String partyType, Long partyId) {
        settleInternal(arApId, amount, partyType, partyId, null, null);
    }

    private void settleInternal(Long arApId, BigDecimal amount, String partyType, Long partyId,
                                String sourceType, Long sourceId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("核销金额必须大于0");
        }
        FinArAp row = arApMapper.selectByIdForUpdate(arApId);
        if (row == null || STATUS_VOID.equals(row.getStatus())) {
            throw new ServiceException("应收应付台账不存在或已作废：" + arApId);
        }
        if (!row.getPartyType().equals(partyType) || !row.getPartyId().equals(partyId)) {
            throw new ServiceException("核销台账不属于该往来单位：" + arApId);
        }
        if (sourceType != null && (!sourceType.equals(row.getSourceType()) || !Objects.equals(sourceId, row.getSourceId()))) {
            throw new ServiceException("退款只能关联原销售单应收台账：" + arApId, 40902);
        }
        BigDecimal unsettled = row.getAmount().subtract(row.getSettledAmount());
        if (amount.compareTo(unsettled) > 0) {
            throw new ServiceException("核销金额超过未核销余额：" + arApId
                + "，未核销 " + unsettled);
        }
        BigDecimal newSettled = row.getSettledAmount().add(amount).setScale(2, java.math.RoundingMode.HALF_UP);
        row.setSettledAmount(newSettled);
        row.setStatus(newSettled.compareTo(row.getAmount()) >= 0 ? STATUS_SETTLED : "PARTIAL");
        arApMapper.updateById(row);
    }

    @Override
    public void unsettle(Long arApId, BigDecimal amount, String partyType, Long partyId) {
        adjustUnsettle(arApId, amount, partyType, partyId, null, null);
    }

    @Override
    public void unsettleSaleReturnRefund(Long arApId, BigDecimal amount, Long customerId, Long saleId) {
        adjustUnsettle(arApId, amount, PARTY_AR, customerId, SOURCE_SALE, saleId);
    }

    @Override
    public void settleSaleReturnRefund(Long arApId, BigDecimal amount, Long customerId, Long saleId) {
        settleInternal(arApId, amount, PARTY_AR, customerId, SOURCE_SALE, saleId);
    }

    private void adjustUnsettle(Long arApId, BigDecimal amount, String partyType, Long partyId,
                                String sourceType, Long sourceId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("反核销金额必须大于0");
        }
        FinArAp row = arApMapper.selectByIdForUpdate(arApId);
        if (row == null || STATUS_VOID.equals(row.getStatus())) {
            throw new ServiceException("应收应付台账不存在或已作废：" + arApId);
        }
        if (!Objects.equals(row.getPartyType(), partyType) || !Objects.equals(row.getPartyId(), partyId)) {
            throw new ServiceException("反核销台账不属于该往来单位：" + arApId, 40902);
        }
        if (sourceType != null && (!sourceType.equals(row.getSourceType()) || !Objects.equals(sourceId, row.getSourceId()))) {
            throw new ServiceException("退款只能关联原销售单应收台账：" + arApId, 40902);
        }
        if (amount.compareTo(row.getSettledAmount()) > 0) {
            throw new ServiceException("反核销金额超过已核销金额：" + arApId);
        }
        BigDecimal newSettled = row.getSettledAmount().subtract(amount).setScale(2, java.math.RoundingMode.HALF_UP);
        row.setSettledAmount(newSettled);
        row.setStatus(newSettled.compareTo(BigDecimal.ZERO) == 0 ? "OPEN" : "PARTIAL");
        arApMapper.updateById(row);
    }

    /**
     * 批量加载来源单号（SALE→biz_sale.no，PURCHASE→biz_purchase.no，OPENING→无）。
     * 避免列表接口 N+1 查询。
     */
    private record SourceKey(String sourceType, Long sourceId) { }
    private record SourceInfo(Long partyId, String no) { }

    private boolean sourceMatchesDirection(FinArAp row) {
        return switch (row.getSourceType() == null ? "" : row.getSourceType()) {
            case "SALE", "SALE_RETURN" -> PARTY_AR.equals(row.getPartyType());
            case "PURCHASE", "PURCHASE_RETURN", "SUBCONTRACT" -> PARTY_AP.equals(row.getPartyType());
            case "OPENING" -> PARTY_AR.equals(row.getPartyType()) || PARTY_AP.equals(row.getPartyType());
            default -> false;
        };
    }

    private Map<SourceKey, SourceInfo> loadSourceNos(Collection<FinArAp> rows) {
        Map<SourceKey, SourceInfo> nos = new HashMap<>();
        for (String type : List.of(SOURCE_SALE, SOURCE_PURCHASE, "SALE_RETURN", "PURCHASE_RETURN")) {
            List<Long> ids = rows.stream().filter(this::sourceMatchesDirection)
                .filter(r -> type.equals(r.getSourceType())).map(FinArAp::getSourceId)
                .filter(Objects::nonNull).distinct().toList();
            for (int start = 0; start < ids.size(); start += 200) {
                List<Long> batch = ids.subList(start, Math.min(start + 200, ids.size()));
                List<FinSourceNo> sources = switch (type) {
                    case "SALE" -> sourceNoMapper.selectSaleNos(batch);
                    case "PURCHASE" -> sourceNoMapper.selectPurchaseNos(batch);
                    case "SALE_RETURN" -> sourceNoMapper.selectSaleReturnNos(batch);
                    case "PURCHASE_RETURN" -> sourceNoMapper.selectPurchaseReturnNos(batch);
                    default -> throw new IllegalStateException("Unknown source type");
                };
                for (FinSourceNo source : sources) {
                    if (batch.contains(source.getSourceId())) {
                        nos.put(new SourceKey(type, source.getSourceId()), new SourceInfo(source.getPartyId(), source.getNo()));
                    }
                }
            }
        }
        return nos;
    }

    private String sourceNo(FinArAp row, Map<SourceKey, SourceInfo> nos) {
        SourceInfo source = nos.get(new SourceKey(row.getSourceType(), row.getSourceId()));
        return source != null && row.getPartyId() != null && row.getPartyId().equals(source.partyId())
            && sourceMatchesDirection(row) ? source.no() : null;
    }

    /** 只加载当前返回批次的去重 ID；空批次不查库，缺失记录/空名称保持 null，不逐行回查。 */
    private Map<Long, String> loadPartyNames(String partyType, Collection<Long> partyIds) {
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = partyIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return names;
        }
        for (int start = 0; start < ids.size(); start += 200) {
            List<Long> batch = ids.subList(start, Math.min(start + 200, ids.size()));
            if (PARTY_AR.equals(partyType)) {
                for (ErpCustomer customer : customerMapper.selectByIds(batch)) {
                    names.put(customer.getId(), customer.getName());
                }
            } else {
                for (ErpSupplier supplier : supplierMapper.selectByIds(batch)) {
                    names.put(supplier.getId(), supplier.getName());
                }
            }
        }
        return names;
    }

    /** 单详情保留原 selectById 解析语义，批量读取不得调用此重载。 */
    private FinArApVo toVo(FinArAp row, Map<SourceKey, SourceInfo> sourceNos) {
        return toVo(row, sourceNos, resolvePartyName(row.getPartyType(), row.getPartyId()));
    }

    private FinArApVo toVo(FinArAp row, Map<SourceKey, SourceInfo> sourceNos, String partyName) {
        FinArApVo vo = new FinArApVo();
        vo.setId(row.getId());
        vo.setPartyType(row.getPartyType());
        vo.setPartyId(row.getPartyId());
        vo.setPartyName(partyName);
        vo.setSourceType(row.getSourceType());
        vo.setSourceId(row.getSourceId());
        vo.setSourceNo(sourceNo(row, sourceNos));
        vo.setBizDate(row.getBizDate());
        vo.setAmount(row.getAmount());
        vo.setSettledAmount(row.getSettledAmount());
        vo.setUnsettledAmount(row.getAmount().subtract(row.getSettledAmount()));
        vo.setStatus(row.getStatus());
        vo.setStatusText(StatusText.arAp(row.getStatus()));
        return vo;
    }

    private String resolvePartyName(String partyType, Long partyId) {
        if (partyId == null) {
            return null;
        }
        if (PARTY_AR.equals(partyType)) {
            ErpCustomer c = customerMapper.selectById(partyId);
            return c == null ? null : c.getName();
        }
        ErpSupplier s = supplierMapper.selectById(partyId);
        return s == null ? null : s.getName();
    }

    /**
     * 委外加工单完工生成应付账款（partyType=AP, sourceType=SUBCONTRACT）。
     *
     * @param subcontractId 委外单ID
     * @param subcontractNo 委外单号
     * @param supplierId 加工商ID
     * @param amount 应付加工费合计
     * @param bizDate 业务日期
     */
}
