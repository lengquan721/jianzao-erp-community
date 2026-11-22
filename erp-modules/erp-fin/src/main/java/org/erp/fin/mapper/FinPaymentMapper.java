package org.erp.fin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.fin.domain.FinPayment;
import org.erp.fin.domain.vo.FinPaymentVo;

/**
 * 付款单 Mapper
 */
public interface FinPaymentMapper extends BaseMapperPlus<FinPayment, FinPaymentVo> {

    /**
     * 按主键锁定付款单头（SELECT ... FOR UPDATE）。
     *
     * <p>先取稳定往来锁，再锁本单头，最后按原顺序锁台账；头锁/CAS 防重复核销。
     *
     * @param id 付款单ID
     * @return 锁定到的单据；不存在或已逻辑删除返回 null
     */
    @Select("select * from fin_payment where id = #{id} and del_flag = '0' for update")
    FinPayment lockById(@Param("id") Long id);
}
