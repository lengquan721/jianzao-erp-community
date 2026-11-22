package org.erp.fin.domain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 当前账户页的已确认净动账投影；不含期初，不落表，不作为接口返回值。
 */
@Data
public class FinAccountMovement {

    private Long accountId;

    private BigDecimal movement;
}
