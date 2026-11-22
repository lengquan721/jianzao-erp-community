package org.erp.fin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.fin.domain.FinAccount;
import org.erp.fin.domain.FinAccountMovement;
import org.erp.fin.domain.vo.FinAccountFlowVo;
import org.erp.fin.domain.vo.FinAccountVo;

import java.util.List;

/**
 * 结算账户 Mapper
 */
public interface FinAccountMapper extends BaseMapperPlus<FinAccount, FinAccountVo> {

    /**
     * 按编码查询已逻辑删除的记录 ID（唯一键 tenant_id + code 不区分 del_flag），
     * 删档后重建同码账户时复用该行（对齐 ErpProductMapper 先例）。
     *
     * @param code 账户编码
     * @return 已删除记录 ID，不存在时返回 null
     */
    @Select("select id from fin_account where code = #{code} and del_flag = '1' limit 1")
    Long selectDeletedIdByCode(@Param("code") String code);

    /**
     * 计数和取页共用完全相同的集合。接口没有日期过滤，保留所有 biz_date（包括 null），
     * 不把业务日期转为确认日期，也不做 DATE/时区截断。source_order 对齐旧 Java 稳定排序的分支顺序。
     * flow_type 二进制比较对齐 Java 的大小写/尾空格敏感 equals，避免排序规则改变历史单据方向。
     */
    String FLOW_UNION = """
        select 'RECEIPT' as biz_type, r.no as biz_no, r.biz_date, r.amount,
               case when r.negative = 1 then 'OUT' else 'IN' end as direction,
               r.customer_id as party_id, null as counterparty, r.id as row_id, 0 as source_order
        from fin_receipt r
        where r.tenant_id = #{tenantId} and r.del_flag = '0' and r.status = 'CONFIRMED'
          and r.account_id = #{accountId}
        union all
        select 'PAYMENT' as biz_type, p.no as biz_no, p.biz_date, p.amount,
               'OUT' as direction, p.supplier_id as party_id, null as counterparty,
               p.id as row_id, 1 as source_order
        from fin_payment p
        where p.tenant_id = #{tenantId} and p.del_flag = '0' and p.status = 'CONFIRMED'
          and p.account_id = #{accountId}
        union all
        select 'OTHER_FLOW' as biz_type, o.no as biz_no, o.biz_date, o.amount,
               case when cast(o.flow_type as binary) = 'INCOME' then 'IN' else 'OUT' end as direction,
               null as party_id, o.remark as counterparty, o.id as row_id, 2 as source_order
        from fin_other_flow o
        where o.tenant_id = #{tenantId} and o.del_flag = '0' and o.status = 'CONFIRMED'
          and o.account_id = #{accountId}
        """;

    @Select("select count(*) from (" + FLOW_UNION + ") f")
    long countFlows(@Param("tenantId") String tenantId, @Param("accountId") Long accountId);

    /**
     * 显式 LIMIT，避免全局 overflow=true 将末页外请求重定向首页。
     * 名称仅在已取出的当前页上连接；连接保留缺失/已删除往来单位对应的流水，名称返回 null。
     */
    @Select("""
        select f.biz_type as bizType, f.biz_no as bizNo, f.biz_date as bizDate,
               f.amount, f.direction, f.row_id as rowId,
               case when f.biz_type = 'RECEIPT' then c.name
                    when f.biz_type = 'PAYMENT' then s.name
                    else f.counterparty end as counterparty
        from (
            select u.* from (
        """ + FLOW_UNION + """
            ) u
            order by u.biz_date desc, u.row_id desc, u.source_order asc
            limit #{offset}, #{limit}
        ) f
        left join md_customer c on f.biz_type = 'RECEIPT' and c.id = f.party_id
             and c.tenant_id = #{tenantId} and c.del_flag = '0'
        left join md_supplier s on f.biz_type = 'PAYMENT' and s.id = f.party_id
             and s.tenant_id = #{tenantId} and s.del_flag = '0'
        order by f.biz_date desc, f.row_id desc, f.source_order asc
        """)
    List<FinAccountFlowVo> selectFlowPage(@Param("tenantId") String tenantId, @Param("accountId") Long accountId,
                                         @Param("offset") long offset, @Param("limit") long limit);

    /**
     * 只聚合账户当前页的 ID；各分支先分组后 UNION ALL，避免返回历史明细或往来单位 N+1。
     * 所有运算保持 DECIMAL，不提前舍入；与单户余额一致，由服务在加期初后 HALF_UP 到两位。
     */
    @Select("""
        <script>
        select m.account_id as accountId, sum(m.movement) as movement from (
            select r.account_id,
                   sum(case when r.negative = 1 then -coalesce(r.amount, 0)
                            else coalesce(r.amount, 0) end) as movement
            from fin_receipt r
            where r.tenant_id = #{tenantId} and r.del_flag = '0' and r.status = 'CONFIRMED'
            <choose>
                <when test="accountIds != null and !accountIds.isEmpty()">
                    and r.account_id in
                    <foreach collection="accountIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                </when>
                <otherwise>and 1 = 0</otherwise>
            </choose>
            group by r.account_id
            union all
            select p.account_id, -sum(coalesce(p.amount, 0)) as movement
            from fin_payment p
            where p.tenant_id = #{tenantId} and p.del_flag = '0' and p.status = 'CONFIRMED'
            <choose>
                <when test="accountIds != null and !accountIds.isEmpty()">
                    and p.account_id in
                    <foreach collection="accountIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                </when>
                <otherwise>and 1 = 0</otherwise>
            </choose>
            group by p.account_id
            union all
            select o.account_id,
                   sum(case when cast(o.flow_type as binary) = 'INCOME' then coalesce(o.amount, 0)
                            else -coalesce(o.amount, 0) end) as movement
            from fin_other_flow o
            where o.tenant_id = #{tenantId} and o.del_flag = '0' and o.status = 'CONFIRMED'
            <choose>
                <when test="accountIds != null and !accountIds.isEmpty()">
                    and o.account_id in
                    <foreach collection="accountIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                </when>
                <otherwise>and 1 = 0</otherwise>
            </choose>
            group by o.account_id
        ) m group by m.account_id
        </script>
        """)
    List<FinAccountMovement> selectMovements(@Param("tenantId") String tenantId,
                                            @Param("accountIds") List<Long> accountIds);
}
