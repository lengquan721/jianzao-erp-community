package org.erp.master.domain.vo;

import java.util.List;

/** 远程选择器的无 COUNT 页；只在有下一页时返回 nextPageNum。 */
public record MasterOptionPageVo<T>(List<T> rows, boolean hasMore, Long nextPageNum) {
}
