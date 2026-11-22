package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.common.enums.StatusText;
import org.erp.fin.domain.FinOpening;
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.domain.vo.FinOpeningVo;
import org.erp.fin.domain.vo.FinOpeningConfirmedAmountVo;
import cn.dev33.satoken.stp.StpUtil;
import org.erp.master.domain.vo.ErpCustomerVo;
import org.erp.master.domain.vo.ErpSupplierVo;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.service.IFinArApService;
import org.erp.fin.service.IFinOpeningService;
import org.erp.fin.service.IFinOpeningGuardService;
import org.springframework.dao.DuplicateKeyException;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpSupplier;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 期初往来服务实现
 *
 * 规则（DOC/17 §3、DOC/02 D1）：
 * - 录入即 upsert（party_type+party_id 唯一键），CONFIRMED 行拒改（40902）；
 * - confirm：createOpening 落 fin_ar_ap(OPENING)；重录即先冲旧（createOpening 内置）；
 * - unconfirm：先冲销期初台账（settled>0 拒 40904），行回 DRAFT。
 */
@RequiredArgsConstructor
@Service
public class FinOpeningServiceImpl implements IFinOpeningService {

    private final FinOpeningMapper openingMapper;
    private final ErpCustomerMapper customerMapper;
    private final ErpSupplierMapper supplierMapper;
    private final IFinArApService arApService;
    private final IFinOpeningGuardService openingGuard;

    private static final int READ_LIMIT = 5000;
    private static final int PAGE_LIMIT = 200;

    @Override
    @Transactional(readOnly = true)
    public List<FinOpeningVo> listAll(String partyType) {
        // Excel 导出与旧 /list 共用此边界；不用交互分页对象，避免 maxLimit 裁掉哨兵。
        List<FinOpening> rows = openingMapper.selectReadRows(partyType, 0, READ_LIMIT + 1);
        if (rows.size() > READ_LIMIT) {
            throw new ServiceException("往来期初单次最多返回/导出 5000 行，请按往来类型筛选后重试；列表请使用分页接口", 400);
        }
        return toVos(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public TableDataInfo<FinOpeningVo> page(String partyType, int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > PAGE_LIMIT) {
            throw new ServiceException("分页参数有误：页码须大于 0，每页数量须为 1..200", 400);
        }
        long offset = (long) (pageNum - 1) * pageSize;
        long total = openingMapper.countReadRows(partyType);
        List<FinOpening> rows = offset >= total ? List.of()
            : openingMapper.selectReadRows(partyType, offset, pageSize);
        return new TableDataInfo<>(toVos(rows), total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinOpeningConfirmedAmountVo> confirmedByPartyIds(String partyType, String partyIds) {
        if (!"AR".equals(partyType) && !"AP".equals(partyType)) {
            throw new ServiceException("partyType 必须是 AR 或 AP", 400);
        }
        Map<Long, String> requested = parsePartyIds(partyIds);
        // 不把 finance:list 变成主数据探测权限；主数据页 list 和单项 query 都是既有读取入口。
        StpUtil.checkPermission("erp:finance:list");
        String masterPermission = "AR".equals(partyType) ? "erp:master:customer" : "erp:master:supplier";
        if (!StpUtil.hasPermission(masterPermission + ":list")) {
            StpUtil.checkPermission(masterPermission + ":query");
        }
        List<Long> ids = List.copyOf(requested.keySet());
        // 复用现有 options/by-ids 的主数据读取 SQL：同一租户/软删/数据可读边界。
        // 输入在此已去重并限制 1..200；不使用全量 selectList、主数据分页或逐项 selectById。
        Set<Long> readable = new HashSet<>("AR".equals(partyType)
            ? customerMapper.selectOptionsByIds(ids).stream().map(ErpCustomerVo::getId).toList()
            : supplierMapper.selectOptionsByIds(ids).stream().map(ErpSupplierVo::getId).toList());
        List<Long> readableIds = ids.stream().filter(readable::contains).toList();
        if (readableIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> confirmedAmounts = new HashMap<>();
        for (FinOpening row : openingMapper.selectConfirmedAmountsByPartyIds(partyType, readableIds)) {
            if (!readableIds.contains(row.getPartyId())) {
                continue;
            }
            if (row.getAmount() == null || confirmedAmounts.containsKey(row.getPartyId())) {
                throw new ServiceException("已确认期初金额或唯一性异常，请核对原账", 40902);
            }
            // 不能转 double、不能计算未结余额；保留 DECIMAL 原始值与 scale 的精确文本。
            confirmedAmounts.put(row.getPartyId(), row.getAmount().toPlainString());
        }
        return readableIds.stream().map(id -> new FinOpeningConfirmedAmountVo(requested.get(id),
            confirmedAmounts.getOrDefault(id, "0"))).toList();
    }

    private static Map<Long, String> parsePartyIds(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 8192) {
            throw new ServiceException("partyIds 必须是逗号分隔的 1..200 个正 ID，原始长度不超过8192", 400);
        }
        Map<Long, String> ids = new LinkedHashMap<>();
        for (String token : raw.split(",", -1)) {
            String id = token.strip();
            if (!id.matches("[1-9][0-9]{0,18}")) {
                throw new ServiceException("partyIds 必须使用无前导零的正 BIGINT 十进制字符串", 400);
            }
            try {
                ids.putIfAbsent(Long.parseLong(id), id);
            } catch (NumberFormatException e) {
                throw new ServiceException("partyIds 超出正 BIGINT 范围", 400);
            }
            if (ids.size() > PAGE_LIMIT) {
                throw new ServiceException("partyIds 去重后最多200个", 400);
            }
        }
        return ids;
    }

    /** 仅查结果页中的 ID，按 AR/AP 隔离并去重；旧列表/导出同样每批最多 200 个 ID。 */
    private List<FinOpeningVo> toVos(List<FinOpening> rows) {
        Map<Long, String> customerNames = new HashMap<>();
        Map<Long, String> supplierNames = new HashMap<>();
        List<Long> customerIds = rows.stream().filter(row -> "AR".equals(row.getPartyType()))
            .map(FinOpening::getPartyId).filter(Objects::nonNull).distinct().toList();
        List<Long> supplierIds = rows.stream().filter(row -> !"AR".equals(row.getPartyType()))
            .map(FinOpening::getPartyId).filter(Objects::nonNull).distinct().toList();
        for (int start = 0; start < customerIds.size(); start += PAGE_LIMIT) {
            for (ErpCustomer customer : customerMapper.selectByIds(customerIds.subList(start, Math.min(start + PAGE_LIMIT, customerIds.size())))) {
                customerNames.put(customer.getId(), customer.getName());
            }
        }
        for (int start = 0; start < supplierIds.size(); start += PAGE_LIMIT) {
            for (ErpSupplier supplier : supplierMapper.selectByIds(supplierIds.subList(start, Math.min(start + PAGE_LIMIT, supplierIds.size())))) {
                supplierNames.put(supplier.getId(), supplier.getName());
            }
        }
        return rows.stream().map(row -> toVo(row, "AR".equals(row.getPartyType()) ? customerNames : supplierNames)).toList();
    }

    private FinOpeningVo toVo(FinOpening row, Map<Long, String> names) {
        FinOpeningVo vo = new FinOpeningVo();
        vo.setId(row.getId());
        vo.setPartyType(row.getPartyType());
        vo.setPartyId(row.getPartyId());
        vo.setPartyName(names.get(row.getPartyId()));
        vo.setAmount(row.getAmount());
        vo.setOpeningDate(row.getOpeningDate());
        vo.setStatus(row.getStatus());
        vo.setStatusText(StatusText.bill(row.getStatus()));
        vo.setRemark(row.getRemark());
        vo.setCreateTime(row.getCreateTime());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FinOpeningVo save(FinOpeningBo bo) {
        validateOpeningDate(bo.getOpeningDate());
        if (bo.getAmount() == null || bo.getAmount().signum() < 0) {
            throw new ServiceException("期初金额不能为空或为负", 400);
        }
        openingGuard.lockParty(bo.getPartyType(), bo.getPartyId());
        FinOpening exist = openingMapper.selectByPartyForUpdate(bo.getPartyType(), bo.getPartyId());
        if (exist != null && "CONFIRMED".equals(exist.getStatus())) {
            throw new ServiceException("期初已确认锁定，请先反确认", 40902);
        }
        FinOpening row = exist == null ? new FinOpening() : exist;
        row.setPartyType(bo.getPartyType());
        row.setPartyId(bo.getPartyId());
        row.setAmount(bo.getAmount().setScale(2, RoundingMode.HALF_UP));
        row.setOpeningDate(bo.getOpeningDate());
        row.setRemark(bo.getRemark());
        row.setStatus("DRAFT");
        if (exist == null) {
            try {
                openingMapper.insert(row);
            } catch (DuplicateKeyException e) {
                throw new ServiceException("该单位已有期初记录，请刷新后重试", 40902);
            }
        } else {
            requireOneRow(openingMapper.update(row, new UpdateWrapper<FinOpening>()
                .eq("id", row.getId()).eq("status", "DRAFT")));
        }
        FinOpeningVo vo = new FinOpeningVo();
        vo.setId(row.getId());
        vo.setPartyType(row.getPartyType());
        vo.setPartyId(row.getPartyId());
        vo.setAmount(row.getAmount());
        vo.setOpeningDate(row.getOpeningDate());
        vo.setStatus(row.getStatus());
        vo.setStatusText(StatusText.bill(row.getStatus()));
        vo.setOverwritten(exist != null);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        FinOpening row = lockOpening(id);
        if (!"DRAFT".equals(row.getStatus())) {
            throw new ServiceException("期初已确认", 40902);
        }
        validateOpeningDate(row.getOpeningDate());
        Date boundary = Date.from(row.getOpeningDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        long overlaps = arApService.countBusinessBefore(row.getPartyType(), row.getPartyId(), boundary);
        if (overlaps > 0) {
            throw new ServiceException("期初截止日 " + row.getOpeningDate() + " 及之前已有 " + overlaps
                + " 笔已确认业务（含收付款），会重复计算；请先核对原账，不自动搬账", 40902);
        }
        // 使用显式截止日，不使用当前时间，也不推断/回填历史 NULL 日期。
        Date bizDate = Date.from(row.getOpeningDate().atStartOfDay(ZoneId.systemDefault()).toInstant());
        arApService.createOpening(row.getPartyType(), row.getPartyId(), row.getAmount(), row.getId(), bizDate);
        requireOneRow(openingMapper.update(null, new UpdateWrapper<FinOpening>()
            .eq("id", id).eq("status", "DRAFT").set("status", "CONFIRMED")));
        row.setStatus("CONFIRMED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unconfirm(Long id) {
        FinOpening row = lockOpening(id);
        if (!"CONFIRMED".equals(row.getStatus())) {
            throw new ServiceException("仅已确认期初可反确认", 40902);
        }
        // 期初台账已核销时 voidBySource 抛 40904
        arApService.voidBySource("OPENING", row.getId());
        requireOneRow(openingMapper.update(null, new UpdateWrapper<FinOpening>()
            .eq("id", id).eq("status", "CONFIRMED").set("status", "DRAFT")));
        row.setStatus("DRAFT");
    }

    private FinOpening lockOpening(Long id) {
        // 这里只读取不可变的往来身份来定位锁；状态/金额/日期一律使用锁后的当前读。
        FinOpening identity = openingMapper.selectById(id);
        if (identity == null) {
            throw new ServiceException("期初记录不存在");
        }
        openingGuard.lockParty(identity.getPartyType(), identity.getPartyId());
        FinOpening current = openingMapper.selectByIdForUpdate(id);
        if (current == null || !Objects.equals(identity.getPartyType(), current.getPartyType())
            || !Objects.equals(identity.getPartyId(), current.getPartyId())) {
            throw new ServiceException("期初往来身份已变更，请刷新后重试", 40902);
        }
        return current;
    }

    private static void validateOpeningDate(LocalDate date) {
        if (date == null) {
            throw new ServiceException("期初截止日为空，请先编辑显式补录截止日", 400);
        }
        if (date.isBefore(LocalDate.of(1000, 1, 1))) {
            throw new ServiceException("期初截止日超出 MySQL DATE 范围", 400);
        }
        if (date.isAfter(LocalDate.now())) {
            throw new ServiceException("期初截止日不能晚于今天", 400);
        }
    }

    private static void requireOneRow(int rows) {
        if (rows != 1) {
            throw new ServiceException("期初状态已变更，请刷新后重试", 40902);
        }
    }
}
