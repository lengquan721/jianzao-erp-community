package org.erp.trade.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.trade.domain.ErpSaleItem;
import org.erp.trade.domain.vo.ErpSaleVo;

import java.util.Date;

/**
 * 销售明细 Mapper
 */
public interface ErpSaleItemMapper extends BaseMapperPlus<ErpSaleItem, ErpSaleVo.ItemVo> {

    /**
     * 按销售头的期间直接连接统计成本为 0 的明细，不把销售头 ID 全量装入 JVM 再生成大 IN。
     */
    @Select("""
        select count(*)
        from biz_sale_item i
        join biz_sale h on h.id = i.order_id and h.tenant_id = i.tenant_id
        where h.tenant_id = #{tenantId} and i.tenant_id = #{tenantId}
          and h.del_flag = '0' and i.del_flag = '0'
          and h.status = 'CONFIRMED'
          and h.biz_date >= #{from} and h.biz_date < #{to}
          and i.cost_amount = 0
        """)
    Long countMissingCostBySaleWindow(@Param("tenantId") String tenantId,
                                      @Param("from") Date from, @Param("to") Date to);
}
