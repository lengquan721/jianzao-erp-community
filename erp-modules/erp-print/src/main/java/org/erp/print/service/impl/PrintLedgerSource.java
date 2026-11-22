package org.erp.print.service.impl;

import lombok.Data;

import java.util.Date;

@Data
public class PrintLedgerSource {
    private Long id;
    private String sourceType;
    private Long sourceId;
    private Date bizDate;
}
