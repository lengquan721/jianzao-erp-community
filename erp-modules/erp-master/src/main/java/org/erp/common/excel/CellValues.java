package org.erp.common.excel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Excel 单元格校验与转换的公共方法。
 */
public final class CellValues {

    private CellValues() {
    }

    public static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String text(String value) {
        return value == null ? null : value.trim();
    }

    public static String required(String value, String label, int maxLength, List<String> errors) {
        String text = text(value);
        if (blank(text)) {
            errors.add(label + "不能为空");
            return null;
        }
        if (text.length() > maxLength) {
            errors.add(label + "不能超过" + maxLength + "个字符");
        }
        return text;
    }

    public static String optional(String value, String label, int maxLength, List<String> errors) {
        String text = text(value);
        if (text != null && text.length() > maxLength) {
            errors.add(label + "不能超过" + maxLength + "个字符");
        }
        return text;
    }

    /** Backward-compatible default precision for other ERP importers; callers with known schema should pass precision explicitly. */
    public static BigDecimal decimal(String value, String label, int maxScale, boolean required,
                                     List<String> errors) {
        return decimal(value, label, 18, maxScale, required, errors);
    }

    public static BigDecimal decimal(String value, String label, int maxPrecision, int maxScale,
                                     boolean required, List<String> errors) {
        String text = text(value);
        if (blank(text)) {
            if (required) {
                errors.add(label + "不能为空");
                return null;
            }
            return BigDecimal.ZERO;
        }
        // Bound lexical work, reject exponent notation, NaN/Infinity and non-decimal formats before BigDecimal.
        if (text.length() > 64 || !text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            errors.add(label + "必须是长度不超过64位的普通十进制数字");
            return null;
        }
        try {
            BigDecimal number = new BigDecimal(text);
            BigDecimal normalized = number.stripTrailingZeros();
            int effectiveScale = Math.max(normalized.scale(), 0);
            int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
            if (effectiveScale > maxScale) {
                errors.add(label + "最多保留" + maxScale + "位小数");
                return null;
            }
            if (integerDigits > maxPrecision - maxScale || normalized.precision() > maxPrecision) {
                errors.add(label + "超出数据库DECIMAL(" + maxPrecision + "," + maxScale + ")范围");
                return null;
            }
            return number;
        } catch (NumberFormatException e) {
            errors.add(label + "必须是数字");
            return null;
        }
    }

    public static Integer status(String value, List<String> errors) {
        String text = text(value);
        if (blank(text)) {
            return 1;
        }
        if ("启用".equals(text) || "1".equals(text)) {
            return 1;
        }
        if ("停用".equals(text) || "0".equals(text)) {
            return 0;
        }
        errors.add("状态必须是「启用」或「停用」");
        return null;
    }

    public static String choice(String value, Map<String, String> mappings, String label, boolean required,
                                List<String> errors) {
        String text = text(value);
        if (blank(text)) {
            if (required) {
                errors.add(label + "不能为空");
            }
            return null;
        }
        String mapped = mappings.get(text);
        if (mapped == null) {
            errors.add(label + "取值无效：" + text);
        }
        return mapped;
    }

    /**
     * 解析日期：兼容 yyyy-MM-dd 与 yyyy/M/d（用户从表格粘贴的常见格式）。
     *
     * @param value    单元格文本
     * @param label    列名，用于错误提示
     * @param required 是否必填
     * @param errors   错误收集器
     * @return 解析结果，格式错误或空值返回 null
     */
    public static LocalDate date(String value, String label, boolean required, List<String> errors) {
        String text = text(value);
        if (blank(text)) {
            if (required) {
                errors.add(label + "不能为空");
            }
            return null;
        }
        if (text.length() > 10 || !text.matches("[0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}")) {
            errors.add(label + "格式必须是 yyyy-MM-dd");
            return null;
        }
        try {
            // STRICT 解析：避免 SMART 把 2026-02-30 静默纠成 2026-02-28
            return LocalDate.parse(text.replace('/', '-'),
                DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(java.time.format.ResolverStyle.STRICT));
        } catch (DateTimeParseException e) {
            errors.add(label + "格式必须是 yyyy-MM-dd");
            return null;
        }
    }
}
