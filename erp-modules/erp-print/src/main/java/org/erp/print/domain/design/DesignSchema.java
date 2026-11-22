package org.erp.print.domain.design;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 设计器组件树（对应 print_template.schema_json）
 *
 * <p>区块按 handlebars 顺序纵向排列，位置由顺序决定而非绝对坐标：
 * 打印场景的表格行数随单据变化，绝对定位会导致分页串行，因此在设计上就排除自由画布。
 */
@Data
public class DesignSchema {

    /**
     * 结构版本，便于后续组件树格式演进时做兼容
     */
    private Integer schemaVersion = 1;

    /**
     * 纸张：A4 / A5 / 241x140 / 241x93 / 其他有效宽x高 mm
     */
    private String paper = "A4";

    /**
     * 页边距（mm）
     */
    private Integer pageMargin = 12;

    /**
     * 正文字体
     */
    private String fontFamily = "\"Microsoft YaHei\", \"PingFang SC\", sans-serif";

    /**
     * 基准字号（px）
     */
    private Integer fontSize = 12;

    private List<DesignBlock> blocks = new ArrayList<>();
}
