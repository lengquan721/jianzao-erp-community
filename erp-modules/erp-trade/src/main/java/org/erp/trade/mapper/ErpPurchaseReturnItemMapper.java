package org.erp.trade.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.trade.domain.ErpPurchaseReturnItem;
import org.erp.trade.domain.vo.ErpPurchaseReturnVo;
import org.erp.trade.domain.vo.ErpReturnQtyAggregateVo;

import java.util.Collection;
import java.util.List;

/**
 * 采购退货明细 Mapper
 */
public interface ErpPurchaseReturnItemMapper extends BaseMapperPlus<ErpPurchaseReturnItem, ErpPurchaseReturnVo.ItemVo> {

    /** 按原采购行汇总当前租户内已确认退货，空 ID 集合由调用方短路。 */
    @Select("""
        <script>
        select r.purchase_item_id as originalItemId, sum(r.qty) as returnedQty
        from biz_purchase_return_item r
        join biz_purchase_return rh on rh.id = r.order_id and rh.tenant_id = r.tenant_id
        where r.purchase_item_id in
          <foreach collection="originalItemIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          and r.tenant_id = #{tenantId} and rh.tenant_id = #{tenantId}
          and r.del_flag = '0' and rh.del_flag = '0' and rh.status = 'CONFIRMED'
          <if test="excludeReturnId != null">and rh.id &lt;&gt; #{excludeReturnId}</if>
        group by r.purchase_item_id
        </script>
        """)
    List<ErpReturnQtyAggregateVo> sumConfirmedReturnedQty(@Param("originalItemIds") Collection<Long> originalItemIds,
                                                          @Param("tenantId") String tenantId,
                                                          @Param("excludeReturnId") Long excludeReturnId);
}
