package org.erp.fin.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 汇总分页：金额为该往来类型全部非 VOID 台账的合计，parties 仅为当前页。
 * total 是匹配的往来单位数，不是台账行数或当前页长度。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FinArApSummaryPageVo extends FinArApSummaryVo {

    private long total;
}
