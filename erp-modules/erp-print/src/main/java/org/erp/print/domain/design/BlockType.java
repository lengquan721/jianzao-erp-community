package org.erp.print.domain.design;

/**
 * 设计器区块类型
 *
 * <p>区块按顺序纵向排列，位置由顺序决定而非绝对坐标：
 * 打印场景的表格行数随单据变化，绝对定位会导致分页串行，因此在设计上就排除自由画布。
 */
public enum BlockType {

    /**
     * 标题：单据名称，居中大字
     */
    TITLE,

    /**
     * 纯文本：固定文案、说明、页脚备注
     */
    TEXT,

    /**
     * 字段：绑定单个变量，如单号、客户名称、日期
     */
    FIELD,

    /**
     * 图片：Logo，仅允许 data: URI（与 TemplateSanitizer 的外链策略一致）
     */
    IMAGE,

    /**
     * 表格：绑定集合变量，动态行数 + 表头列配置
     */
    TABLE,

    /**
     * 分割线
     */
    DIVIDER,

    /**
     * 签章区：制单 / 仓管 / 客户签收
     */
    SIGNATURE,

    /**
     * 分页符：强制换页
     */
    PAGE_BREAK,

    /**
     * 留白：纵向间距
     */
    SPACER
}
