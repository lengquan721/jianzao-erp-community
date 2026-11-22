package org.erp.fin.domain.vo;

/** 当前主数据页的已确认原始期初金额；只有这两个精确字符串，不暴露余额/日期/主数据。 */
public record FinOpeningConfirmedAmountVo(String partyId, String amount) {
}
