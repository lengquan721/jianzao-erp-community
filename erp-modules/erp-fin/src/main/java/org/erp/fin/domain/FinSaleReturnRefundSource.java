package org.erp.fin.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/** 退款只能使用当前租户退货单上持久化的金额、账户及来源。 */
@Data
public class FinSaleReturnRefundSource {
    private String no;
    private Long saleId;
    private Long customerId;
    private Date bizDate;
    private String status;
    private BigDecimal totalAmount;
    private Integer needRefund;
    private BigDecimal refundAmount;
    private Long refundAccountId;
    private Long refundReceiptId;
}
