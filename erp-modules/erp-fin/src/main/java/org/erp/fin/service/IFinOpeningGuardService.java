package org.erp.fin.service;

import java.util.Date;

/**
 * 期初/业务确认的统一往来锁边界。两方法必须由现有可写事务调用（MANDATORY）。
 * 锁本租户有效 md_customer(AR) / md_supplier(AP) 稳定行，直到外层事务结束；
 * 即使还没有 fin_opening 也不能绕过。重复获取同一把锁安全。
 *
 * 锁序：稳定往来行 -> 期初/收付款单头 -> 台账（原有 id 顺序）。
 * trade 可以先持有自身业务头，但必须在任何台账/收付款写入之前调用检查；
 * 期初重叠检查不锁 trade 单头，避免反向等待。多往来操作按 AR/AP、partyId 升序取锁。
 */
public interface IFinOpeningGuardService {

    void lockParty(String partyType, Long partyId);

    /** 已确认且有明确截止日的期初，拒绝 bizDate 当日 <= 截止日；历史 NULL 不推断。 */
    void lockAndCheckBusinessDate(String partyType, Long partyId, Date bizDate);
}
