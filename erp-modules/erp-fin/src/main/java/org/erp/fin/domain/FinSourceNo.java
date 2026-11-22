package org.erp.fin.domain;

import lombok.Data;

/**
 * 来源单号投影（biz_sale / biz_purchase 的 id + no）
 *
 * 用于应收应付台账回显来源单号；erp-fin 不反向依赖 erp-trade，直接以 SQL 投影业务单表。
 */
@Data
public class FinSourceNo {

    /**
     * 来源单据 id（biz_sale.id / biz_purchase.id）
     */
    private Long sourceId;

    /** 源业务单的本租户客户/供应商；防止损坏的台账引用泄露其它往来单号。 */
    private Long partyId;

    /**
     * 来源单号
     */
    private String no;
}
