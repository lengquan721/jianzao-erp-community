package org.erp.fin.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 账户收支流水 VO（DOC/25 §3.5：收/付/其他收支 UNION）
 */
@Data
public class FinAccountFlowVo {

    /**
     * RECEIPT/PAYMENT/OTHER_FLOW
     */
    private String bizType;

    private String bizNo;

    private Date bizDate;

    private BigDecimal amount;

    /**
     * IN=入账（普通收款/其他收入），OUT=出账（退款/付款/其他支出）
     */
    private String direction;

    /**
     * 客户/供应商/摘要
     */
    private String counterparty;

    /**
     * 流水行 id（用于 UNION 排序稳定）
     */
    private Long rowId;

    @Data
    public static class Page {
        private long total;
        private List<FinAccountFlowVo> rows = new ArrayList<>();
    }
}
