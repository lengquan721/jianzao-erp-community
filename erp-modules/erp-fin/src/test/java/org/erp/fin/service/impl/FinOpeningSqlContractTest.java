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
import org.erp.fin.mapper.FinOpeningMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 实际 SQL 渲染及租户解析合同，不接数据库；不将 SQL 静态断言冒充数据库执行。 */
@Tag("dev")
class FinOpeningSqlContractTest {

    @Test
    void countAndPageShareOptionalTypeSoftDeleteAndTenantScopeWithoutStatusOrDateNarrowing() {
        for (String type : Arrays.asList(null, "", "  ", "\u2003", "AR", "AP", "XX")) {
            BoundSql count = render("countReadRows", type);
            BoundSql rows = render("selectReadRows", type);
            String countSql = normalized(count.getSql());
            String pageSql = normalized(rows.getSql());
            assertEquals(countSql.substring(countSql.indexOf(" from ")),
                pageSql.substring(pageSql.indexOf(" from "), pageSql.indexOf(" order by ")));
            for (BoundSql bound : List.of(count, rows)) {
                String sql = normalized(bound.getSql());
                assertTrue(sql.contains("del_flag = '0'"), sql);
                assertEquals(type != null && !type.isBlank(), sql.contains("party_type = ?"), sql);
                assertFalse(sql.contains("status ="), "DRAFT/CONFIRMED 等原状态不能被隐式筛掉");
                assertFalse(sql.contains("create_time >"), sql);
                assertFalse(sql.contains("create_time <"), sql);
                String scoped = normalized(new TenantLineInnerInterceptor(new TenantLineHandler() {
                    @Override public Expression getTenantId() { return new StringValue("TENANT_A"); }
                }).parserSingle(bound.getSql(), null));
                assertTrue(scoped.contains("tenant_id = 'tenant_a'"), scoped);
            }
            assertTrue(pageSql.endsWith("order by id asc limit ?, ?"), pageSql);
            assertTrue(pageSql.contains("remark, create_time"), pageSql);
            assertTrue(pageSql.contains("amount, opening_date, status"), pageSql);
            assertFalse(pageSql.contains("select *"), pageSql);
            assertFalse(countSql.contains("join"), "缺失名称不应改变 total");
        }
    }

    @Test
    void pageAndExportParametersAreBoundWithoutSqlInterpolationOrInteractivePageLimit() {
        BoundSql rows = render("selectReadRows", "AP");
        assertEquals(List.of("partyType", "offset", "limit"),
            rows.getParameterMappings().stream().map(parameter -> parameter.getProperty()).toList());
        assertEquals(5001, ((Map<?, ?>) rows.getParameterObject()).get("limit"));
        assertTrue(normalized(rows.getSql()).endsWith("limit ?, ?"));
    }

    @Test
    void legacyAndNewReadPermissionsAndActualExportPathRemainCompatible() throws Exception {
        var list = FinOpeningController.class.getMethod("list", String.class);
        var page = FinOpeningController.class.getMethod("page", String.class, int.class, int.class);
        var export = FinOpeningController.class.getMethod("export", String.class, jakarta.servlet.http.HttpServletResponse.class);
        for (var method : List.of(list, page)) {
            assertArrayEquals(new String[]{"erp:finance:list"}, method.getAnnotation(SaCheckPermission.class).value());
        }
        assertArrayEquals(new String[]{"/list"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/list/page"}, page.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/export"}, export.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"erp:finance:opening:export"}, export.getAnnotation(SaCheckPermission.class).value());
    }

    private BoundSql render(String methodName, String type) {
        var method = Arrays.stream(FinOpeningMapper.class.getDeclaredMethods())
            .filter(candidate -> methodName.equals(candidate.getName())).findFirst().orElseThrow();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("partyType", type);
        parameters.put("offset", 40L);
        parameters.put("limit", 5001);
        return new XMLLanguageDriver().createSqlSource(new Configuration(),
            String.join(" ", method.getAnnotation(Select.class).value()), Map.class).getBoundSql(parameters);
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
