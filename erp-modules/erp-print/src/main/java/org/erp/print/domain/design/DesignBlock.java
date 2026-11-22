package org.erp.print.domain.design;

import lombok.Data;

/**
 * 设计器区块
 *
 * <p>props 与 style 是弱类型 Map：不同 BlockType 用到的键不同，
 * 强类型化会让前后端加字段都要改类，这里以编译期校验代替。
 */
@Data
public class DesignBlock {

    /**
     * 区块唯一标识，前端拖拽排序与选中用
     */
    private String id;

    private BlockType type;

    /**
     * 类型相关属性。
     * <ul>
     *   <li>TITLE: text, subText</li>
     *   <li>TEXT: text</li>
     *   <li>FIELD: fieldKey（变量路径）, label, showLabel</li>
     *   <li>IMAGE: src（data: URI）, width, height</li>
     *   <li>TABLE: dataSource（items / statement.items）, columns（List&lt;TableColumn&gt;）, showHeader, bordered</li>
     *   <li>DIVIDER: thickness, color</li>
     *   <li>SIGNATURE: labels（List&lt;String&gt;）</li>
     *   <li>SPACER: height</li>
     * </ul>
     */
    private java.util.Map<String, Object> props;

    /**
     * 通用样式：fontSize / fontWeight / textAlign / color / marginTop / marginBottom / padding
     */
    private java.util.Map<String, Object> style;
}
