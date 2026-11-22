package org.erp.fin.service;

import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.fin.domain.bo.FinOpeningBo;
import org.erp.fin.domain.vo.FinOpeningVo;
import org.erp.fin.domain.vo.FinOpeningConfirmedAmountVo;

import java.util.List;

/**
 * 期初往来服务
 *
 * 确认后落 fin_ar_ap(source_type=OPENING)（D1），参与应收应付汇总；
 * 确认后锁定，反确认需先冲销期初台账（已核销拒 40904）。
 */
public interface IFinOpeningService {

    /** 兼容旧列表与 Excel 导出：完整返回至多 5000 行，超限明确失败，绝不截断。 */
    List<FinOpeningVo> listAll(String partyType);

    /** 数据库分页，total 为全部匹配期初行数；页大小 1..200，按 id ASC。 */
    TableDataInfo<FinOpeningVo> page(String partyType, int pageNum, int pageSize);

    /**
     * 请求内去重 1..200 个精确正 BIGINT 字符串；仅返回本租户、对应主数据可读的往来。
     * 已确认原始期初（含历史 NULL 截止日）；可读但无已确认期初显式 "0"，未知往来省略。
     * 财务 list 与对应客户/供应商 list 或 query 权限同时满足；不扫描 summary/分页。
     */
    List<FinOpeningConfirmedAmountVo> confirmedByPartyIds(String partyType, String partyIds);

    FinOpeningVo save(FinOpeningBo bo);

    void confirm(Long id);

    void unconfirm(Long id);
}
