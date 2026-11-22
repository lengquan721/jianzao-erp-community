package org.erp.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 销售退货单 biz_sale_return（DOC/24）
 *
 * 必须关联 CONFIRMED 原销售单；按行退、累计不超原行数量；
 * 确认后：库存回增（原行成本快照）、应收红冲（负数 AR 行）、可选红字收款单退款。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_sale_return")
public class ErpSaleReturn extends TenantEntity {

    @TableId(value = "id")
    private Long id;

    private String no;

    private Long saleId;

    /**
     * 原单号快照（列表/审计展示）
     */
    private String saleNo;

    private Long customerId;

    private Date bizDate;

    private String status;

    private BigDecimal totalAmount;

    /**
     * 成本回冲额 = Σ行 return_cost，确认时回填
     */
    private BigDecimal returnCostAmount;

    /**
     * 是否退款给客户
     */
    private Integer needRefund;

    private BigDecimal refundAmount;

    /** 草稿持久化；取消退款时同步清空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long refundAccountId;

    /**
     * 联动红字收款单ID，确认时回填，撤回时清空
     */
    private Long refundReceiptId;

    private String remark;

    private Long confirmedBy;

    private Date confirmedAt;

    private Long withdrawnBy;

    private Date withdrawnAt;

    private Long voidedBy;

    private Date voidedAt;

    /**
     * 逻辑删除标志：0存在 1删除；草稿单据删除走逻辑删除，单号不回收
     */
    @TableLogic
    private String delFlag;
}
