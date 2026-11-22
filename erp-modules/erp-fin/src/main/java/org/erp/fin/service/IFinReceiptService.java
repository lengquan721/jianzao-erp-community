package org.erp.fin.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.fin.domain.bo.FinReceiptBo;
import org.erp.fin.domain.vo.FinReceiptVo;

/**
 * 收款单服务
 *
 * 确认落核销（fin_receipt_alloc + fin_ar_ap.settled_amount）；
 * 撤回先反核销；作废走撤回。
 */
public interface IFinReceiptService {

    TableDataInfo<FinReceiptVo> selectPage(Long customerId, String status, PageQuery pageQuery);

    FinReceiptVo selectById(Long id);

    FinReceiptVo insert(FinReceiptBo bo);

    /** 仅在销售退货确认事务内使用，从锁定的退货单读取退款账户、金额及来源。 */
    Long insertSaleReturnRefund(Long returnId);

    FinReceiptVo update(Long id, FinReceiptBo bo);

    void confirm(Long id);

    void withdraw(Long id);

    /** 仅在销售退货撤回事务内使用，读取并验证该退货单实际关联的红字收款。 */
    void withdrawSaleReturnRefund(Long returnId);

    void voidBill(Long id);

    /**
     * 删除草稿收款单（逻辑删除，仅 DRAFT 可删）
     *
     * @param id 收款单ID
     */
    void deleteDraft(Long id);
}
