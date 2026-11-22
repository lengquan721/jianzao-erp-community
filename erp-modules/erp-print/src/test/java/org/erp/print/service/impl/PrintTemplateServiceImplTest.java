package org.erp.print.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.print.domain.PrintTemplate;
import org.erp.print.mapper.PrintFinanceMapper;
import org.erp.print.domain.bo.PrintTemplateBo;
import org.erp.print.mapper.PrintTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 打印模板服务测试：清洗入库 / 编码唯一 / 默认互斥 / 停用 / 解析顺序
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrintTemplateServiceImplTest {

    private static final String SAFE_HTML = "<html><body><h1>送货单</h1></body></html>";

    @Mock
    private PrintTemplateMapper templateMapper;

    @Mock
    private ErpCustomerMapper customerMapper;

    @Mock
    private PrintFinanceMapper printFinanceMapper;

    private PrintTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PrintTemplateServiceImpl(templateMapper, printFinanceMapper, customerMapper,
            new TemplateSanitizer(), new TemplateCompiler(), new ObjectMapper());
    }

    private PrintTemplateBo bo(String code, String scene, Integer isDefault) {
        PrintTemplateBo bo = new PrintTemplateBo();
        bo.setCode(code);
        bo.setName("模板" + code);
        bo.setScene(scene);
        bo.setContent(SAFE_HTML);
        bo.setIsDefault(isDefault);
        return bo;
    }

    @Test
    @DisplayName("列表：仅返回轻量元数据且按页批量加载客户名，详情仍保留正文/组件树")
    void selectPageUsesLightProjectionAndBatchedCustomerNames() {
        PrintTemplate first = new PrintTemplate();
        first.setId(21L);
        first.setCode("T21");
        first.setName("模板甲");
        first.setScene("SALE_DELIVERY");
        first.setCustomerId(7L);
        first.setPaper("A4");
        first.setVersion(2);
        first.setIsDefault(1);
        first.setStatus(1);
        first.setContent("large html body");
        first.setSchemaJson("large schema json");
        first.setVariablesJson("large variable json");

        PrintTemplate second = new PrintTemplate();
        second.setId(20L);
        second.setCode("T20");
        second.setName("模板乙");
        second.setScene("SALE_DELIVERY");
        second.setCustomerId(7L);
        second.setPaper("A5");
        second.setVersion(1);
        second.setIsDefault(0);
        second.setStatus(1);
        PrintTemplate third = new PrintTemplate();
        third.setId(19L);
        third.setCode("T19");
        third.setName("模板丙");
        third.setScene("SALE_DELIVERY");
        third.setCustomerId(9L);
        Page<PrintTemplate> page = new Page<>(2, 3, 8);
        page.setRecords(List.of(first, second, third));
        when(printFinanceMapper.selectLightPage(any(), any(), any(), any())).thenReturn(page);
        when(printFinanceMapper.selectCustomerNames(any(), any())).thenReturn(List.of(
            customerName(7L, "甲客户"), customerName(9L, "乙客户")));

        try (MockedStatic<TenantHelper> tenant = org.mockito.Mockito.mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("TENANT_A");
            var result = service.selectPage("SALE_DELIVERY", "T", new PageQuery(3, 2));

            assertEquals(8, result.getTotal());
            assertEquals(3, result.getRows().size());
            assertEquals("甲客户", result.getRows().get(0).getCustomerName());
            assertEquals("甲客户", result.getRows().get(1).getCustomerName());
            assertEquals("乙客户", result.getRows().get(2).getCustomerName());
            assertEquals("T21", result.getRows().get(0).getCode());
            assertEquals(21L, result.getRows().get(0).getId());
            assertNull(result.getRows().get(0).getContent());
            assertNull(result.getRows().get(0).getSchemaJson());
            assertNull(result.getRows().get(0).getVariablesJson());
            assertNull(result.getRows().get(0).getEngine());
            verify(printFinanceMapper).selectLightPage(any(), org.mockito.ArgumentMatchers.eq("TENANT_A"),
                org.mockito.ArgumentMatchers.eq("SALE_DELIVERY"), org.mockito.ArgumentMatchers.eq("T"));
            verify(printFinanceMapper).selectCustomerNames(org.mockito.ArgumentMatchers.eq("TENANT_A"),
                org.mockito.ArgumentMatchers.eq(List.of(7L, 9L)));
            verifyNoInteractions(customerMapper);
        }

        PrintTemplate detail = new PrintTemplate();
        detail.setId(21L);
        detail.setCustomerId(7L);
        detail.setContent("<div>full cleaned template</div>");
        String schemaJson = """
            {"blocks":[{"type":"TITLE"}]}
            """;
        detail.setSchemaJson(schemaJson);
        when(templateMapper.selectById(21L)).thenReturn(detail);
        org.erp.master.domain.ErpCustomer customer = new org.erp.master.domain.ErpCustomer();
        customer.setName("甲客户");
        when(customerMapper.selectById(7L)).thenReturn(customer);
        var detailVo = service.selectById(21L);
        assertEquals("<div>full cleaned template</div>", detailVo.getContent());
        assertEquals(schemaJson, detailVo.getSchemaJson());
    }

    private PrintCustomerName customerName(Long id, String name) {
        PrintCustomerName result = new PrintCustomerName();
        result.setId(id);
        result.setName(name);
        return result;
    }

    @Test
    @DisplayName("新增：清洗后入库，版本 1")
    void insertCleansAndSetsVersion() {
        PrintTemplateBo b = bo("T1", "SALE_DELIVERY", 0);
        b.setContent(SAFE_HTML + "<script>alert(1)</script>");
        service.insert(b);
        ArgumentCaptor<PrintTemplate> captor = ArgumentCaptor.forClass(PrintTemplate.class);
        verify(templateMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getVersion());
        assertEquals(-1, captor.getValue().getContent().indexOf("<script"));
        assertEquals("THYMELEAF", captor.getValue().getEngine());
    }

    @Test
    @DisplayName("新增：编码重复 40902")
    void insertDuplicateCodeRejected() {
        when(templateMapper.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.insert(bo("T1", "SALE_DELIVERY", 0)));
        assertEquals(40902, ex.getCode());
    }

    @Test
    @DisplayName("新增：非法场景拒绝")
    void insertInvalidSceneRejected() {
        assertThrows(ServiceException.class, () -> service.insert(bo("T1", "HACK", 0)));
        verify(templateMapper, never()).insert(any(PrintTemplate.class));
    }

    @Test
    @DisplayName("新增：设默认清除同场景旧默认")
    void insertDefaultClearsOld() {
        when(templateMapper.selectCount(any())).thenReturn(0L);
        PrintTemplate old = new PrintTemplate();
        old.setId(9L);
        old.setScene("SALE_DELIVERY");
        old.setIsDefault(1);
        when(templateMapper.selectList(any())).thenReturn(List.of(old));
        service.insert(bo("T1", "SALE_DELIVERY", 1));
        assertEquals(0, old.getIsDefault());
        verify(templateMapper, atLeastOnce()).updateById(old);
    }

    @Test
    @DisplayName("编辑：版本 +1")
    void updateIncrementsVersion() {
        PrintTemplate existing = new PrintTemplate();
        existing.setId(1L);
        existing.setCode("T1");
        existing.setScene("SALE_DELIVERY");
        existing.setVersion(3);
        existing.setStatus(1);
        existing.setIsDefault(0);
        when(templateMapper.selectById(1L)).thenReturn(existing);
        when(templateMapper.selectCount(any())).thenReturn(0L);
        service.update(1L, bo("T1", "SALE_DELIVERY", 0));
        assertEquals(4, existing.getVersion());
        verify(templateMapper).updateById(existing);
    }

    @Test
    @DisplayName("编辑：内容含恶意外链被清洗")
    void updateSanitizes() {
        PrintTemplate existing = new PrintTemplate();
        existing.setId(1L);
        existing.setCode("T1");
        existing.setScene("SALE_DELIVERY");
        existing.setVersion(1);
        when(templateMapper.selectById(1L)).thenReturn(existing);
        when(templateMapper.selectCount(any())).thenReturn(0L);
        PrintTemplateBo b = bo("T1", "SALE_DELIVERY", 0);
        b.setContent("<div>x</div><img src=\"http://evil.com/a.png\">");
        service.update(1L, b);
        assertEquals(-1, existing.getContent().indexOf("evil.com"));
    }

    @Test
    @DisplayName("停用：置 status=0，不物理删除")
    void disableSetsStatus() {
        PrintTemplate existing = new PrintTemplate();
        existing.setId(1L);
        existing.setStatus(1);
        when(templateMapper.selectById(1L)).thenReturn(existing);
        service.disable(1L);
        assertEquals(0, existing.getStatus());
        verify(templateMapper).updateById(existing);
        verify(templateMapper, never()).deleteById(1L);
    }

    @Test
    @DisplayName("resolve：指定 templateId 停用则拒绝")
    void resolveDisabledTemplateRejected() {
        PrintTemplate t = new PrintTemplate();
        t.setId(5L);
        t.setStatus(0);
        when(templateMapper.selectById(5L)).thenReturn(t);
        assertThrows(ServiceException.class, () -> service.resolve(5L, "SALE_DELIVERY", null));
    }

    @Test
    @DisplayName("resolve：客户专属优先于默认")
    void resolveCustomerSpecificFirst() {
        when(templateMapper.selectList(any())).thenReturn(List.of());
        assertEquals(null, service.resolve(null, "SALE_DELIVERY", 7L));
    }

    @Test
    @DisplayName("resolve：无模板返回 null（调用方用内置兜底）")
    void resolveReturnsNullWhenNone() {
        when(templateMapper.selectList(any())).thenReturn(List.of());
        assertEquals(null, service.resolve(null, "SALE_DELIVERY", null));
    }

    @Test
    @DisplayName("resolve：命中默认")
    void resolveHitsDefault() {
        PrintTemplate def = new PrintTemplate();
        def.setId(8L);
        def.setScene("STATEMENT");
        def.setIsDefault(1);
        def.setStatus(1);
        when(templateMapper.selectList(any())).thenReturn(List.of(def));
        assertEquals(8L, service.resolve(null, "STATEMENT", 7L).getId());
    }

    /* ------------------------------ 设计器模式 ------------------------------ */

    /** 最小组件树：一个标题 + 一个字段，足够验证编译链路 */
    private static final String SCHEMA_JSON = """
        {"schemaVersion":1,"paper":"A4","pageMargin":12,"fontSize":12,
         "blocks":[{"id":"t1","type":"TITLE","props":{"text":"送 货 单"},"style":{"fontSize":20}},
                   {"id":"f1","type":"FIELD","props":{"fieldKey":"doc.no","label":"单号"},"style":{}}]}
        """;

    @Test
    @DisplayName("设计器模式：组件树编译成 content 入库，schemaJson 一并保存")
    void insertCompilesSchema() {
        PrintTemplateBo b = bo("T2", "SALE_DELIVERY", 0);
        b.setContent(null);
        b.setSchemaJson(SCHEMA_JSON);
        service.insert(b);

        ArgumentCaptor<PrintTemplate> captor = ArgumentCaptor.forClass(PrintTemplate.class);
        verify(templateMapper).insert(captor.capture());
        PrintTemplate saved = captor.getValue();
        assertTrue(saved.getContent().contains("送 货 单"), "应写入编译后的 HTML");
        assertTrue(saved.getContent().contains("th:text=\"${doc.no}\""), "应保留变量绑定");
        assertTrue(saved.getSchemaJson().contains("\"TITLE\""), "组件树应原样保存以便再次编辑");
    }

    @Test
    @DisplayName("设计器模式：组件树为空则拒绝保存")
    void rejectEmptySchema() {
        PrintTemplateBo b = bo("T3", "SALE_DELIVERY", 0);
        b.setContent(null);
        b.setSchemaJson("{\"blocks\":[]}");
        assertThrows(ServiceException.class, () -> service.insert(b));
    }

    @Test
    @DisplayName("设计器模式：schema JSON 请求体超过长度上限时不进入数据库")
    void rejectOversizedSchemaJsonBeforePersistence() {
        PrintTemplateBo b = bo("T-LARGE", "SALE_DELIVERY", 0);
        b.setContent(null);
        b.setSchemaJson("{" + "x".repeat(TemplateSanitizer.MAX_TEMPLATE_CHARS));
        assertThrows(ServiceException.class, () -> service.insert(b));
        verifyNoInteractions(templateMapper);
    }

    @Test
    @DisplayName("设计器模式：组件树 JSON 格式错误给出明确提示")
    void rejectBrokenSchemaJson() {
        PrintTemplateBo b = bo("T4", "SALE_DELIVERY", 0);
        b.setContent(null);
        b.setSchemaJson("{not json");
        ServiceException e = assertThrows(ServiceException.class, () -> service.insert(b));
        assertTrue(e.getMessage().contains("设计器内容格式错误"));
    }

    @Test
    @DisplayName("设计器模式：非法字段路径被编译器拦截，不会落库")
    void rejectIllegalFieldPath() {
        PrintTemplateBo b = bo("T5", "SALE_DELIVERY", 0);
        b.setContent(null);
        b.setSchemaJson("""
            {"blocks":[{"id":"f1","type":"FIELD","props":{"fieldKey":"T(java.lang.Runtime).exec"},"style":{}}]}
            """);
        assertThrows(ServiceException.class, () -> service.insert(b));
    }

    @Test
    @DisplayName("源码模式：schemaJson 为空时按原样清洗 content（老模板不受影响）")
    void sourceModeUnchanged() {
        PrintTemplateBo b = bo("T6", "SALE_DELIVERY", 0);
        b.setSchemaJson("");
        service.insert(b);

        ArgumentCaptor<PrintTemplate> captor = ArgumentCaptor.forClass(PrintTemplate.class);
        verify(templateMapper).insert(captor.capture());
        assertNull(captor.getValue().getSchemaJson(), "空字符串应存 null，表示源码模式");
        assertTrue(captor.getValue().getContent().contains("送货单"));
    }

    @Test
    @DisplayName("两种模式都为空则拒绝")
    void rejectBothEmpty() {
        PrintTemplateBo b = bo("T7", "SALE_DELIVERY", 0);
        b.setContent("");
        b.setSchemaJson(null);
        ServiceException e = assertThrows(ServiceException.class, () -> service.insert(b));
        assertTrue(e.getMessage().contains("模板内容不能为空"));
    }
}
