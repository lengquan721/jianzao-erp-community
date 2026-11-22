package org.erp.master.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.master.domain.ErpProduct;
import org.erp.master.domain.bo.MasterOptionQuery;
import org.erp.master.domain.vo.ErpProductVo;

import java.util.List;

/**
 * 产品 Mapper
 */
public interface ErpProductMapper extends BaseMapperPlus<ErpProduct, ErpProductVo> {

    // 不按分类联表列排序，分类仍完整回传；不改变管理列表和 Excel 的原查询。
    String OPTION_SELECT = """
        <script>
        select m.id, m.code, m.name, m.spec, m.unit, m.type, m.category_id, m.supplier_id,
               c.name as categoryName, c.code as categoryCode, c.sort as categorySort,
               s.name as supplierName, s.code as supplierCode,
               m.qty_scale, m.cost_price, m.purchase_price, m.tax_type, m.freight,
               m.min_stock, m.max_stock, m.status, m.remark, m.create_time
        from md_product m
        left join md_product_category c on c.id = m.category_id and c.tenant_id = m.tenant_id and c.del_flag = '0'
        left join md_supplier s on s.id = m.supplier_id and s.tenant_id = m.tenant_id and s.del_flag = '0'
        """;

    @Select(OPTION_SELECT + MasterOptionSql.FILTER + MasterOptionSql.PRODUCT_SEARCH + """
        <if test="type != null">and m.type = #{type}</if>
        <if test="categoryId != null">and m.category_id = #{categoryId}</if>
        """ + MasterOptionSql.ORDER_START + "m.code, m.id" + MasterOptionSql.LIMIT)
    List<ErpProductVo> selectOptions(@Param("q") MasterOptionQuery query, @Param("type") String type,
                                     @Param("categoryId") Long categoryId);

    @Select(OPTION_SELECT + MasterOptionSql.IDS)
    List<ErpProductVo> selectOptionsByIds(@Param("ids") List<Long> ids);

    /**
     * 商品列表联查 SQL：分类与供应商只关联未删除记录，分类被逻辑删除时 categoryName 为 null，
     * 前端据此归入「未分类」；按分类 sort、id 排序保证折叠分组顺序稳定（DOC/26 §5.3）。
     * tenant_id 由多租户插件注入，不手写条件。
     */
    String DETAIL_SQL = """
        <script>
        select p.id             as id,
               p.code           as code,
               p.name           as name,
               p.spec           as spec,
               p.unit           as unit,
               p.type           as type,
               p.category_id    as categoryId,
               c.name           as categoryName,
               c.code           as categoryCode,
               c.sort           as categorySort,
               p.supplier_id    as supplierId,
               s.name           as supplierName,
               s.code           as supplierCode,
               p.qty_scale      as qtyScale,
               p.cost_price     as costPrice,
               p.purchase_price as purchasePrice,
               p.tax_type       as taxType,
               p.freight        as freight,
               p.min_stock      as minStock,
               p.max_stock      as maxStock,
               p.status         as status,
               p.remark         as remark,
               p.create_time    as createTime
        from md_product p
        left join md_product_category c on c.id = p.category_id and c.del_flag = '0'
        left join md_supplier s on s.id = p.supplier_id and s.del_flag = '0'
        where p.del_flag = '0'
        <if test="keyword != null and keyword != ''">
            and (p.name like concat('%', #{keyword}, '%')
                 or p.code like concat('%', #{keyword}, '%')
                 or p.spec like concat('%', #{keyword}, '%'))
        </if>
        <if test="type != null and type != ''"> and p.type = #{type} </if>
        <if test="status != null"> and p.status = #{status} </if>
        <if test="categoryId != null"> and p.category_id = #{categoryId} </if>
        <if test="supplierId != null"> and p.supplier_id = #{supplierId} </if>
        order by c.sort, c.id, p.code
        </script>
        """;

    /**
     * 商品分页列表（含分类名称、分类排序、供应商名称）。
     *
     * @param page       分页参数
     * @param keyword    编码/名称/规格模糊匹配
     * @param type       成品/原料，null=全部
     * @param status     状态，null=全部
     * @param categoryId 分类ID，null=全部
     * @param supplierId 供应商ID，null=全部
     * @return 分页结果
     */
    @Select(DETAIL_SQL)
    Page<ErpProductVo> selectVoPageDetail(Page<ErpProductVo> page,
                                          @Param("keyword") String keyword,
                                          @Param("type") String type,
                                          @Param("status") Integer status,
                                          @Param("categoryId") Long categoryId,
                                          @Param("supplierId") Long supplierId);

    /**
     * 商品全量列表（含分类名称、分类排序、供应商名称），供下拉与导出使用。
     *
     * @param keyword    编码/名称/规格模糊匹配
     * @param type       成品/原料，null=全部
     * @param status     状态，null=全部
     * @param categoryId 分类ID，null=全部
     * @param supplierId 供应商ID，null=全部
     * @return 商品列表
     */
    @Select(DETAIL_SQL)
    List<ErpProductVo> selectVoListDetail(@Param("keyword") String keyword,
                                          @Param("type") String type,
                                          @Param("status") Integer status,
                                          @Param("categoryId") Long categoryId,
                                          @Param("supplierId") Long supplierId);

    /**
     * 按编码查询已逻辑删除的记录 ID（唯一键 tenant_id + code 不区分 del_flag）。
     *
     * @param code 产品编码
     * @return 已删除记录 ID，不存在时返回 null
     */
    @Select("select id from md_product where code = #{code} and del_flag = '1' limit 1")
    Long selectDeletedIdByCode(@Param("code") String code);
}
