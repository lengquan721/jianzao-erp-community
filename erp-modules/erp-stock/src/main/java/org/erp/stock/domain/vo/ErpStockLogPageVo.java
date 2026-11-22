package org.erp.stock.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.List;

/** 按 (bizDate, id) 倒序的流水游标页；只在 hasMore 时返回下一页游标。 */
public record ErpStockLogPageVo(List<ErpStockLogVo> rows, boolean hasMore,
                                @JsonFormat(pattern = "yyyy-MM-dd") LocalDate nextBeforeDate,
                                Long nextBeforeId) {
}
