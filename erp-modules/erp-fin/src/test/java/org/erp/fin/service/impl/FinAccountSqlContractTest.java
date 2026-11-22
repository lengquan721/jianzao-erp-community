package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.erp.fin.domain.FinAccountMovement;
import org.erp.fin.domain.vo.FinAccountFlowVo;
import org.erp.fin.mapper.FinAccountMapper;
import org.erp.master.domain.ErpCustomer;
import org.erp.master.domain.ErpSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 渲染实际账户 SQL 并通过生产同类租户解析器；不启动 Spring、不连接数据库。 */
@Tag("dev")
class FinAccountSqlContractTest {

    @Test
    void countAndPageShareAllHistoryUnionAndTenantSoftDeleteConfirmedAccountPredicates() {
        BoundSql count = render("countFlows", flowParameters());
        BoundSql page = render("selectFlowPage", flowParameters());
        String countSql = normalized(count);
        String pageSql = normalized(page);
        String unionSql = normalized(renderScript(FinAccountMapper.FLOW_UNION, flowParameters()));

        assertTrue(countSql.contains(unionSql), countSql);
        assertTrue(pageSql.contains(unionSql), pageSql);
        assertEquals(2, occurrences(countSql, "union all"));
        assertEquals(2, occurrences(pageSql, "union all"));
        assertEquals(List.of("tenantId", "accountId", "tenantId", "accountId", "tenantId", "accountId"), names(count));
        assertEquals(List.of("tenantId", "accountId", "tenantId", "accountId", "tenantId", "accountId",
            "offset", "limit", "tenantId", "tenantId"), names(page));
        assertEquals(List.of("TENANT_A", 7L, "TENANT_A", 7L, "TENANT_A", 7L, 4L, 2L, "TENANT_A", "TENANT_A"),
            values(page));
        for (String alias : List.of("r", "p", "o")) {
            assertScoped(count, alias);
            assertScoped(page, alias);
            assertTrue(countSql.contains(alias + ".status = 'confirmed'"), countSql);
            assertTrue(countSql.contains(alias + ".account_id = ?"), countSql);
        }
        // 旧接口不接收日期条件：计数、取页都保留全部业务日期（含 null），不能改为确认日或按天截断。
        for (String sql : List.of(countSql, pageSql)) {
            assertFalse(sql.contains("confirmed_at"), sql);
            assertFalse(sql.contains("biz_date >"), sql);
            assertFalse(sql.contains("biz_date <"), sql);
            assertFalse(sql.contains("date("), sql);
            assertFalse(sql.contains("date_format("), sql);
            assertFalse(sql.contains("distinct"), "同日同 ID 的不同来源不能去重：" + sql);
        }
    }

    @Test
    void overlappingDatesAndCrossTableIdsKeepOldStableOrderBeforeLimitAndAfterPageNameJoins() {
        BoundSql page = render("selectFlowPage", flowParameters());
        String sql = normalized(page);
        assertTrue(sql.contains("r.id as row_id, 0 as source_order"), sql);
        assertTrue(sql.contains("p.id as row_id, 1 as source_order"), sql);
        assertTrue(sql.contains("o.id as row_id, 2 as source_order"), sql);
        // MySQL DESC 的 null 日期排末尾；日期/ID 相同按旧 Java 稳定 UNION 顺序收款、付款、其他。
        assertTrue(sql.contains("order by u.biz_date desc, u.row_id desc, u.source_order asc limit ?, ?"), sql);
        assertTrue(sql.endsWith("order by f.biz_date desc, f.row_id desc, f.source_order asc"), sql);
        assertTrue(sql.indexOf("limit ?, ?") < sql.indexOf("left join md_customer"), sql);
        assertTrue(sql.contains("left join md_customer c on f.biz_type = 'receipt' and c.id = f.party_id"), sql);
        assertTrue(sql.contains("left join md_supplier s on f.biz_type = 'payment' and s.id = f.party_id"), sql);
        assertTrue(sql.contains("left join " + ErpCustomer.class.getAnnotation(TableName.class).value() + " c "), sql);
        assertTrue(sql.contains("left join " + ErpSupplier.class.getAnnotation(TableName.class).value() + " s "), sql);
        assertScoped(page, "c");
        assertScoped(page, "s");
        assertTrue(sql.contains("else f.counterparty end as counterparty"), sql);
        assertFalse(normalized(render("countFlows", flowParameters())).contains("join"), "名称缺失不改变 total");
    }

    @Test
    void flowAmountStaysUnsignedOriginalWhileRefundPaymentAndOtherDirectionsStayCompatible() {
        String sql = normalized(render("selectFlowPage", flowParameters()));
        assertTrue(sql.contains("r.biz_date, r.amount,"), sql);
        assertTrue(sql.contains("case when r.negative = 1 then 'out' else 'in' end as direction"), sql);
        assertTrue(sql.contains("p.biz_date, p.amount, 'out' as direction"), sql);
        assertTrue(sql.contains("case when cast(o.flow_type as binary) = 'income' then 'in' else 'out' end"), sql);
        assertFalse(sql.contains("-r.amount"), "流水金额本身不能取负；只改变退款方向");
        assertFalse(sql.contains("coalesce(r.amount"), "流水的 null 金额仍保持 null");
    }

    @Test
    void currentAccountIdsAreBoundInsideEveryDecimalAggregateBranchWithExistingSigns() {
        BoundSql bound = render("selectMovements", Map.of("tenantId", "TENANT_A", "accountIds", List.of(7L, 8L)));
        String sql = normalized(bound);
        for (String alias : List.of("r", "p", "o")) {
            assertScoped(bound, alias);
            assertTrue(sql.contains(alias + ".status = 'confirmed'"), sql);
            assertTrue(sql.matches("(?s).*" + alias + "\\.account_id in \\(\\s*\\?\\s*,\\s*\\?\\s*\\).*"), sql);
            assertTrue(sql.contains("group by " + alias + ".account_id"), sql);
        }
        assertEquals(2, occurrences(sql, "union all"));
        assertTrue(sql.contains("sum(m.movement) as movement"), sql);
        assertTrue(sql.endsWith("group by m.account_id"), sql);
        assertTrue(sql.contains("case when r.negative = 1 then -coalesce(r.amount, 0) else coalesce(r.amount, 0) end"), sql);
        assertTrue(sql.contains("-sum(coalesce(p.amount, 0)) as movement"), sql);
        assertTrue(sql.contains("case when cast(o.flow_type as binary) = 'income' then coalesce(o.amount, 0) else -coalesce(o.amount, 0) end"), sql);
        assertEquals(List.of("TENANT_A", 7L, 8L, "TENANT_A", 7L, 8L, "TENANT_A", 7L, 8L), values(bound));
        assertFalse(sql.contains("round("), "SQL 不能分项提前舍入");
        assertFalse(sql.contains("double"), sql);
        assertFalse(sql.contains("float"), sql);
        assertFalse(sql.contains("opening_balance"), "期初只取账户当前页实体，不重复读/累加");
        assertFalse(sql.contains("biz_date >"), sql);
        assertFalse(sql.contains("biz_date <"), sql);
    }

    @Test
    void emptyOrNullAccountIdCollectionFailsClosedInsteadOfAggregatingWholeTenant() {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", "TENANT_A");
        for (List<Long> ids : Arrays.<List<Long>>asList(List.of(), null)) {
            params.put("accountIds", ids);
            BoundSql bound = render("selectMovements", params);
            String sql = normalized(bound);
            assertEquals(3, occurrences(sql, "and 1 = 0"), sql);
            assertFalse(sql.contains(" in "), sql);
            assertEquals(List.of("tenantId", "tenantId", "tenantId"), names(bound));
            for (String alias : List.of("r", "p", "o")) {
                assertScoped(bound, alias);
            }
        }
    }

    @Test
    void internalMovementProjectionDoesNotExpandFlowReturnShapeOrLoseDateAndDecimalTypes() throws Exception {
        assertEquals(BigDecimal.class, FinAccountMovement.class.getDeclaredField("movement").getType());
        assertEquals(BigDecimal.class, FinAccountFlowVo.class.getDeclaredField("amount").getType());
        assertEquals(Date.class, FinAccountFlowVo.class.getDeclaredField("bizDate").getType());
        assertEquals(Set.of("bizType", "bizNo", "bizDate", "amount", "direction", "counterparty", "rowId"),
            Arrays.stream(FinAccountFlowVo.class.getDeclaredFields()).map(field -> field.getName()).collect(Collectors.toSet()));
    }

    private Map<String, Object> flowParameters() {
        return Map.of("tenantId", "TENANT_A", "accountId", 7L, "offset", 4L, "limit", 2L);
    }

    private BoundSql render(String methodName, Map<String, ?> parameters) {
        Method method = Arrays.stream(FinAccountMapper.class.getDeclaredMethods())
            .filter(candidate -> methodName.equals(candidate.getName())).findFirst().orElseThrow();
        return renderScript(String.join(" ", method.getAnnotation(Select.class).value()), parameters);
    }

    private BoundSql renderScript(String script, Map<String, ?> parameters) {
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class).getBoundSql(parameters);
    }

    private String normalized(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT);
    }

    private List<String> names(BoundSql bound) {
        return bound.getParameterMappings().stream().map(ParameterMapping::getProperty).toList();
    }

    private List<Object> values(BoundSql bound) {
        return bound.getParameterMappings().stream().map(mapping -> {
            String name = mapping.getProperty();
            return bound.hasAdditionalParameter(name) ? bound.getAdditionalParameter(name)
                : ((Map<?, ?>) bound.getParameterObject()).get(name);
        }).toList();
    }

    private void assertScoped(BoundSql bound, String alias) {
        String sql = normalized(bound);
        assertTrue(sql.contains(alias + ".tenant_id = ?"), sql);
        assertTrue(sql.contains(alias + ".del_flag = '0'"), sql);
        String expanded = new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue("TENANT_A");
            }
        }).parserSingle(bound.getSql(), null).toLowerCase(java.util.Locale.ROOT);
        assertTrue(expanded.contains(alias + ".tenant_id = 'tenant_a'"), expanded);
    }

    private int occurrences(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }
}
