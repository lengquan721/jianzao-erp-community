package org.erp.fin.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.erp.fin.domain.FinOpening;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.service.IFinOpeningGuardService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/** 所有日期判断在同一稳定主数据行锁内，并使用锁定读（不是 RR 的旧快照）。 */
@Service
@RequiredArgsConstructor
public class FinOpeningGuardServiceImpl implements IFinOpeningGuardService {

    private final FinOpeningMapper openingMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockParty(String partyType, Long partyId) {
        if ((!"AR".equals(partyType) && !"AP".equals(partyType)) || partyId == null || partyId <= 0) {
            throw new ServiceException("往来类型必须是 AR 或 AP，且往来单位 ID 必须有效", 400);
        }
        // 自定义 SQL 同样经过 TenantLineInnerInterceptor；不能锁不存在/跨租户/软删的主数据。
        Long locked = "AR".equals(partyType)
            ? openingMapper.lockCustomer(partyId) : openingMapper.lockSupplier(partyId);
        if (locked == null) {
            throw new ServiceException("本租户往来单位不存在或已删除", 40902);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockAndCheckBusinessDate(String partyType, Long partyId, Date bizDate) {
        if (bizDate == null) {
            throw new ServiceException("业务日期不能为空", 400);
        }
        lockParty(partyType, partyId);
        FinOpening opening = openingMapper.selectByPartyForUpdate(partyType, partyId);
        if (opening != null && "CONFIRMED".equals(opening.getStatus()) && opening.getOpeningDate() != null
            && !new java.sql.Date(bizDate.getTime()).toLocalDate().isAfter(opening.getOpeningDate())) {
            throw new ServiceException("业务日期不能早于或等于已确认期初截止日 " + opening.getOpeningDate()
                + "；请先受控撤回期初，已核销时须先反核销", 40902);
        }
    }
}
