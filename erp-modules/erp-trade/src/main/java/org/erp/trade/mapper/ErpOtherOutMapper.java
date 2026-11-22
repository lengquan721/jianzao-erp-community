package org.erp.trade.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.trade.domain.ErpOtherOut;
import org.erp.trade.domain.vo.ErpOtherOutVo;

/**
 * 其他出库单 Mapper
 */
public interface ErpOtherOutMapper extends BaseMapperPlus<ErpOtherOut, ErpOtherOutVo> {

    /**
     * 按主键锁定其他出库单头（SELECT ... FOR UPDATE）。
     *
     * <p>确认/撤回/作废的第一把锁必须是本方法，与销售单保持一致的加锁顺序
     * （单据头 → 库存），避免并发确认时重复扣减库存。
     *
     * @param id 其他出库单ID
     * @return 锁定到的单据；不存在或已逻辑删除返回 null
     */
    @Select("select * from biz_other_out where id = #{id} and del_flag = '0' for update")
    ErpOtherOut lockById(@Param("id") Long id);
}
