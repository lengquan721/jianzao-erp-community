package org.erp.print.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.mapper.ErpCustomerMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.domain.bo.PrintTemplateBo;
import org.erp.print.domain.design.DesignSchema;
import org.erp.print.domain.vo.PrintTemplateVo;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.mapper.PrintTemplateMapper;
import org.erp.print.service.IPrintTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 打印模板服务实现
 *
 * 规则（DOC/14）：code 租户内唯一；保存清洗 + version+1；停用不删除；
 * 渲染解析顺序 客户专属 → 租户默认 → null（内置兜底）。
 */
@RequiredArgsConstructor
@Service
public class PrintTemplateServiceImpl implements IPrintTemplateService {

    private static final String ENGINE_THYMELEAF = "THYMELEAF";

    private final PrintTemplateMapper templateMapper;
    private final PrintFinanceMapper printFinanceMapper;
    private final ErpCustomerMapper customerMapper;
    private final TemplateSanitizer sanitizer;
    private final TemplateCompiler compiler;
    private final ObjectMapper objectMapper;

    @Override
    public TableDataInfo<PrintTemplateVo> selectPage(String scene, String keyword, PageQuery pageQuery) {
        String tenantId = currentTenantId();
        IPage<PrintTemplate> page = printFinanceMapper.selectLightPage(pageQuery.build(), tenantId,
            scene, keyword);
        Set<Long> customerIds = new LinkedHashSet<>();
        for (PrintTemplate row : page.getRecords()) {
            if (row.getCustomerId() != null) {
                customerIds.add(row.getCustomerId());
            }
        }
        Map<Long, String> customerNames = new HashMap<>();
        if (!customerIds.isEmpty()) {
            List<PrintCustomerName> names = printFinanceMapper.selectCustomerNames(tenantId,
                new ArrayList<>(customerIds));
            if (names != null) {
                for (PrintCustomerName name : names) {
                    customerNames.put(name.getId(), name.getName());
                }
            }
        }
        Page<PrintTemplateVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
            .map(row -> toListVo(row, customerNames.get(row.getCustomerId())))
            .toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public PrintTemplateVo selectById(Long id) {
        PrintTemplate row = templateMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("打印模板不存在：" + id);
        }
        return toVo(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintTemplateVo insert(PrintTemplateBo bo) {
        String content = resolveContent(bo);
        Long exists = templateMapper.selectCount(new LambdaQueryWrapper<PrintTemplate>()
            .eq(PrintTemplate::getCode, bo.getCode()));
        if (exists != null && exists > 0) {
            throw new ServiceException("模板编码已存在：" + bo.getCode(), 40902);
        }
        PrintTemplate row = new PrintTemplate();
        applyBo(row, bo);
        row.setContent(content);
        row.setEngine(ENGINE_THYMELEAF);
        row.setVersion(1);
        if (row.getIsDefault() != null && row.getIsDefault() == 1) {
            clearDefault(bo.getScene());
        }
        templateMapper.insert(row);
        return toVo(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintTemplateVo update(Long id, PrintTemplateBo bo) {
        PrintTemplate row = templateMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("打印模板不存在：" + id);
        }
        String content = resolveContent(bo);
        Long exists = templateMapper.selectCount(new LambdaQueryWrapper<PrintTemplate>()
            .eq(PrintTemplate::getCode, bo.getCode())
            .ne(PrintTemplate::getId, id));
        if (exists != null && exists > 0) {
            throw new ServiceException("模板编码已存在：" + bo.getCode(), 40902);
        }
        applyBo(row, bo);
        row.setContent(content);
        row.setVersion(row.getVersion() == null ? 1 : row.getVersion() + 1);
        if (row.getIsDefault() != null && row.getIsDefault() == 1) {
            clearDefault(bo.getScene());
        }
        templateMapper.updateById(row);
        return toVo(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long id) {
        PrintTemplate row = templateMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("打印模板不存在：" + id);
        }
        row.setStatus(0);
        templateMapper.updateById(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        PrintTemplate row = templateMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("打印模板不存在：" + id);
        }
        row.setIsDefault(1);
        row.setStatus(1);
        clearDefault(row.getScene());
        templateMapper.updateById(row);
    }

    @Override
    public PrintTemplate resolve(Long templateId, String scene, Long customerId) {
        if (templateId != null) {
            PrintTemplate row = templateMapper.selectById(templateId);
            if (row == null || row.getStatus() == null || row.getStatus() != 1) {
                throw new ServiceException("指定打印模板不存在或已停用：" + templateId);
            }
            return row;
        }
        if (customerId != null) {
            List<PrintTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<PrintTemplate>()
                .eq(PrintTemplate::getScene, scene)
                .eq(PrintTemplate::getCustomerId, customerId)
                .eq(PrintTemplate::getStatus, 1)
                .orderByDesc(PrintTemplate::getId)
                .last("limit 1"));
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
        }
        List<PrintTemplate> defaults = templateMapper.selectList(new LambdaQueryWrapper<PrintTemplate>()
            .eq(PrintTemplate::getScene, scene)
            .eq(PrintTemplate::getIsDefault, 1)
            .eq(PrintTemplate::getStatus, 1)
            .orderByDesc(PrintTemplate::getId)
            .last("limit 1"));
        return defaults.isEmpty() ? null : defaults.get(0);
    }

    @Override
    public List<PrintTemplate> listEnabled(String scene) {
        return templateMapper.selectList(new LambdaQueryWrapper<PrintTemplate>()
            .eq(PrintTemplate::getScene, scene)
            .eq(PrintTemplate::getStatus, 1)
            .orderByDesc(PrintTemplate::getIsDefault)
            .orderByDesc(PrintTemplate::getId));
    }

    private void applyBo(PrintTemplate row, PrintTemplateBo bo) {
        row.setCode(bo.getCode());
        row.setName(bo.getName());
        row.setScene(bo.getScene());
        row.setCustomerId(bo.getCustomerId());
        row.setVariablesJson(bo.getVariablesJson());
        row.setSchemaJson(blankToNull(bo.getSchemaJson()));
        row.setPaper(bo.getPaper() == null || bo.getPaper().isBlank() ? "A4" : bo.getPaper());
        row.setIsDefault(bo.getIsDefault() == null ? 0 : bo.getIsDefault());
        row.setStatus(bo.getStatus() == null ? 1 : bo.getStatus());
    }

    private void clearDefault(String scene) {
        List<PrintTemplate> defaults = templateMapper.selectList(new LambdaQueryWrapper<PrintTemplate>()
            .eq(PrintTemplate::getScene, scene)
            .eq(PrintTemplate::getIsDefault, 1));
        for (PrintTemplate d : defaults) {
            d.setIsDefault(0);
            templateMapper.updateById(d);
        }
    }

    /**
     * 解析并校验模板内容。
     *
     * <p>可视化设计器只提交组件树（schemaJson），HTML 由后端编译生成：
     * 前端不产出不可信 HTML；源码模式直接用提交的 content。
     * 两条路径都必须通过 sanitizer 白名单，安全边界一致。
     *
     * @param bo 保存请求
     * @return 清洗后的 HTML
     */
    private String resolveContent(PrintTemplateBo bo) {
        validateScene(bo.getScene());
        String schemaJson = blankToNull(bo.getSchemaJson());
        if (schemaJson != null && schemaJson.length() > TemplateSanitizer.MAX_TEMPLATE_CHARS) {
            throw new ServiceException("设计器请求体超过允许大小");
        }
        if (schemaJson == null) {
            if (bo.getContent() == null || bo.getContent().isBlank()) {
                throw new ServiceException("模板内容不能为空：请在设计器中添加区块，或切到源码模式填写 HTML");
            }
            return sanitizer.clean(bo.getContent(), bo.getScene());
        }
        DesignSchema schema;
        try {
            schema = objectMapper.readValue(schemaJson, DesignSchema.class);
        } catch (JsonProcessingException e) {
            throw new ServiceException("设计器内容格式错误：" + e.getOriginalMessage());
        }
        String html;
        try {
            html = compiler.compile(schema, bo.getScene());
        } catch (IllegalArgumentException e) {
            throw new ServiceException("设计器内容无法编译：" + e.getMessage());
        }
        return sanitizer.clean(html, bo.getScene());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private void validateScene(String scene) {
        List<String> allowed = List.of("SALE_DELIVERY", "STATEMENT", "STATEMENT_RANGE", "PURCHASE_IN",
            "RECEIPT", "PAYMENT");
        if (scene == null || !allowed.contains(scene)) {
            throw new ServiceException("不支持的场景：" + scene);
        }
    }

    private String currentTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ServiceException("缺少租户上下文，拒绝读取打印模板列表", 40901);
        }
        return tenantId;
    }

    private PrintTemplateVo toVo(PrintTemplate row) {
        String customerName = null;
        if (row.getCustomerId() != null) {
            ErpCustomer customer = customerMapper.selectById(row.getCustomerId());
            customerName = customer == null ? null : customer.getName();
        }
        return toVo(row, customerName);
    }

    private PrintTemplateVo toListVo(PrintTemplate row, String customerName) {
        PrintTemplateVo vo = new PrintTemplateVo();
        vo.setId(row.getId());
        vo.setCode(row.getCode());
        vo.setName(row.getName());
        vo.setScene(row.getScene());
        vo.setCustomerId(row.getCustomerId());
        vo.setCustomerName(customerName);
        vo.setPaper(row.getPaper());
        vo.setVersion(row.getVersion());
        vo.setIsDefault(row.getIsDefault());
        vo.setStatus(row.getStatus());
        return vo;
    }

    private PrintTemplateVo toVo(PrintTemplate row, String customerName) {
        PrintTemplateVo vo = new PrintTemplateVo();
        vo.setId(row.getId());
        vo.setCode(row.getCode());
        vo.setName(row.getName());
        vo.setScene(row.getScene());
        vo.setCustomerId(row.getCustomerId());
        vo.setCustomerName(customerName);
        vo.setEngine(row.getEngine());
        vo.setVariablesJson(row.getVariablesJson());
        vo.setContent(row.getContent());
        vo.setSchemaJson(row.getSchemaJson());
        vo.setPaper(row.getPaper());
        vo.setVersion(row.getVersion());
        vo.setIsDefault(row.getIsDefault());
        vo.setStatus(row.getStatus());
        vo.setCreateTime(row.getCreateTime());
        return vo;
    }
}
