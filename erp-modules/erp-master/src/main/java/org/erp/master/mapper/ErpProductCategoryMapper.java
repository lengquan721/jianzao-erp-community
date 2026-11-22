package org.erp.master.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.master.domain.ErpProductCategory;
import org.erp.master.domain.bo.MasterOptionQuery;
import org.erp.master.domain.vo.ErpProductCategoryVo;

import java.util.List;

/**
 * 商品分类 Mapper
 */
public interface ErpProductCategoryMapper extends BaseMapperPlus<ErpProductCategory, ErpProductCategoryVo> {

    String OPTION_SELECT = """
        <script>select m.id, m.code, m.name, m.sort, m.status, m.remark, m.create_time from md_product_category m
        """;

    @Select(OPTION_SELECT + MasterOptionSql.FILTER + MasterOptionSql.NAME_SEARCH
        + MasterOptionSql.ORDER_START + "m.sort, m.id" + MasterOptionSql.LIMIT)
    List<ErpProductCategoryVo> selectOptions(@Param("q") MasterOptionQuery query);

    @Select(OPTION_SELECT + MasterOptionSql.IDS)
    List<ErpProductCategoryVo> selectOptionsByIds(@Param("ids") List<Long> ids);

    /**
     * 按编码查询已逻辑删除的记录 ID（唯一键 tenant_id + code 不区分 del_flag）。
     *
     * @param code 分类编码
     * @return 已删除记录 ID，不存在时返回 null
     */
    @Select("select id from md_product_category where code = #{code} and del_flag = '1' limit 1")
    Long selectDeletedIdByCode(@Param("code") String code);
}
