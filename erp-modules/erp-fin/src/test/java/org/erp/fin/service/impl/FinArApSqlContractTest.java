package org.erp.fin.service.impl;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.erp.fin.controller.FinArApController;
import org.erp.fin.mapper.FinArApMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 渲染实际 Mapper SQL，经过与生产相同的租户 SQL 解析器；无 JDBC/数据库。 */
@Tag("dev")
class FinArApSqlContractTest {

    @Test
    void allReadsSharePartyTypeNonVoidSoftDeleteAndTenantScope() {
        for (String name : List.of("selectSummaryTotals", "countSummaryParties", "selectSummaryParties", "countByParty",
            "selectByPartyPage", "selectAllocatableByParty")) {
            BoundSql bound = render(name);
            String sql = normalized(bound.getSql());
            assertTrue(sql.contains("party_type = ?"), sql);
            assertTrue(sql.contains("status != 'void'"), sql);
            assertTrue(sql.contains("del_flag = '0'"), sql);
            assertFalse(sql.contains("status = 'open'"), "不能丢掉 SETTLED/PARTIAL 或红冲：" + sql);
            assertFalse(sql.contains("amount > 0"), sql);
            assertFalse(sql.contains("biz_date >"), "旧接口没有隐式日期范围：" + sql);
            assertFalse(sql.contains("biz_date <"), sql);
            String tenantSql = normalized(new TenantLineInnerInterceptor(new TenantLineHandler() {
                @Override
                public Expression getTenantId() {
                    return new StringValue("TENANT_A");
                }
            }).parserSingle(bound.getSql(), null));
            assertTrue(tenantSql.contains("tenant_id = 'tenant_a'"), tenantSql);
        }
    }

    @Test
    void summaryAggregatesAllMatchingRowsBeforeGroupLimitAndUsesDistinctPartyCount() {
        String totals = normalized(render("selectSummaryTotals").getSql());
        assertTrue(totals.contains("sum(amount)"), totals);
        assertTrue(totals.contains("sum(settled_amount)"), totals);
        assertTrue(totals.contains("sum(amount - settled_amount)"), totals);
        assertTrue(totals.contains("case when source_type = 'opening' then amount else 0 end"), totals);
        assertFalse(totals.contains("limit"), "全量金额不能跟随分页：" + totals);
        assertFalse(totals.contains("md_customer"), "不能将旧 opening_debt 再加一次：" + totals);
        String groups = normalized(render("selectSummaryParties").getSql());
        assertTrue(groups.endsWith("group by party_id order by party_id asc limit ?, ?"), groups);
        assertTrue(groups.contains("sum(amount - settled_amount)"), groups);
        assertEquals(List.of("partyType", "offset", "limit"), parameters(render("selectSummaryParties")));
        assertTrue(normalized(render("countSummaryParties").getSql()).contains("count(distinct party_id)"));
    }

    @Test
    void detailCountAndPageSharePartyFilterWithOriginalBusinessDateAndIdOrder() {
        String count = normalized(render("countByParty").getSql());
        String page = normalized(render("selectByPartyPage").getSql());
        assertTrue(count.contains("party_id = ?"), count);
        assertTrue(page.contains("party_id = ?"), page);
        assertTrue(page.endsWith("order by biz_date asc, id asc limit ?, ?"), page);
        assertFalse(page.contains("create_time"), "不能将业务日期换成创建日");
        assertFalse(page.contains("date("), "不能截去时间或遗漏 null 业务日期");
        assertFalse(page.contains("select *"), page);
        assertEquals(List.of("partyType", "partyId"), parameters(render("countByParty")));
        assertEquals(List.of("partyType", "partyId", "offset", "limit"), parameters(render("selectByPartyPage")));
    }

    @Test
    void allocationCandidatesFilterPositiveBalanceBeforeBoundedReadWithoutChangingHistory() {
        String candidates = normalized(render("selectAllocatableByParty").getSql());
        assertTrue(candidates.contains("party_id = ?"), candidates);
        assertTrue(candidates.endsWith("and amount > settled_amount order by biz_date asc, id asc limit ?"), candidates);
        assertEquals(List.of("partyType", "partyId", "limit"), parameters(render("selectAllocatableByParty")));
        assertFalse(normalized(render("selectByPartyPage").getSql()).contains("amount > settled_amount"));
        assertFalse(normalized(render("countByParty").getSql()).contains("amount > settled_amount"));
    }

    @Test
    void newAndLegacyEndpointsKeepTheSameReadPermissionAndParallelPaths() {
        Map<String, String> paths = Map.of("summary", "/summary", "summaryPage", "/summary/page",
            "detail", "/detail", "detailPage", "/detail/page");
        for (Map.Entry<String, String> entry : paths.entrySet()) {
            Method method = Arrays.stream(FinArApController.class.getDeclaredMethods())
                .filter(candidate -> entry.getKey().equals(candidate.getName())).findFirst().orElseThrow();
            assertArrayEquals(new String[]{"erp:finance:list"}, method.getAnnotation(SaCheckPermission.class).value());
            assertArrayEquals(new String[]{entry.getValue()}, method.getAnnotation(GetMapping.class).value());
        }
    }

    private BoundSql render(String name) {
        Method method = Arrays.stream(FinArApMapper.class.getDeclaredMethods())
            .filter(candidate -> name.equals(candidate.getName())).findFirst().orElseThrow();
        String script = String.join(" ", method.getAnnotation(Select.class).value());
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class)
            .getBoundSql(Map.of("partyType", "AR", "partyId", 9L, "offset", 40L, "limit", 20));
    }

    private List<String> parameters(BoundSql sql) {
        return sql.getParameterMappings().stream().map(ParameterMapping::getProperty).toList();
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
