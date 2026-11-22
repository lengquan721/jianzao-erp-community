package org.erp.fin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.fin.domain.FinArAp;
import org.erp.fin.domain.vo.FinArApVo;
import org.erp.fin.domain.vo.FinArApSummaryVo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 应收应付台账 Mapper
 */
public interface FinArApMapper extends BaseMapperPlus<FinArAp, FinArApVo> {

    // 自定义 SQL 不会自动补逻辑删除；租户仍由 TenantLineInnerInterceptor 强制注入。
    String READ_SCOPE = " from fin_ar_ap where party_type = #{partyType}"
        + " and status != 'VOID' and del_flag = '0'";
    String PARTY_SCOPE = READ_SCOPE + " and party_id = #{partyId}";
    String DETAIL_COLUMNS = "select id, party_type, party_id, source_type, source_id, biz_date, amount, settled_amount, status";

    /** 全部匹配台账的合计，独立于往来单位分页，保留已结清行、期初及退货负数行。 */
    @Select("select coalesce(sum(amount), 0) as total_amount,"
        + " coalesce(sum(settled_amount), 0) as settled_amount,"
        + " coalesce(sum(amount - settled_amount), 0) as unsettled_amount,"
        + " coalesce(sum(case when source_type = 'OPENING' then amount else 0 end), 0) as opening_amount"
        + READ_SCOPE)
    FinArApSummaryVo selectSummaryTotals(@Param("partyType") String partyType);

    @Select("select count(distinct party_id)" + READ_SCOPE)
    long countSummaryParties(@Param("partyType") String partyType);

    /** 聚合后再 LIMIT，不能把台账当前页当作单位合计；party_id 是稳定的唯一排序键。 */
    @Select("select party_id, coalesce(sum(amount), 0) as amount,"
        + " coalesce(sum(settled_amount), 0) as settled,"
        + " coalesce(sum(amount - settled_amount), 0) as unsettled,"
        + " coalesce(sum(case when source_type = 'OPENING' then amount else 0 end), 0) as opening"
        + READ_SCOPE + " group by party_id order by party_id asc limit #{offset}, #{limit}")
    List<FinArApSummaryVo.PartySummary> selectSummaryParties(@Param("partyType") String partyType,
                                                           @Param("offset") long offset, @Param("limit") int limit);

    @Select("select count(*)" + PARTY_SCOPE)
    long countByParty(@Param("partyType") String partyType, @Param("partyId") Long partyId);

    /** 业务日期（包括 null）和 ID 升序沿用原明细口径，不加隐式日期/结清状态过滤。 */
    @Select(DETAIL_COLUMNS + PARTY_SCOPE + " order by biz_date asc, id asc limit #{offset}, #{limit}")
    List<FinArAp> selectByPartyPage(@Param("partyType") String partyType, @Param("partyId") Long partyId,
                                  @Param("offset") long offset, @Param("limit") int limit);

    /** 原表单筛选条件下推：仅正余额的非 VOID 行。先筛候选，再判容量，不能拿历史行数当候选数。 */
    @Select(DETAIL_COLUMNS + PARTY_SCOPE + " and amount > settled_amount order by biz_date asc, id asc limit #{limit}")
    List<FinArAp> selectAllocatableByParty(@Param("partyType") String partyType, @Param("partyId") Long partyId,
                                         @Param("limit") int limit);

    /**
     * 锁定台账行（事务内 SELECT ... FOR UPDATE），核销/反核销/冲销前调用，
     * 防并发读-改-写丢失更新（与库存 selectForUpdate 同一先例）。
     */
    @Select("""
        select * from fin_ar_ap
        where id = #{id}
          and del_flag = '0'
        for update
        """)
    FinArAp selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按往来类型汇总未核销金额（SUM(amount - settled_amount)），供看板一次性取回未收/未付。
     *
     * <p>替代原先「全表 selectList 后在内存累加」的写法：台账随业务量线性增长，
     * 交给数据库聚合只回一行，避免把整张表读进堆内存。tenant_id 与 del_flag 由多租户插件注入。
     *
     * @param partyType AR=应收 AP=应付
     * @return 未核销金额合计；无数据返回 0
     */
    @Select("""
        select coalesce(sum(amount - settled_amount), 0)
        from fin_ar_ap
        where party_type = #{partyType}
          and status != 'VOID'
          and del_flag = '0'
        """)
    BigDecimal sumUnsettledByPartyType(@Param("partyType") String partyType);

    /**
     * 按往来类型 + 往来单位汇总未核销金额，用于单据打印上的「累计欠款」。
     *
     * <p>与 {@link #sumUnsettledByPartyType} 同口径，只是多一个 party_id 过滤；
     * tenant_id 与 del_flag 由多租户插件注入。
     *
     * @param partyType AR=应收 AP=应付
     * @param partyId   客户/供应商主键
     * @return 未核销金额合计；无数据返回 0
     */
    @Select("""
        select coalesce(sum(amount - settled_amount), 0)
        from fin_ar_ap
        where party_type = #{partyType}
          and party_id = #{partyId}
          and status != 'VOID'
          and del_flag = '0'
        """)
    BigDecimal sumUnsettledByParty(@Param("partyType") String partyType, @Param("partyId") Long partyId);
}
