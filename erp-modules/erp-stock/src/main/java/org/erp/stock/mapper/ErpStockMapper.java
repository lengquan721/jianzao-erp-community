package org.erp.stock.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.stock.domain.ErpStock;
import org.erp.stock.domain.vo.ErpStockQuantityVo;
import org.erp.stock.domain.vo.ErpStockSumVo;
import org.erp.stock.domain.vo.ErpStockVo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存快照 Mapper
 */
public interface ErpStockMapper extends BaseMapperPlus<ErpStock, ErpStockVo> {

    String BALANCE_FILTER = """
        from st_stock s
        left join md_product p on p.id = s.product_id and p.tenant_id = s.tenant_id and p.del_flag = '0'
        where s.del_flag = '0'
        <if test="type != null">and p.type = #{type}</if>
        <if test="pattern != null">
            and (lower(p.code) like #{pattern} escape '!'
              or lower(p.name) like #{pattern} escape '!'
              or lower(p.spec) like #{pattern} escape '!')
        </if>
        """;

    @Select("<script>select count(*) " + BALANCE_FILTER + "</script>")
    long countBalances(@Param("pattern") String pattern, @Param("type") String type);

    @Select("""
        <script>
        select s.product_id as productId, s.warehouse_id as warehouseId, s.qty, s.avg_cost as avgCost,
               p.code as productCode, p.name as productName, p.spec, p.unit, p.type,
               p.qty_scale as qtyScale, p.min_stock as minStock, p.max_stock as maxStock
        """ + BALANCE_FILTER + """
        order by s.product_id, s.warehouse_id, s.id
        limit #{size} offset #{offset}
        </script>
        """)
    List<ErpStockVo> selectBalancePage(@Param("pattern") String pattern, @Param("type") String type,
                                      @Param("offset") long offset, @Param("size") int size);

    @Select("""
        <script>
        select product_id as productId, sum(qty) as qty
        from st_stock where del_flag = '0' and product_id in
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        group by product_id
        order by product_id
        </script>
        """)
    List<ErpStockQuantityVo> selectQuantityTotals(@Param("ids") List<Long> ids);

    /**
     * 条件扣减：qty >= 扣减量才更新，影响行数 0 即库存不足（防超卖）。
     * tenant_id 由多租户插件注入。
     */
    @Update("""
        update st_stock
        set qty = qty - #{qty}
        where product_id = #{productId}
          and warehouse_id = #{warehouseId}
          and qty >= #{qty}
          and del_flag = '0'
        """)
    int deductQty(@Param("productId") Long productId,
                  @Param("warehouseId") Long warehouseId,
                  @Param("qty") BigDecimal qty);

    /**
     * 无条件回补（入库/反向流水）。
     */
    @Update("""
        update st_stock
        set qty = qty + #{qty}
        where product_id = #{productId}
          and warehouse_id = #{warehouseId}
          and del_flag = '0'
        """)
    int addQty(@Param("productId") Long productId,
               @Param("warehouseId") Long warehouseId,
               @Param("qty") BigDecimal qty);

    /**
     * 锁定库存行（事务内 SELECT ... FOR UPDATE），保证读-算-写原子。
     */
    @org.apache.ibatis.annotations.Select("""
        select * from st_stock
        where product_id = #{productId}
          and warehouse_id = #{warehouseId}
          and del_flag = '0'
        for update
        """)
    ErpStock selectForUpdate(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);

    /**
     * 按商品ID集合批量锁定库存行（事务内 SELECT ... FOR UPDATE），SQL 内固定 product_id、warehouse_id 升序。
     * 加锁顺序由 SQL 决定，调用方传入顺序不影响锁序（DOC/31 §6.8.1）。
     *
     * @param productIds 商品ID集合，调用方需保证非空（空集合不生成合法 SQL）
     * @return 锁定到的库存行，按 product_id、warehouse_id 升序
     */
    @org.apache.ibatis.annotations.Select("""
        <script>
        select * from st_stock
        where product_id in
        <foreach collection="productIds" item="productId" open="(" separator="," close=")">#{productId}</foreach>
          and del_flag = '0'
        order by product_id, warehouse_id
        for update
        </script>
        """)
    List<ErpStock> selectByProductIdsForUpdate(@Param("productIds") List<Long> productIds);

    /**
     * 更新移动加权平均成本。
     */
    @Update("""
        update st_stock
        set avg_cost = #{avgCost}
        where product_id = #{productId}
          and warehouse_id = #{warehouseId}
          and del_flag = '0'
        """)
    int updateAvgCost(@Param("productId") Long productId,
                      @Param("warehouseId") Long warehouseId,
                      @Param("avgCost") BigDecimal avgCost);

    /**
     * 按商品类型汇总库存数量与金额（Σ qty、Σ qty × avg_cost），供看板一次性取回成品/原料库存。
     *
     * <p>替代原先「全表 selectList 后再查一次商品类型、在内存按类型累加」的写法：
     * 看板每次刷新要取成品与原料两组数据，原实现会把整张 st_stock 扫两遍。
     * 这里交由数据库按类型分组聚合，只回两行。tenant_id 与 del_flag 由多租户插件注入。
     *
     * @param productType PRODUCT=成品 MATERIAL=原料
     * @return 数量与金额合计；无数据返回 qty=0、amount=0（SQL 内已 coalesce）
     */
    @org.apache.ibatis.annotations.Select("""
        select coalesce(sum(s.qty), 0) as qty,
               coalesce(sum(s.qty * s.avg_cost), 0) as amount
        from st_stock s
        join md_product p on p.id = s.product_id
        where p.type = #{productType}
          and p.del_flag = '0'
          and s.del_flag = '0'
        """)
    ErpStockSumVo sumStockByProductType(@Param("productType") String productType);
}
