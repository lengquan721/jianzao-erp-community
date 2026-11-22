package org.erp.fin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.fin.domain.FinOpening;
import org.erp.fin.domain.vo.FinOpeningVo;

import java.util.List;

/**
 * 期初往来 Mapper
 */
public interface FinOpeningMapper extends BaseMapperPlus<FinOpening, FinOpeningVo> {

    // 自定义读显式过滤软删；租户继续由现有 TenantLineInnerInterceptor 注入。
    String READ_SCOPE = " from fin_opening where del_flag = '0'"
        + " <if test=\"partyType != null and !partyType.isBlank()\"> and party_type = #{partyType}</if>";

    @Select("<script>select count(*)" + READ_SCOPE + "</script>")
    long countReadRows(@Param("partyType") String partyType);

    /** 保留全部状态/创建时间及原 id ASC 顺序；先数据库取页，再批量补名称。 */
    @Select("<script>select id, party_type, party_id, amount, opening_date, status, remark, create_time"
        + READ_SCOPE + " order by id asc limit #{offset}, #{limit}</script>")
    List<FinOpening> selectReadRows(@Param("partyType") String partyType,
                                    @Param("offset") long offset, @Param("limit") int limit);

    /** 只查有权主数据 ID 的已确认原始期初；不按 opening_date 过滤历史 NULL，不计算余额。 */
    @Select("<script>select party_id, amount from fin_opening"
        + " where party_type = #{partyType} and status = 'CONFIRMED' and del_flag = '0'"
        + " and party_id in"
        + " <foreach collection='partyIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<FinOpening> selectConfirmedAmountsByPartyIds(@Param("partyType") String partyType,
                                                    @Param("partyIds") List<Long> partyIds);

    @Select("select id from md_customer where id = #{partyId} and del_flag = '0' for update")
    Long lockCustomer(@Param("partyId") Long partyId);

    @Select("select id from md_supplier where id = #{partyId} and del_flag = '0' for update")
    Long lockSupplier(@Param("partyId") Long partyId);

    @Select("select * from fin_opening where id = #{id} and del_flag = '0' for update")
    FinOpening selectByIdForUpdate(@Param("id") Long id);

    @Select("select * from fin_opening where party_type = #{partyType} and party_id = #{partyId}"
        + " and del_flag = '0' for update")
    FinOpening selectByPartyForUpdate(@Param("partyType") String partyType, @Param("partyId") Long partyId);

    // 只在持有稳定往来锁时使用。FOR UPDATE 是当前读，不能用普通 count 的 RR 历史快照。
    // 不锁 trade 单头：trade 可已持自身单头再取往来锁，但不得先创建台账。
    @Select("select count(*) from fin_ar_ap where party_type = #{partyType} and party_id = #{partyId}"
        + " and source_type != 'OPENING' and status != 'VOID' and del_flag = '0'"
        + " and biz_date < #{before} for update")
    long countLedgerBeforeForUpdate(@Param("partyType") String partyType, @Param("partyId") Long partyId,
                                   @Param("before") java.util.Date before);

    @Select("select count(*) from fin_receipt where customer_id = #{partyId} and status = 'CONFIRMED'"
        + " and del_flag = '0' and biz_date < #{before} for update")
    long countReceiptsBeforeForUpdate(@Param("partyId") Long partyId, @Param("before") java.util.Date before);

    @Select("select count(*) from fin_payment where supplier_id = #{partyId} and status = 'CONFIRMED'"
        + " and del_flag = '0' and biz_date < #{before} for update")
    long countPaymentsBeforeForUpdate(@Param("partyId") Long partyId, @Param("before") java.util.Date before);
}
