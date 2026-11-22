package org.erp.fin.service.impl;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.erp.fin.controller.FinOpeningController;
import org.erp.fin.domain.vo.FinOpeningConfirmedAmountVo;
import org.erp.fin.excel.FinOpeningExcelService;
import org.erp.fin.mapper.FinOpeningMapper;
import org.erp.fin.service.IFinOpeningService;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际 SQL 渲染/租户解析和最小 HTTP 契约；不是 JVM/真库已执行证据。 */
@Tag("dev")
class FinOpeningConfirmedAmountsSqlContractTest {
    @Test void controllerPreservesExactStringQueryAndRequiresFinanceReadPermission() throws Exception {
        var method = FinOpeningController.class.getMethod("confirmedByPartyIds", String.class, String.class);
        assertArrayEquals(new String[]{"/erp/finance/openings"}, FinOpeningController.class.getAnnotation(RequestMapping.class).value());
        assertArrayEquals(new String[]{"/confirmed/by-party-ids"}, method.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"erp:finance:list"}, method.getAnnotation(SaCheckPermission.class).value());
        var params = method.getParameters();
        assertEquals("partyType", params[0].getAnnotation(RequestParam.class).value());
        assertEquals("partyIds", params[1].getAnnotation(RequestParam.class).value());
        assertTrue(params[0].getAnnotation(RequestParam.class).required());
        assertTrue(params[1].getAnnotation(RequestParam.class).required());
        IFinOpeningService service = mock(IFinOpeningService.class);
        var excel = mock(FinOpeningExcelService.class);
        var rows = List.of(new FinOpeningConfirmedAmountVo("9007199254740993", "9007199254740993.01"));
        when(service.confirmedByPartyIds("AR", "9007199254740993,9007199254740993")).thenReturn(rows);
        var result = new FinOpeningController(service, excel).confirmedByPartyIds("AR", "9007199254740993,9007199254740993");
        assertEquals(200, result.getCode()); assertSame(rows, result.getData());
        verify(service).confirmedByPartyIds("AR", "9007199254740993,9007199254740993");
        verifyNoMoreInteractions(service); verifyNoInteractions(excel);
    }

    @Test void responseShapeContainsOnlyTwoStringsAndReadPathTakesNoMutationLocks() throws Exception {
        var fields = FinOpeningConfirmedAmountVo.class.getRecordComponents();
        assertEquals(List.of("partyId", "amount"), Arrays.stream(fields).map(value -> value.getName()).toList());
        assertTrue(Arrays.stream(fields).allMatch(value -> value.getType() == String.class));
        Transactional tx = FinOpeningServiceImpl.class.getMethod("confirmedByPartyIds", String.class, String.class)
            .getAnnotation(Transactional.class);
        assertNotNull(tx); assertTrue(tx.readOnly());
        assertFalse(normalized(renderOpening("AR", List.of(1L)).getSql()).contains("for update"));
    }

    @Test void narrowQueryOnlyReadsRequestedConfirmedRawAmountsIncludingNullOpeningDate() {
        for (String type : List.of("AR", "AP")) {
            BoundSql sql = renderOpening(type, List.of(Long.MAX_VALUE, 9L));
            String raw = normalized(sql.getSql());
            assertTrue(raw.startsWith("select party_id, amount from fin_opening"), raw);
            assertTrue(raw.contains("party_type = ?"), raw);
            assertTrue(raw.contains("status = 'confirmed'"), raw);
            assertTrue(raw.contains("del_flag = '0'"), raw);
            assertTrue(raw.contains("party_id in"), raw);
            assertEquals(3, sql.getParameterMappings().size());
            assertEquals("partyType", sql.getParameterMappings().get(0).getProperty());
            assertEquals(Long.MAX_VALUE, sql.getAdditionalParameter(sql.getParameterMappings().get(1).getProperty()));
            for (String forbidden : List.of("opening_date", "fin_ar_ap", "settled_amount", "sum(", "group by", "join", "select *", "${", "limit", "offset")) {
                assertFalse(raw.contains(forbidden), raw);
            }
            assertTenantScoped(sql);
        }
    }

    @Test void exact200IdsStayBoundParametersWithoutPagingOrTruncation() {
        BoundSql sql = renderOpening("AP", LongStream.rangeClosed(1, 200).boxed().toList());
        assertEquals(201, sql.getParameterMappings().size());
        assertFalse(normalized(sql.getSql()).contains("limit"));
        assertFalse(normalized(sql.getSql()).contains("offset"));
    }

    @Test void reusedMasterBatchReadKeepsTenantSoftDeleteAndDisabledButReadableParties() {
        for (Class<?> mapper : List.of(ErpCustomerMapper.class, ErpSupplierMapper.class)) {
            BoundSql sql = render(mapper, "selectOptionsByIds", Map.of("ids", List.of(Long.MAX_VALUE, 9L)));
            String raw = normalized(sql.getSql());
            assertTrue(raw.contains("m.del_flag = '0'"), raw);
            assertTrue(raw.contains("m.id in"), raw);
            assertFalse(raw.contains("m.status ="), "禁用但主数据页仍可读的往来不能被当作无权或0");
            assertEquals(2, sql.getParameterMappings().size());
            assertTenantScoped(sql);
        }
    }

    private BoundSql renderOpening(String type, List<Long> ids) {
        return render(FinOpeningMapper.class, "selectConfirmedAmountsByPartyIds", Map.of("partyType", type, "partyIds", ids));
    }
    private BoundSql render(Class<?> mapper, String name, Map<String, Object> parameters) {
        var method = Arrays.stream(mapper.getMethods()).filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
        return new XMLLanguageDriver().createSqlSource(new Configuration(),
            String.join(" ", method.getAnnotation(Select.class).value()), Map.class).getBoundSql(parameters);
    }
    private void assertTenantScoped(BoundSql sql) {
        String scoped = normalized(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new StringValue("TENANT_A"); }
        }).parserSingle(sql.getSql(), null));
        assertTrue(scoped.contains("tenant_id = 'tenant_a'"), scoped);
    }
    private String normalized(String sql) { return sql.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT); }
}
