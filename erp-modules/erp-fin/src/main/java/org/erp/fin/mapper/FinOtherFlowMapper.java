package org.erp.fin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.fin.domain.FinOtherFlow;
import org.erp.fin.domain.vo.FinOtherFlowVo;

/**
 * 其他收支 Mapper
 */
public interface FinOtherFlowMapper extends BaseMapperPlus<FinOtherFlow, FinOtherFlowVo> {

    /** Lock the bill header before editing or changing state. */
    @Select("select * from fin_other_flow where id = #{id} and del_flag = '0' for update")
    FinOtherFlow lockById(@Param("id") Long id);
}
