package org.erp.common.excel;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 读取 Excel 数据行并保留行号，供逐行错误提示使用。
 */
public final class ExcelRowReader {

    private static final int MAX_DATA_ROWS = 2000;
    private static final int MAX_COLUMNS = 64;

    private ExcelRowReader() {
    }

    public static <T> List<ExcelRow<T>> read(InputStream inputStream, Class<T> clazz) {
        List<ExcelRow<T>> rows = new ArrayList<>();
        try {
            FastExcel.read(inputStream, clazz, new AnalysisEventListener<T>() {
                @Override
                public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                    if (headMap.size() > MAX_COLUMNS
                        || (!headMap.isEmpty() && headMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) >= MAX_COLUMNS)) {
                        throw new ImportLimitException(1, "Excel 列数不能超过" + MAX_COLUMNS + "列");
                    }
                }

                @Override
                public void invoke(T data, AnalysisContext context) {
                    int rowNumber = context.readRowHolder().getRowIndex() + 1;
                    Map<Integer, ?> cells = context.readRowHolder().getCellMap();
                    if (cells.size() > MAX_COLUMNS
                        || (!cells.isEmpty() && cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) >= MAX_COLUMNS)) {
                        throw new ImportLimitException(rowNumber, "Excel 列数不能超过" + MAX_COLUMNS + "列");
                    }
                    rows.add(new ExcelRow<>(rowNumber, data));
                    if (rows.size() > MAX_DATA_ROWS) {
                        // A single sentinel row tells the import service to reject the whole file.
                        // Abort parsing immediately instead of retaining the rest of a hostile sheet.
                        throw new TooManyRowsException();
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // no-op
                }
            }).sheet().doRead();
        } catch (RuntimeException e) {
            ImportLimitException limit = findLimit(e);
            if (limit != null) {
                throw limit;
            }
            if (!isTooManyRows(e)) {
                throw e;
            }
        }
        return rows;
    }

    private static ImportLimitException findLimit(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ImportLimitException limit) {
                return limit;
            }
        }
        return null;
    }

    private static boolean isTooManyRows(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TooManyRowsException) {
                return true;
            }
        }
        return false;
    }

    public static final class ImportLimitException extends RuntimeException {
        private final int rowNumber;

        public ImportLimitException(int rowNumber, String message) {
            super(message);
            this.rowNumber = rowNumber;
        }

        public int rowNumber() {
            return rowNumber;
        }
    }

    private static final class TooManyRowsException extends RuntimeException {
        private TooManyRowsException() {
            super("Excel data row limit exceeded");
        }
    }
}
