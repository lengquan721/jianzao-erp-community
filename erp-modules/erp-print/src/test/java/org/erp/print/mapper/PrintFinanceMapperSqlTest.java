package org.erp.print.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.erp.fin.mapper.FinArApMapper;
import org.erp.print.service.impl.PrintCustomerName;
import org.erp.print.service.impl.PrintLedgerSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Render actual mapped SQL and run the tenant parser, without opening a connection. */
@Tag("dev")
class PrintFinanceMapperSqlTest {
    @Test
    void sourceBalanceUsesAllSourceAndPartyKeysAndActualNetLedgerAmount() throws Exception {
        BoundSql bound = render(PrintFinanceMapper.class, "sumUnsettledBySource", Map.of(
            "tenantId", "TENANT_A", "partyType", "AR", "partyId", 7L, "sourceType", "SALE", "sourceId", 41L));
        String sql = normalized(bound);
        assertTrue(sql.startsWith("select sum(amount - settled_amount) from fin_ar_ap"), sql);
        for (String key : List.of("tenant_id", "party_type", "party_id", "source_type", "source_id")) {
            assertTrue(sql.contains(key + " = ?"), key);
        }
        assertEquals(List.of("tenantId", "partyType", "partyId", "sourceType", "sourceId"),
            bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList());
        assertFalse(sql.contains("coalesce"), "No source must remain null, not become a fabricated zero");
        assertFalse(sql.contains("least("), "Do not cap a source ledger using a party/document total");
        assertNotNull(tenantSql(sql));
    }

    @Test
    void sourceBalanceIncludesSettledZeroButExcludesVoidDeletedAndUnknownStates() throws Exception {
        String sql = normalized(render(PrintFinanceMapper.class, "sumUnsettledBySource", Map.of()));
        assertTrue(sql.contains("status in ('OPEN', 'PARTIAL', 'SETTLED')"), sql);
        assertFalse(sql.contains("'VOID'"));
        assertTrue(sql.contains("del_flag = '0'"));
        assertFalse(sql.contains("status <>"));
    }

    @Test
    void profitProjectionIsOnlyThreeSnapshotsAndHasTenantIdAndLogicalDeleteFilters() throws Exception {
        BoundSql bound = render(PrintFinanceMapper.class, "selectSaleProfit", Map.of("tenantId", "TENANT_A", "saleId", 41L));
        String sql = normalized(bound);
        assertTrue(sql.startsWith("select cost_amount as costAmount, gross_profit as grossProfit, gross_margin as grossMargin from biz_sale"), sql);
        assertTrue(sql.contains("tenant_id = ? and id = ? and del_flag = '0'"), sql);
        assertFalse(sql.contains("select *"));
        assertEquals(List.of("tenantId", "saleId"),
            bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList());
        assertNotNull(tenantSql(sql));
    }

    @Test
    void partyDebtRetainsAuthoritativeLegacySumAndNonVoidPolicy() throws Exception {
        String sql = normalized(render(FinArApMapper.class, "sumUnsettledByParty", Map.of("partyType", "AR", "partyId", 7L)));
        assertTrue(sql.contains("coalesce(sum(amount - settled_amount), 0)"));
        assertTrue(sql.contains("status != 'VOID'"));
        assertTrue(sql.contains("del_flag = '0'"));
        assertFalse(sql.contains("source_type"));
        assertFalse(sql.contains("source_id"));
        assertFalse(sql.contains("status in"));
    }

    @Test
    void statementTotalsUseBoundDatesAndSignedCurrentDocumentEvents() throws Exception {
        BoundSql bound = render(PrintFinanceMapper.class, "selectStatementMovements", Map.of());
        String sql = normalized(bound);
        assertEquals(4, sql.split("tenant_id = \\?", -1).length - 1);
        assertEquals(5, sql.split("del_flag = '0'", -1).length - 1);
        assertEquals(3, sql.split("status = 'CONFIRMED'", -1).length - 1);
        for (String tableAlias : List.of("s", "r", "f")) {
            assertEquals(2, sql.split(tableAlias + "\\.biz_date < \\?", -1).length - 1,
                "every dated source has an exclusive end and prior-period split: " + tableAlias);
        }
        assertEquals(2, sql.split("coalesce\\(o\\.opening_date, a\\.biz_date\\) < \\?", -1).length - 1);
        assertEquals(1, sql.split("coalesce\\(o\\.opening_date, a\\.biz_date\\) >= \\?", -1).length - 1);
        assertTrue(sql.contains("left join fin_opening o on o.id = a.source_id and o.tenant_id = a.tenant_id and o.del_flag = '0'"), sql);
        assertTrue(sql.contains("a.source_type = 'OPENING' and a.status in ('OPEN', 'PARTIAL', 'SETTLED')"));
        assertTrue(sql.contains("then s.total_amount"), "statement sales totals must use confirmed document headers");
        assertTrue(sql.contains("then -r.total_amount"));
        assertTrue(sql.contains("then f.amount else -f.amount"));
        assertFalse(sql.contains("settled_amount"));
        assertTrue(bound.getParameterMappings().stream().allMatch(mapping ->
            List.of("tenantId", "partyId", "from", "to").contains(mapping.getProperty())));
        assertNotNull(tenantSql(sql));
    }

    @Test
    void statementLinesBindDateWindowAndIsolateEveryJoinedTable() throws Exception {
        String sql = normalized(render(PrintFinanceMapper.class, "selectStatementLines", Map.of()));
        assertTrue(sql.contains("left join biz_sale_item i on i.order_id = s.id and i.tenant_id = s.tenant_id and i.del_flag = '0'"));
        assertTrue(sql.contains("left join biz_sale_return_item i on i.order_id = r.id and i.tenant_id = r.tenant_id and i.del_flag = '0'"));
        assertTrue(sql.contains("p.tenant_id = i.tenant_id and p.del_flag = '0'"));
        assertTrue(sql.contains("case when i.id is null then s.total_amount else coalesce(i.amount, 0) end"));
        assertTrue(sql.contains("case when i.id is null then -r.total_amount else -coalesce(i.amount, 0) end"));
        assertTrue(sql.contains("left join fin_opening o on o.id = a.source_id and o.tenant_id = a.tenant_id and o.del_flag = '0'"));
        assertEquals(3, sql.split("biz_date >= \\? and [a-z]\\.biz_date < \\?", -1).length - 1);
        assertTrue(sql.contains("coalesce(o.opening_date, a.biz_date) >= ?"));
        assertTrue(sql.contains("coalesce(o.opening_date, a.biz_date) < ?"));
        assertTrue(sql.endsWith("order by line.eventDate, line.documentNo, line.kind, line.docId, line.lineSeq, line.lineId limit 5001"));
        assertFalse(sql.contains("cost_amount"));
        assertFalse(sql.contains("gross_profit"));
        assertNotNull(tenantSql(sql));
    }

    @Test
    void allocationSourceDocumentNumberQueriesOnlyProjectIdAndNo() throws Exception {
        for (String method : List.of("selectSaleNumbers", "selectPurchaseNumbers")) {
            String sql = normalized(render(PrintFinanceMapper.class, method, Map.of(
                "tenantId", "TENANT_A", "ids", List.of(1L, 2L))));
            String expectedTable = method.equals("selectSaleNumbers") ? "biz_sale" : "biz_purchase";
            assertTrue(sql.startsWith("select id, no as documentNo from " + expectedTable), sql);
            assertTrue(sql.contains("tenant_id = ? and del_flag = '0' and id in"), sql);
            assertTrue(java.util.regex.Pattern.compile("\\bid\\s+in\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)")
                .matcher(sql).find(), sql);
            assertFalse(sql.contains("cost_amount"));
            assertFalse(sql.contains("gross_profit"));
            assertFalse(sql.contains("select *"));
            assertNotNull(tenantSql(sql));
        }
    }

    @Test
    void ledgerSourcesUseTypedFourFieldProjectionAndBindTenantAndEveryId() throws Exception {
        assertNarrowIdProjection("selectLedgerSources", PrintLedgerSource.class,
            "id, source_type as sourceType, source_id as sourceId, biz_date as bizDate", "fin_ar_ap");
    }

    @Test
    void customerNamesUseTypedIdNameProjectionAndBindTenantAndEveryId() throws Exception {
        assertNarrowIdProjection("selectCustomerNames", PrintCustomerName.class, "id, name", "md_customer");
    }

    @Test
    void templateListProjectionIsNarrowTenantScopedAndGroupsKeywordUnderScene() throws Exception {
        BoundSql bound = render(PrintFinanceMapper.class, "selectLightPage", Map.of(
            "tenantId", "TENANT_A", "scene", "SALE_DELIVERY", "keyword", "T"));
        String sql = normalized(bound);
        assertTrue(sql.startsWith("select id, code, name, scene, customer_id, paper, version, is_default, status from print_template"), sql);
        assertTrue(sql.contains("where tenant_id = ? and del_flag = '0' and scene = ? and (name like concat('%', ?, '%') or code like concat('%', ?, '%'))"), sql);
        assertFalse(sql.contains("content"));
        assertFalse(sql.contains("schema_json"));
        assertFalse(sql.contains("variables_json"));
        assertEquals(List.of("tenantId", "scene", "keyword", "keyword"),
            bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList());
        assertNotNull(tenantSql(sql));
    }

    private void assertNarrowIdProjection(String methodName, Class<?> projection, String columns, String table) {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(PrintFinanceMapper.class);
        var statement = configuration.getMappedStatement(PrintFinanceMapper.class.getName() + "." + methodName);
        assertEquals(SqlCommandType.SELECT, statement.getSqlCommandType());
        assertEquals(List.of(projection), statement.getResultMaps().stream().map(result -> result.getType()).toList());
        BoundSql bound = statement.getBoundSql(Map.of("tenantId", "TENANT_A", "ids", List.of(101L, 102L)));
        String sql = normalized(bound);

        // Exact projection and table: adding a large/private column must fail the contract.
        assertEquals("select " + columns + " from " + table, sql.substring(0, sql.indexOf(" where ")));
        assertTrue(sql.contains("where tenant_id = ? and del_flag = '0' and id in"), sql);
        assertTrue(java.util.regex.Pattern.compile("\\bid\\s+in\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)")
            .matcher(sql).find(), sql);
        assertFalse(sql.contains("status"), "Source labels must not disappear because a document/party changed status");
        assertEquals(3, bound.getParameterMappings().size());
        assertEquals("tenantId", bound.getParameterMappings().get(0).getProperty());
        assertEquals(List.of("TENANT_A", 101L, 102L), bound.getParameterMappings().stream()
            .map(mapping -> parameterValue(bound, mapping)).toList());
        String scoped = tenantSql(sql).toLowerCase();
        assertTrue(scoped.contains("tenant_id = 'tenant_a'"), scoped);
        assertTrue(scoped.contains("del_flag = '0'"), scoped);
    }

    private Object parameterValue(BoundSql bound, ParameterMapping mapping) {
        String property = mapping.getProperty();
        if (bound.hasAdditionalParameter(property)) {
            return bound.getAdditionalParameter(property);
        }
        return ((Map<?, ?>) bound.getParameterObject()).get(property);
    }

    private BoundSql render(Class<?> mapper, String methodName, Map<String, ?> parameters) throws Exception {
        var method = Arrays.stream(mapper.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        return new XMLLanguageDriver().createSqlSource(new Configuration(), sql, Map.class).getBoundSql(parameters);
    }

    private String normalized(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").strip();
    }

    private String tenantSql(String sql) {
        return new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new StringValue("TENANT_A"); }
        }).parserSingle(sql, null);
    }
}
