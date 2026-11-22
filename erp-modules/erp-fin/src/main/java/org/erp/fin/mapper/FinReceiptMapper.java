package org.erp.fin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.domain.FinSaleReturnRefundSource;
import org.erp.fin.domain.vo.FinReceiptVo;

/**
 * 收款单 Mapper
 */
public interface FinReceiptMapper extends BaseMapperPlus<FinReceipt, FinReceiptVo> {

    /**
     * 按主键锁定收款单头（SELECT ... FOR UPDATE）。
     *
     * <p>先取稳定往来锁，再锁本单头，最后按原顺序锁台账；头锁/CAS 防重复核销。
     *
     * @param id 收款单ID
     * @return 锁定到的单据；不存在或已逻辑删除返回 null
     */
    @Select("select * from fin_receipt where id = #{id} and del_flag = '0' for update")
    FinReceipt lockById(@Param("id") Long id);

    @Select("""
        select no, sale_id, customer_id, biz_date, status, total_amount, need_refund,
               refund_amount, refund_account_id, refund_receipt_id
        from biz_sale_return
        where id = #{id} and del_flag = '0'
        for update
        """)
    FinSaleReturnRefundSource lockRefundSource(@Param("id") Long id);
}
