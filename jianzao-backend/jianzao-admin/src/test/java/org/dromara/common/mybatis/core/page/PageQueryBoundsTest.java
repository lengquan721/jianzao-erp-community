package org.dromara.common.mybatis.core.page;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class PageQueryBoundsTest {

    @Test
    void defaultsToTwentyAndRejectsOversizedPages() {
        assertEquals(20L, new PageQuery(null, null).build().getSize());
        assertEquals(200L, new PageQuery(200, 1).build().getSize());
        assertThrows(ServiceException.class, () -> new PageQuery(201, 1).build());
        assertThrows(ServiceException.class, () -> new PageQuery(0, 1).build());
    }

    @Test
    void offsetUsesLongArithmeticInsteadOfIntegerOverflow() {
        PageQuery query = new PageQuery(Integer.MAX_VALUE, Integer.MAX_VALUE);
        long expected = ((long) Integer.MAX_VALUE - 1L) * Integer.MAX_VALUE;
        assertEquals(expected, query.getFirstNum());
    }

    @Test
    void exportCollectionUsesFixedSafePagesAndHonorsSeparateFiveThousandRowBound() {
        List<Integer> requestedPageSizes = new ArrayList<>();
        var rows = PageQuery.collectForExport(450, query -> {
            requestedPageSizes.add(query.getPageSize());
            int start = (query.getPageNum() - 1) * query.getPageSize();
            int end = Math.min(450, start + query.getPageSize());
            return IntStream.range(start, end).boxed().toList();
        });
        assertEquals(450, rows.size());
        assertEquals(449, rows.get(449));
        assertEquals(List.of(200, 200, 200), requestedPageSizes,
            "the final partial page must keep its original offset stride");

        List<Integer> maximumPageSizes = new ArrayList<>();
        var maximumRows = PageQuery.collectForExport(5000, query -> {
            maximumPageSizes.add(query.getPageSize());
            int start = (query.getPageNum() - 1) * query.getPageSize();
            return IntStream.range(start, Math.min(5000, start + query.getPageSize())).boxed().toList();
        });
        assertEquals(5000, maximumRows.size());
        assertEquals(4999, maximumRows.get(4999));
        assertEquals(25, maximumPageSizes.size());
        assertEquals(List.of(200, 200, 200), maximumPageSizes.subList(0, 3));
        assertEquals(5000, maximumPageSizes.stream().mapToInt(Integer::intValue).sum());
        assertThrows(ServiceException.class, () -> PageQuery.collectForExport(5001, q -> List.of()));
    }
}
