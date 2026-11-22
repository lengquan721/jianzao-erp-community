package org.erp.print.service.impl;

import lombok.Data;

import java.math.BigDecimal;

/** Signed aggregation by document type before and during a statement period. */
@Data
public class PrintLedgerMovement {
    private String sourceType;
    private BigDecimal priorAmount;
    private BigDecimal periodAmount;
}
