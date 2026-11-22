package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.erp.trade.domain.vo.DashboardAggregateVo;
import org.erp.trade.domain.vo.ErpReturnQtyAggregateVo;
import org.erp.trade.mapper.DashboardAggregateMapper;
import org.erp.trade.mapper.ErpPurchaseOrderMapper;
import org.erp.trade.mapper.ErpPurchaseReturnItemMapper;
import org.erp.trade.mapper.ErpSaleItemMapper;
import org.erp.trade.mapper.ErpSaleOrderMapper;
import org.erp.trade.mapper.ErpSaleReturnItemMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册 trade 实际 @Select / @SelectProvider mapped statement，并经过生产同类租户解析器的 SQL 契约测试。
 *
 * <p>不连接数据库：重点检查动态 XML 已展开、绑定参数仍存在、租户/软删条件覆盖每个
 * UNION 分支，而不是只对 Java 源码做关键字匹配。</p>
 */
@Tag("dev")
class TradeSqlContractTest {

    private static final Date FROM = new java.sql.Date(1761955200000L);
    private static final Date TO = new java.sql.Date(1764547200000L);

    @Test
    void dashboardEveryAggregateBranch_isRenderedTenantScopedAndSoftDeleted() throws Exception {
        Map<String, Set<String>> aliases = new LinkedHashMap<>();
        aliases.put("monthSales", Set.of("s"));
        aliases.put("monthPurchases", Set.of("p"));
        aliases.put("monthReceipts", Set.of("r"));
        aliases.put("trendSales", Set.of("s"));
        aliases.put("trendReceipts", Set.of("r"));
        aliases.put("costLedgerSum", Set.of("c"));
        aliases.put("salesCostSummary", Set.of("s"));
        aliases.put("salesReturnCostSummary", Set.of("r"));
        aliases.put("trendOtherCosts", Set.of("c"));
        aliases.put("customerSales", Set.of("s"));
        aliases.put("customerReceipts", Set.of("r"));
        aliases.put("supplierPurchases", Set.of("p"));
        aliases.put("supplierPayments", Set.of("p"));
        aliases.put("rankCustomerSales", Set.of("s"));
        aliases.put("rankCustomerReceipts", Set.of("r"));
        aliases.put("rankSupplierPurchases", Set.of("p"));
        aliases.put("rankSupplierPayments", Set.of("p"));
        Set<String> actualMethods = sqlMethods(DashboardAggregateMapper.class);
        assertEquals(aliases.keySet(), actualMethods, "新增看板 SQL 必须加入租户/软删契约覆盖表");

        for (Map.Entry<String, Set<String>> entry : aliases.entrySet()) {
            BoundSql bound = renderTyped(DashboardAggregateMapper.class, entry.getKey(),
                dashboardParams(entry.getKey()), DashboardAggregateVo.class);
            assertScoped(bound, entry.getValue(), 1);
            assertEquals(expectedDashboardParams(entry.getKey()), parameterNames(bound), entry.getKey());
            assertEquals(List.of(FROM, TO), bound.getParameterMappings().stream()
                .filter(mapping -> Set.of("from", "to").contains(mapping.getProperty()))
                .map(mapping -> parameterValue(bound, mapping)).toList(), entry.getKey());
            String sql = normalized(bound);
            if ("salesReturnCostSummary".equals(entry.getKey())) {
                assertTrue(sql.startsWith("select sum(r.total_amount) as amount, sum(r.return_cost_amount) as cost from biz_sale_return r"), sql);
                assertTrue(sql.contains("r.status = 'CONFIRMED'"), sql);
                assertTrue(sql.contains("r.biz_date >= ? and r.biz_date < ?"), sql);
                assertFalse(sql.contains("group by") || sql.contains("round(") || sql.contains("abs("), sql);
            }
            if (entry.getKey().toLowerCase().contains("receipt")) {
                assertTrue(sql.contains("case when r.negative = 1 then -r.amount else r.amount end"), sql);
            } else {
                assertFalse(sql.contains("negative"), entry.getKey());
            }
        }
    }

    @Test
    void saleItemCostCount_isRenderedAsTenantScopedPeriodJoinWithoutInList() throws Exception {
        BoundSql bound = render(ErpSaleItemMapper.class, "countMissingCostBySaleWindow", Map.of(
            "tenantId", "TENANT_A", "from", FROM, "to", TO));
        String sql = normalized(bound);

        assertTrue(sql.contains("from biz_sale_item i join biz_sale h on h.id = i.order_id and h.tenant_id = i.tenant_id"), sql);
        assertTrue(sql.contains("h.biz_date >= ? and h.biz_date < ?"), sql);
        assertFalse(Pattern.compile("\\bin\\s*\\(").matcher(sql).find(), sql);
        assertScoped(bound, Set.of("i", "h"), 2);
        assertEquals(List.of("tenantId", "tenantId", "from", "to"), parameterNames(bound));
    }

    @Test
    void saleExecutionRecords_coverPositiveDeliveryAndNegativeReturnBranches() throws Exception {
        BoundSql delivery = render(ErpSaleOrderMapper.class, "selectDeliveries", Map.of(
            "orderId", 7L, "tenantId", "TENANT_A"));
        assertScoped(delivery, Set.of("h", "i"), 2);
        assertEquals(1, count(normalized(delivery), "sum(i.qty) as qty"));
        assertTrue(normalized(delivery).contains("h.total_amount as amount"), normalized(delivery));
        assertEquals(List.of("orderId", "tenantId", "tenantId"), parameterNames(delivery));

        BoundSql returns = render(ErpSaleOrderMapper.class, "selectReturns", Map.of(
            "orderId", 7L, "tenantId", "TENANT_A"));
        assertScoped(returns, Set.of("rh", "r", "i", "h"), 4);
        String returnSql = normalized(returns);
        assertTrue(returnSql.contains("sum(r.qty) as qty"), returnSql);
        assertTrue(returnSql.contains("-rh.total_amount as amount"), returnSql);
        assertEquals(1, count(returnSql, "rh.sale_id = h.id"));
        assertEquals(List.of("orderId", "tenantId", "tenantId", "tenantId", "tenantId"), parameterNames(returns));
    }

    @Test
    void purchaseExecutionRecords_coverPositiveReceiptAndNegativeReturnBranches() throws Exception {
        BoundSql receipt = render(ErpPurchaseOrderMapper.class, "selectReceipts", Map.of(
            "orderId", 8L, "tenantId", "TENANT_A"));
        assertScoped(receipt, Set.of("h", "i"), 2);
        assertTrue(normalized(receipt).contains("h.total_amount as amount"), normalized(receipt));
        assertEquals(List.of("orderId", "tenantId", "tenantId"), parameterNames(receipt));

        BoundSql returns = render(ErpPurchaseOrderMapper.class, "selectReturns", Map.of(
            "orderId", 8L, "tenantId", "TENANT_A"));
        assertScoped(returns, Set.of("rh", "r", "i", "h"), 4);
        String returnSql = normalized(returns);
        assertTrue(returnSql.contains("sum(r.qty) as qty"), returnSql);
        assertTrue(returnSql.contains("-rh.total_amount as amount"), returnSql);
        assertEquals(1, count(returnSql, "rh.purchase_id = h.id"));
        assertEquals(List.of("orderId", "tenantId", "tenantId", "tenantId", "tenantId"), parameterNames(returns));
    }

    @Test
    void saleAndPurchaseExecutionAmountUnion_expandsBothBranchesAndBindsPageIds() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("orderIds", List.of(101L, 102L));
        params.put("tenantId", "TENANT_A");

        BoundSql sale = render(ErpSaleOrderMapper.class, "selectExecutionAmounts", params);
        assertExecutionAmountUnion(sale, Set.of("h", "rh", "sh"));
        String saleSql = normalized(sale);
        assertTrue(saleSql.contains("h.total_amount as amount"), saleSql);
        assertTrue(saleSql.contains("-rh.total_amount as amount"), saleSql);
        assertPageIdsFilter(sale, "h");
        assertPageIdsFilter(sale, "sh");

        BoundSql purchase = render(ErpPurchaseOrderMapper.class, "selectExecutionAmounts", params);
        assertExecutionAmountUnion(purchase, Set.of("h", "rh", "ph"));
        String purchaseSql = normalized(purchase);
        assertTrue(purchaseSql.contains("h.total_amount as amount"), purchaseSql);
        assertTrue(purchaseSql.contains("-rh.total_amount as amount"), purchaseSql);
        assertPageIdsFilter(purchase, "h");
        assertPageIdsFilter(purchase, "ph");
    }

    @Test
    void saleReturnQuantityAggregate_isTypedTenantScopedAndExcludesOnlyTheRequestedDocument() throws Exception {
        assertReturnQuantityAggregate(ErpSaleReturnItemMapper.class, "sale_item_id", "biz_sale_return");
    }

    @Test
    void purchaseReturnQuantityAggregate_isTypedTenantScopedAndExcludesOnlyTheRequestedDocument() throws Exception {
        assertReturnQuantityAggregate(ErpPurchaseReturnItemMapper.class, "purchase_item_id", "biz_purchase_return");
    }

    private void assertReturnQuantityAggregate(Class<?> mapper, String originalItemColumn, String headTable) throws Exception {
        assertEquals(Set.of("sumConfirmedReturnedQty"), sqlMethods(mapper),
            "新增退货明细 SQL 必须加入租户/软删契约覆盖表：" + mapper.getSimpleName());
        for (Long excludeReturnId : Arrays.asList(null, 77L)) {
            Map<String, Object> params = new HashMap<>();
            params.put("originalItemIds", List.of(101L, 102L));
            params.put("tenantId", "TENANT_A");
            params.put("excludeReturnId", excludeReturnId);
            BoundSql bound = renderTyped(mapper, "sumConfirmedReturnedQty", params, ErpReturnQtyAggregateVo.class);
            String sql = normalized(bound);

            assertTrue(sql.startsWith("select r." + originalItemColumn
                + " as originalItemId, sum(r.qty) as returnedQty from " + headTable + "_item r"), sql);
            assertTrue(sql.contains("join " + headTable
                + " rh on rh.id = r.order_id and rh.tenant_id = r.tenant_id"), sql);
            assertTrue(Pattern.compile(Pattern.quote("r." + originalItemColumn)
                + "\\s+in\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)").matcher(sql).find(), sql);
            assertTrue(sql.contains("rh.status = 'CONFIRMED'"), sql);
            assertFalse(sql.contains("'DRAFT'") || sql.contains("'VOID'"), sql);
            assertTrue(sql.endsWith("group by r." + originalItemColumn), sql);
            assertScoped(bound, Set.of("r", "rh"), 2);

            List<String> names = parameterNames(bound);
            assertEquals(excludeReturnId == null ? 4 : 5, names.size(), names.toString());
            assertEquals(List.of("tenantId", "tenantId"), names.subList(2, 4));
            if (excludeReturnId == null) {
                assertFalse(sql.contains("rh.id <>"), sql);
                assertFalse(names.contains("excludeReturnId"), names.toString());
            } else {
                assertTrue(sql.contains("rh.id <> ?"), sql);
                assertEquals("excludeReturnId", names.get(4));
            }
            List<?> expectedValues = excludeReturnId == null
                ? List.of(101L, 102L, "TENANT_A", "TENANT_A")
                : List.of(101L, 102L, "TENANT_A", "TENANT_A", excludeReturnId);
            assertEquals(expectedValues, bound.getParameterMappings().stream()
                .map(mapping -> parameterValue(bound, mapping)).toList());
        }
    }

    private void assertPageIdsFilter(BoundSql bound, String alias) {
        String sql = normalized(bound);
        Pattern filter = Pattern.compile("\\b" + Pattern.quote(alias + ".source_order_id")
            + "\\s+in\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)", Pattern.CASE_INSENSITIVE);
        assertEquals(1L, filter.matcher(sql).results().count(), sql);
    }

    private void assertExecutionAmountUnion(BoundSql bound, Set<String> aliases) {
        String sql = normalized(bound);
        assertTrue(sql.contains("union all"), sql);
        assertScoped(bound, aliases, 3);
        assertEquals(4, parameterNames(bound).stream().filter(name -> !"tenantId".equals(name)).count(),
            parameterNames(bound).toString());
        assertEquals(3, parameterNames(bound).stream().filter("tenantId"::equals).count(),
            parameterNames(bound).toString());
        List<Object> tenantValues = bound.getParameterMappings().stream()
            .filter(mapping -> "tenantId".equals(mapping.getProperty()))
            .map(mapping -> parameterValue(bound, mapping))
            .toList();
        assertEquals(List.of("TENANT_A", "TENANT_A", "TENANT_A"), tenantValues);
        List<Object> orderIds = bound.getParameterMappings().stream()
            .filter(mapping -> !"tenantId".equals(mapping.getProperty()))
            .map(mapping -> parameterValue(bound, mapping))
            .toList();
        assertEquals(List.of(101L, 102L, 101L, 102L), orderIds);
    }

    private Object parameterValue(BoundSql bound, ParameterMapping mapping) {
        String property = mapping.getProperty();
        if (bound.hasAdditionalParameter(property)) {
            return bound.getAdditionalParameter(property);
        }
        Object parameterObject = bound.getParameterObject();
        if (parameterObject instanceof Map<?, ?> values) {
            return values.get(property);
        }
        return null;
    }

    private void assertScoped(BoundSql bound, Set<String> aliases, int tenantBindingCount) {
        String sql = normalized(bound);
        String lowerSql = sql.toLowerCase();
        for (String alias : aliases) {
            assertTrue(lowerSql.contains(alias.toLowerCase() + ".tenant_id = ?"), alias + " tenant: " + sql);
            assertTrue(lowerSql.contains(alias.toLowerCase() + ".del_flag = '0'"), alias + " soft-delete: " + sql);
        }
        String expanded = tenantSql(sql).toLowerCase();
        for (String alias : aliases) {
            assertTrue(expanded.contains(alias.toLowerCase() + ".tenant_id = 'tenant_a'"),
                alias + " tenant expansion: " + expanded);
        }
        assertEquals(tenantBindingCount, parameterNames(bound).stream().filter("tenantId"::equals).count(),
            parameterNames(bound).toString());
        assertEquals(java.util.Collections.nCopies(tenantBindingCount, "TENANT_A"),
            bound.getParameterMappings().stream().filter(mapping -> "tenantId".equals(mapping.getProperty()))
                .map(mapping -> parameterValue(bound, mapping)).toList());
    }

    private List<String> expectedDashboardParams(String method) {
        if ("costLedgerSum".equals(method)) {
            return List.of("tenantId", "costType", "from", "to");
        }
        if (method.startsWith("customer") || method.startsWith("supplier")) {
            return List.of("tenantId", "partyId", "from", "to");
        }
        return List.of("tenantId", "from", "to");
    }

    private Map<String, Object> dashboardParams(String method) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", "TENANT_A");
        params.put("from", FROM);
        params.put("to", TO);
        if ("costLedgerSum".equals(method)) {
            params.put("costType", "OTHER_OUT");
        }
        if (method.startsWith("customer") || method.startsWith("supplier")) {
            params.put("partyId", 7L);
        }
        return params;
    }

    private Set<String> sqlMethods(Class<?> mapper) {
        return Arrays.stream(mapper.getDeclaredMethods())
            .filter(method -> method.getAnnotation(Select.class) != null
                || method.getAnnotation(SelectProvider.class) != null)
            .map(Method::getName)
            .collect(java.util.stream.Collectors.toSet());
    }

    private MappedStatement mappedStatement(Class<?> mapper, String methodName) throws Exception {
        Method method = Arrays.stream(mapper.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(mapper.getName() + "#" + methodName));
        assertTrue(method.getAnnotation(Select.class) != null || method.getAnnotation(SelectProvider.class) != null,
            mapper.getName() + "#" + methodName);
        var configuration = new MybatisConfiguration();
        configuration.addMapper(mapper);
        MappedStatement statement = configuration.getMappedStatement(mapper.getName() + "." + methodName);
        assertNotNull(statement);
        assertEquals(SqlCommandType.SELECT, statement.getSqlCommandType());
        return statement;
    }

    private BoundSql render(Class<?> mapper, String methodName, Map<String, ?> parameters) throws Exception {
        return mappedStatement(mapper, methodName).getBoundSql(parameters);
    }

    private BoundSql renderTyped(Class<?> mapper, String methodName, Map<String, ?> parameters,
                                 Class<?> projection) throws Exception {
        MappedStatement statement = mappedStatement(mapper, methodName);
        assertEquals(List.of(projection), statement.getResultMaps().stream().map(result -> result.getType()).toList(),
            mapper.getName() + "#" + methodName);
        return statement.getBoundSql(parameters);
    }

    private List<String> parameterNames(BoundSql bound) {
        return bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList();
    }

    private String normalized(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").strip();
    }

    private String tenantSql(String sql) {
        return new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue("TENANT_A");
            }
        }).parserSingle(sql, null);
    }

    private int count(String value, String fragment) {
        int count = 0;
        int start = 0;
        while ((start = value.indexOf(fragment, start)) >= 0) {
            count++;
            start += fragment.length();
        }
        return count;
    }
}
