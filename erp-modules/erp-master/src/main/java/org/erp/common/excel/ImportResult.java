package org.erp.common.excel;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 导入结果。
 */
@Data
public class ImportResult {

    private int total;
    private int success;
    private int skipped;
    private int failed;
    private static final int MAX_ERROR_DETAILS = 100;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private final List<ImportError> errors = new ArrayList<>();

    public void success() {
        total++;
        success++;
    }

    public void skipped() {
        total++;
        skipped++;
    }

    public void fail(int row, String message) {
        total++;
        failed++;
        if (errors.size() < MAX_ERROR_DETAILS) {
            String safeMessage = message == null ? "导入失败" : message;
            if (safeMessage.length() > MAX_ERROR_MESSAGE_LENGTH) {
                safeMessage = safeMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…";
            }
            errors.add(new ImportError(row, safeMessage));
        }
    }

    public void failAll(int rowCount, String message) {
        int count = Math.max(rowCount, 0);
        total += count;
        failed += count;
        int remaining = MAX_ERROR_DETAILS - errors.size();
        for (int index = 0; index < Math.min(count, remaining); index++) {
            int row = index + 2;
            String safeMessage = message == null ? "导入失败" : message;
            if (safeMessage.length() > MAX_ERROR_MESSAGE_LENGTH) {
                safeMessage = safeMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…";
            }
            errors.add(new ImportError(row, safeMessage));
        }
    }

    public String summary() {
        if (failed == 0) {
            return "导入完成：成功 " + success + " 行，跳过 " + skipped + " 行";
        }
        return "导入失败：共 " + total + " 行，成功 " + success + " 行，跳过 " + skipped
            + " 行，失败 " + failed + " 行";
    }
}
