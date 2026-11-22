package org.erp.stock.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.erp.stock.domain.vo.ErpStockAlertTopRow;
import org.erp.stock.domain.vo.ErpStockAlertVo;

import java.util.List;

/** 库存上下限预警：完整明细和看板 TopN 共用相同判定与缺口口径。 */
public interface ErpStockAlertMapper {

    // 无库存行按 0；汇总排除已删除的快照。租户插件约束两张表，JOIN 也显式按租户等值。
    // 如果历史脏数据上下限倒挂、同时满足两种预警，确定为 LOW（正常写入有 min <= max 校验）。
    String CLASSIFIED_SQL = """
        select p.id as productId, p.code as productCode, p.name as productName,
               p.spec, p.unit, p.min_stock as minStock, p.max_stock as maxStock,
               coalesce(s.total_qty, 0) as totalQty,
               case when coalesce(s.total_qty, 0) &lt; p.min_stock then 'LOW'
                    when coalesce(s.total_qty, 0) &gt; p.max_stock then 'HIGH' end as alertType,
               case when coalesce(s.total_qty, 0) &lt; p.min_stock then coalesce(s.total_qty, 0) - p.min_stock
                    when coalesce(s.total_qty, 0) &gt; p.max_stock then coalesce(s.total_qty, 0) - p.max_stock end as gap
        from md_product p
        left join (select tenant_id, product_id, sum(qty) as total_qty
                   from st_stock
                   where del_flag = '0'
                   <if test="productId != null">and product_id = #{productId}</if>
                   group by tenant_id, product_id) s on s.product_id = p.id and s.tenant_id = p.tenant_id
        where p.del_flag = '0' and p.status = 1
          and (p.min_stock is not null or p.max_stock is not null)
        <if test="productId != null">and p.id = #{productId}</if>
        """;

    @Select("<script>select a.* from (" + CLASSIFIED_SQL + """
        ) a where a.alertType is not null
        <if test="alertType != null and alertType != ''">and a.alertType = #{alertType}</if>
        order by abs(a.gap) desc, a.productId asc
        </script>
        """)
    List<ErpStockAlertVo> selectAlerts(@Param("alertType") String alertType, @Param("productId") Long productId);

    /** MySQL 8：每类稳定 TopN + 未截断的每类计数；不把全量预警交给 JVM。 */
    @Select("""
        <script>
        select r.productId, r.productName, r.totalQty, r.gap, r.alertType, r.alertCount
        from (select a.productId, a.productName, a.totalQty, a.gap, a.alertType,
                     count(*) over (partition by a.alertType) as alertCount,
                     row_number() over (partition by a.alertType order by abs(a.gap) desc, a.productId asc) as alertRank
              from (
        """ + CLASSIFIED_SQL + """
              ) a where a.alertType is not null) r
        where r.alertRank &lt;= #{limit}
        order by r.alertType, r.alertRank
        </script>
        """)
    List<ErpStockAlertTopRow> selectTopAlerts(@Param("productId") Long productId, @Param("limit") int limit);
}
