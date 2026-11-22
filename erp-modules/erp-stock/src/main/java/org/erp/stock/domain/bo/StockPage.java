package org.erp.stock.domain.bo;

import org.dromara.common.core.exception.ServiceException;

/** 库存表格固定排序，用显式 LIMIT/OFFSET；越界不回卷到第一页。 */
public record StockPage(long offset, int size) {

    public static final String DEFAULT_SIZE = "50";
    public static final int MAX_SIZE = 100;

    public static StockPage of(int pageNum, int size) {
        if (pageNum < 1) {
            throw new ServiceException("pageNum 必须大于 0", 400);
        }
        return new StockPage((pageNum - 1L) * checkSize(size), size);
    }

    public static int checkSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new ServiceException("pageSize 必须在 1–" + MAX_SIZE + " 之间", 400);
        }
        return size;
    }
}
