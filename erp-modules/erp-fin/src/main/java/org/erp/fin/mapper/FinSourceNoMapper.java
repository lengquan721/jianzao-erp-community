package org.erp.fin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.erp.fin.domain.FinSourceNo;

import java.util.Collection;
import java.util.List;

/**
 * 来源单号查询 Mapper
 *
 * 说明：erp-fin 位于 erp-trade 上游（依赖方向 master → stock → production → fin → trade → print），
 * 为避免反向依赖引入环，此处直接以 SQL 投影业务单表获取来源单号，
 * 不引入 erp-trade 的实体与 Mapper。
 */
public interface FinSourceNoMapper {

    /**
     * 批量查询销售单号（biz_sale）。
     */
    @Select({
        "<script>",
        "select id as sourceId, customer_id as partyId, no from biz_sale",
        "where del_flag = '0' and id in",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    List<FinSourceNo> selectSaleNos(@Param("ids") Collection<Long> ids);

    /**
     * 批量查询采购单号（biz_purchase）。
     */
    @Select({
        "<script>",
        "select id as sourceId, supplier_id as partyId, no from biz_purchase",
        "where del_flag = '0' and id in",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    List<FinSourceNo> selectPurchaseNos(@Param("ids") Collection<Long> ids);

    /**
     * 批量查询销售退货单号（biz_sale_return，DOC/24 台账红冲行回显）。
     */
    @Select({
        "<script>",
        "select id as sourceId, customer_id as partyId, no from biz_sale_return",
        "where del_flag = '0' and id in",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    List<FinSourceNo> selectSaleReturnNos(@Param("ids") Collection<Long> ids);

    /**
     * 批量查询采购退货单号（biz_purchase_return）。
     */
    @Select({
        "<script>",
        "select id as sourceId, supplier_id as partyId, no from biz_purchase_return",
        "where del_flag = '0' and id in",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    List<FinSourceNo> selectPurchaseReturnNos(@Param("ids") Collection<Long> ids);
}
