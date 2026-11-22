package org.erp.print.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

/**
 * 打印模板 print_template
 *
 * scene: SALE_DELIVERY/STATEMENT/STATEMENT_RANGE/PURCHASE_IN/RECEIPT/PAYMENT；
 * engine 固定 THYMELEAF；每次保存 version+1；停用不删除保留审计。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("print_template")
public class PrintTemplate extends TenantEntity {

    @TableId(value = "id")
    private Long id;

    private String code;

    private String name;

    private String scene;

    private Long customerId;

    private String engine;

    private String variablesJson;

    private String content;

    /**
     * 设计器组件树 JSON，空表示源码模式
     */
    private String schemaJson;

    private String paper;

    private Integer version;

    private Integer isDefault;

    private Integer status;

    @TableLogic
    private String delFlag;
}
