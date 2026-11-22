package org.erp.stock.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.stock.domain.StOpening;
import org.erp.stock.domain.vo.StOpeningVo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 期初库存 Mapper
 */
public interface StOpeningMapper extends BaseMapperPlus<StOpening, StOpeningVo> {

    String OPENING_FILTER = """
        where o.del_flag = '0'
        <if test="warehouseId != null">and o.warehouse_id = #{warehouseId}</if>
        """;

    @Select("<script>select count(*) from st_opening o " + OPENING_FILTER + "</script>")
    long countDetails(@Param("warehouseId") Long warehouseId);

    // LEFT JOIN 不会丢失历史行；名称只取当前租户未删除的主数据。
    @Select("""
        <script>
        select o.id, o.product_id as productId, o.warehouse_id as warehouseId, o.qty,
               o.unit_cost as unitCost, o.status, o.create_time as createTime,
               p.code as productCode, p.name as productName, p.qty_scale as qtyScale,
               w.code as warehouseCode, w.name as warehouseName
        from st_opening o
        left join md_product p on p.id = o.product_id and p.tenant_id = o.tenant_id and p.del_flag = '0'
        left join md_warehouse w on w.id = o.warehouse_id and w.tenant_id = o.tenant_id and w.del_flag = '0'
        """ + OPENING_FILTER + """
        order by o.product_id, o.warehouse_id, o.id
        limit #{size} offset #{offset}
        </script>
        """)
    List<StOpeningVo> selectDetails(@Param("warehouseId") Long warehouseId, @Param("offset") long offset,
                                   @Param("size") int size);

    /**
     * 按 product_id + warehouse_id 查询已逻辑删除的期初行 ID。
     *
     * 唯一键 uk_st_opening 含 (tenant_id, product_id, warehouse_id) 不含 del_flag，
     * 逻辑删除后该唯一键仍被占用，重新录入同一产品+仓库必须复活原行而不是插入新行。
     * tenant_id 由多租户插件注入，不手写条件。
     *
     * @param productId   产品ID
     * @param warehouseId 仓库ID
     * @return 已删除行ID，不存在时返回 null
     */
    @Select("select id from st_opening where product_id = #{productId} and warehouse_id = #{warehouseId} and del_flag = '1' limit 1")
    Long selectDeletedId(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);

    /**
     * 复活已逻辑删除的期初行：del_flag 置回 0 并写入新值。
     *
     * 唯一键 uk_st_opening 不含 del_flag，删除后重建只能复用原行；
     * MyBatis-Plus 的 update 走逻辑删除过滤（自动附加 del_flag='0'），
     * 故这里用显式 update 语句复活。
     *
     * @param id         期初行ID
     * @param qty        期初数量
     * @param unitCost   期初成本
     * @return 影响行数
     */
    @Update("""
        update st_opening
        set del_flag = '0',
            qty = #{qty},
            unit_cost = #{unitCost},
            status = 'DRAFT'
        where id = #{id}
          and del_flag = '1'
        """)
    int reviveDeleted(@Param("id") Long id,
                      @Param("qty") BigDecimal qty,
                      @Param("unitCost") BigDecimal unitCost);
}
