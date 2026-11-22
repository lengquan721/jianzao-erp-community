package org.erp.print.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模板变量字典项：供设计器左侧字段列表渲染
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintVariableVo {

    /**
     * 变量路径，如 doc.no / partner.name / totals.amountTotal
     */
    private String key;

    /**
     * 中文名称，如「单号」
     */
    private String label;

    /**
     * 示例值，帮助客户理解该字段会显示什么
     */
    private String example;

    /**
     * 分组：文档 / 往来单位 / 合计 / 对账 / 其他
     */
    private String group;
}
