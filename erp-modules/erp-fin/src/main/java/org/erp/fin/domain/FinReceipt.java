package org.erp.fin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 收款单 fin_receipt
 *
 * 独立单据：DRAFT → CONFIRMED → DRAFT/VOID；核销明细落 fin_receipt_alloc。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_receipt")
public class FinReceipt extends TenantEntity {

    @TableId(value = "id")
    private Long id;

    private String no;

    private Long customerId;

    private BigDecimal amount;

    private Date bizDate;

    private String account;

    /**
     * 结算账户ID（DOC/25：新单必填，存量单为空沿用 account 文本）
     */
    private Long accountId;

    /** 仅销售退货联动生成：资金方向为流出，确认时对原 SALE 应收执行受控反核销。 */
    private Integer negative;

    private String remark;

    private String status;

    private Long confirmedBy;

    private Date confirmedAt;

    private Long withdrawnBy;

    private Date withdrawnAt;

    private Long voidedBy;

    private Date voidedAt;

    /**
     * 逻辑删除标志：0存在 1删除（草稿删除只置位，不物理删行，单号不回收）
     */
    @TableLogic
    private String delFlag;
}
