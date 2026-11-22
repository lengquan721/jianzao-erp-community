package org.erp.trade.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.trade.domain.ErpSaleOrder;
import org.erp.trade.domain.ErpSaleOrderItem;
import org.erp.trade.domain.vo.ErpSaleOrderVo;
import org.erp.trade.domain.vo.OrderExecutionAmountVo;

import java.util.Collection;
import java.util.List;

/**
 * 销售订单头 Mapper
 *
 * <p>锁定读与执行记录查询都走注解 SQL：原生 SQL 不经过 @TableLogic，必须显式带 del_flag = '0'；
 * tenant_id 由多租户插件注入，不手写条件。
 * 订单行的批量锁定读与采购订单同口径落在订单 Mapper 上（DOC/31 §5.4），SQL 内固定 ORDER BY id ASC。
 */
public interface ErpSaleOrderMapper extends BaseMapperPlus<ErpSaleOrder, ErpSaleOrderVo> {

    /**
     * 按主键锁定订单头（SELECT ... FOR UPDATE）。
     *
     * <p>加锁顺序固定：订单头 → 订单行（id ASC）→ 台账 → 库存 → 台账新行（DOC/31 §6.8.1）。
     * 调用方的第一条数据库读必须就是本方法：FOR UPDATE 是当前读，能保证后续一致性读看到的已提交执行单不小于锁内状态。
     *
     * @param id 订单头ID
     * @return 锁定到的订单头；不存在或已逻辑删除返回 null
     */
    @Select("select * from biz_sale_order where id = #{id} and del_flag = '0' for update")
    ErpSaleOrder lockById(@Param("id") Long id);

    /**
     * 按主键集合锁定订单行，SQL 内固定 ORDER BY id ASC（不按 seq：两张单的 seq 与 id 顺序可能相反）。
     *
     * @param ids 订单行ID集合，调用方需保证非空（空集合不生成合法 SQL）
     * @return 锁定到的订单行，按 id 升序
     */
    @Select("""
        <script>
        select * from biz_sale_order_item
        where id in
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          and del_flag = '0'
        order by id asc
        for update
        </script>
        """)
    List<ErpSaleOrderItem> lockItemsByIds(@Param("ids") Collection<Long> ids);

    /**
     * 锁定某张订单的全部订单行，SQL 内固定 ORDER BY id ASC。
     *
     * @param orderId 销售订单ID
     * @return 锁定到的订单行，按 id 升序
     */
    @Select("select * from biz_sale_order_item where order_id = #{orderId} and del_flag = '0' order by id asc for update")
    List<ErpSaleOrderItem> lockItemsByOrderId(@Param("orderId") Long orderId);

    /**
     * 统计仍会占用订单量的销售出库单（DRAFT/CONFIRMED）：撤回/作废订单前必须为 0。
     *
     * @param orderId 销售订单ID
     * @return 未处理出库单张数
     */
    @Select("select count(*) from biz_sale where source_order_id = #{orderId} and del_flag = '0' and status in ('DRAFT', 'CONFIRMED')")
    int countActiveDeliveries(@Param("orderId") Long orderId);

    /**
     * 统计未确认（DRAFT）的销售出库单：关闭剩余前必须为 0，避免关闭后草稿单再确认导致口径打架。
     *
     * @param orderId 销售订单ID
     * @return 草稿出库单张数
     */
    @Select("select count(*) from biz_sale where source_order_id = #{orderId} and del_flag = '0' and status = 'DRAFT'")
    int countDraftDeliveries(@Param("orderId") Long orderId);

    /**
     * 订单关联的销售出库单执行记录（按单据汇总挂订单行的数量）。
     *
     * @param orderId 销售订单ID
     * @return 出库记录，type 恒为 DELIVERY
     */
    @Select("""
        select h.id       as id,
               h.no       as no,
               'DELIVERY' as type,
               h.biz_date as bizDate,
               sum(i.qty) as qty,
               h.total_amount as amount,
               h.status   as status
        from biz_sale h
        join biz_sale_item i on i.order_id = h.id and i.tenant_id = h.tenant_id
        where h.source_order_id = #{orderId}
          and h.tenant_id = #{tenantId} and i.tenant_id = #{tenantId}
          and h.del_flag = '0' and i.del_flag = '0'
          and i.source_order_item_id is not null
        group by h.id, h.no, h.biz_date, h.total_amount, h.status
        """)
    List<ErpSaleOrderVo.ExecRecordVo> selectDeliveries(@Param("orderId") Long orderId,
                                                       @Param("tenantId") String tenantId);

    /**
     * 订单关联的销售退货单执行记录（退货行 → 出库行 → 出库头两跳）。
     *
     * @param orderId 销售订单ID
     * @return 退货记录，type 恒为 RETURN
     */
    @Select("""
        select rh.id       as id,
               rh.no       as no,
               'RETURN'    as type,
               rh.biz_date as bizDate,
               sum(r.qty)  as qty,
               -rh.total_amount as amount,
               rh.status   as status
        from biz_sale_return_item r
        join biz_sale_return rh on rh.id = r.order_id and rh.tenant_id = r.tenant_id
        join biz_sale_item i on i.id = r.sale_item_id and i.tenant_id = r.tenant_id
        join biz_sale h on h.id = i.order_id and h.tenant_id = i.tenant_id
        where h.source_order_id = #{orderId}
          and h.tenant_id = #{tenantId} and i.tenant_id = #{tenantId}
          and rh.tenant_id = #{tenantId} and r.tenant_id = #{tenantId}
          and rh.sale_id = h.id
          and h.del_flag = '0' and rh.del_flag = '0' and r.del_flag = '0' and i.del_flag = '0'
          and i.source_order_item_id is not null
        group by rh.id, rh.no, rh.biz_date, rh.total_amount, rh.status
        """)
    List<ErpSaleOrderVo.ExecRecordVo> selectReturns(@Param("orderId") Long orderId,
                                                    @Param("tenantId") String tenantId);

    /**
     * 批量汇总列表页执行金额：出库正数、退货负数；只处理当前页订单 ID，避免前端逐单详情请求。
     */
    @Select("""
        <script>
        select x.order_id as orderId, sum(x.amount) as amount
        from (
            select h.source_order_id as order_id, h.total_amount as amount
            from biz_sale h
            where h.tenant_id = #{tenantId} and h.del_flag = '0' and h.status = 'CONFIRMED'
              and h.source_order_id in
              <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            union all
            select sh.source_order_id as order_id, -rh.total_amount as amount
            from biz_sale_return rh
            join biz_sale sh on sh.id = rh.sale_id and sh.tenant_id = rh.tenant_id
            where rh.tenant_id = #{tenantId} and sh.tenant_id = #{tenantId}
              and rh.del_flag = '0' and sh.del_flag = '0' and rh.status = 'CONFIRMED'
              and sh.source_order_id in
              <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ) x
        group by x.order_id
        </script>
        """)
    List<OrderExecutionAmountVo> selectExecutionAmounts(@Param("orderIds") Collection<Long> orderIds,
                                                         @Param("tenantId") String tenantId);
}
