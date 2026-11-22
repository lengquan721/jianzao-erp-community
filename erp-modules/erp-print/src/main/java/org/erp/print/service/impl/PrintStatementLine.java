package org.erp.print.service.impl;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class PrintStatementLine {
    private Date eventDate;
    private String documentNo;
    private String content;
    private String productName;
    private BigDecimal qty;
    private Integer qtyScale;
    private BigDecimal price;
    private BigDecimal signedAmount;
}
