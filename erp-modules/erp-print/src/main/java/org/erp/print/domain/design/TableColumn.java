package org.erp.print.domain.design;

import lombok.Data;

/**
 * 表格列配置
 *
 * <p>列在渲染期按 visible 过滤、按本列表顺序输出；
 * key 对应数据源对象的字段名（如 items[].name → "name"）。
 */
@Data
public class TableColumn {

    /**
     * 数据源字段名，如 name/spec/unit/qty/price/amount
     */
    private String key;

    /**
     * 表头文字
     */
    private String label;

    /**
     * 列宽百分比（1-100），null 表示自适应
     */
    private Integer width;

    /**
     * 对齐：left / center / right
     */
    private String align;

    /**
     * 是否显示，false 的列不进模板
     */
    private Boolean visible;

    /**
     * 是否按数值格式右对齐（金额、数量）
     */
    private Boolean numeric;

    public boolean isShown() {
        return visible == null || Boolean.TRUE.equals(visible);
    }

    public boolean isNumericColumn() {
        return Boolean.TRUE.equals(numeric);
    }
}
