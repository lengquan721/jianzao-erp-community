package org.erp.print.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 打印模板保存请求（新增/编辑共用）
 */
@Data
public class PrintTemplateBo {

    /**
     * 编辑时必填，新增为空
     */
    private Long id;

    @NotBlank(message = "模板编码不能为空")
    private String code;

    @NotBlank(message = "模板名称不能为空")
    private String name;

    @NotBlank(message = "场景不能为空")
    private String scene;

    private Long customerId;

    private String variablesJson;

    /**
     * 模板内容（HTML + Thymeleaf）。
     * 可视化设计器提交时可不填：后端按 schemaJson 编译生成后回写。
     */
    private String content;

    /**
     * 设计器组件树 JSON；为空表示该模板用 HTML 源码模式编辑
     */
    private String schemaJson;

    private String paper;

    private Integer isDefault;

    /**
     * 1 启用 / 0 停用
     */
    private Integer status;
}
