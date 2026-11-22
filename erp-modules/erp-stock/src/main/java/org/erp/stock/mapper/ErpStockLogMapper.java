package org.erp.stock.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.stock.domain.ErpStockLog;
import org.erp.stock.domain.vo.ErpStockLogVo;

import java.time.LocalDate;
import java.util.List;

/** 库存流水 Mapper；自定义 SQL 显式过滤逻辑删除，租户插件约束租户。 */
public interface ErpStockLogMapper extends BaseMapperPlus<ErpStockLog, ErpStockLogVo> {

    @Select("""
        <script>
        select id, biz_type, biz_no, biz_id, product_id, warehouse_id, qty, unit_cost, amount,
               partner_type, partner_id, balance_after, biz_date, seq
        from st_stock_log
        where del_flag = '0' and product_id = #{productId}
          and biz_date between #{startDate} and #{endDate}
        <if test="beforeDate != null">
          and (biz_date &lt; #{beforeDate} or (biz_date = #{beforeDate} and id &lt; #{beforeId}))
        </if>
        order by biz_date desc, id desc
        limit #{fetchSize}
        </script>
        """)
    List<ErpStockLogVo> selectLogPage(@Param("productId") Long productId,
                                     @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
                                     @Param("beforeDate") LocalDate beforeDate, @Param("beforeId") Long beforeId,
                                     @Param("fetchSize") int fetchSize);

    /** 仅发现锁键，不能用此一致性快照判断是否已冲销。 */
    @Select("""
        select distinct product_id, warehouse_id
        from st_stock_log
        where del_flag = '0' and biz_no = #{bizNo} and biz_type != 'VOID'
        order by product_id, warehouse_id
        """)
    List<ErpStockLog> selectBillStockKeys(@Param("bizNo") String bizNo);

    /** 拿到库存行锁之后再做当前读，一次取回原行与配对的 VOID。 */
    @Select("""
        <script>
        select id, biz_type, product_id, warehouse_id, qty, unit_cost, amount, partner_id
        from st_stock_log
        where del_flag = '0' and biz_no = #{bizNo}
        <if test="productId != null">and product_id = #{productId} and warehouse_id = #{warehouseId}</if>
        order by id
        for update
        </script>
        """)
    List<ErpStockLog> selectBillRowsForUpdate(@Param("bizNo") String bizNo, @Param("productId") Long productId,
                                            @Param("warehouseId") Long warehouseId);

    /** 必须已持有库存行锁；当前读防止外层事务的旧 RR 快照漏掉同期已提交流水。 */
    @Select("""
        select id, biz_type, qty, unit_cost
        from st_stock_log
        where del_flag = '0' and product_id = #{productId} and warehouse_id = #{warehouseId}
        order by id
        for update
        """)
    List<ErpStockLog> selectReplayRowsForUpdate(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);
}
