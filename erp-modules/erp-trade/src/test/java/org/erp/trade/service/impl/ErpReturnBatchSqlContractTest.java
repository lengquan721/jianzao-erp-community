package org.erp.trade.service.impl;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.erp.trade.mapper.ErpPurchaseReturnItemMapper;
import org.erp.trade.mapper.ErpSaleReturnItemMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renders only the new return-quantity SQL; no database access. */
@Tag("dev")
class ErpReturnBatchSqlContractTest {

    @Test
    void salesReturnQuantityAggregateIsGroupedTenantScopedAndExcludesCurrentDocument() throws Exception {
        assertAggregate(ErpSaleReturnItemMapper.class, "sale_item_id", "biz_sale_return_item",
            "biz_sale_return");
    }

    @Test
    void purchaseReturnQuantityAggregateIsGroupedTenantScopedAndExcludesCurrentDocument() throws Exception {
        assertAggregate(ErpPurchaseReturnItemMapper.class, "purchase_item_id", "biz_purchase_return_item",
            "biz_purchase_return");
    }

    private void assertAggregate(Class<?> mapper, String fk, String itemTable, String headTable) throws Exception {
        BoundSql bound = render(mapper, Map.of("originalItemIds", List.of(10L, 11L),
            "tenantId", "TENANT_A", "excludeReturnId", 77L));
        String sql = normalized(bound);
        assertTrue(sql.contains(("select r." + fk + " as originalItemId, sum(r.qty) as returnedQty").toLowerCase()), sql);
        assertTrue(sql.contains("from " + itemTable + " r join " + headTable
            + " rh on rh.id = r.order_id and rh.tenant_id = r.tenant_id"), sql);
        assertTrue(java.util.regex.Pattern.compile("\\br\\." + fk + "\\s+in\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)")
            .matcher(sql).find(), sql);
        assertTrue(sql.contains("rh.status = 'confirmed'"), sql);
        assertFalse(sql.contains("'draft'") || sql.contains("'void'"), sql);
        assertTrue(sql.contains("rh.id <> ?"), sql);
        assertTrue(sql.contains("group by r." + fk), sql);
        assertTrue(sql.contains("r.del_flag = '0'") && sql.contains("rh.del_flag = '0'"), sql);
        assertTrue(sql.contains("r.tenant_id = ?") && sql.contains("rh.tenant_id = ?"), sql);
        assertEquals(2, bound.getParameterMappings().stream()
            .filter(mapping -> "tenantId".equals(mapping.getProperty())).count());
        String tenantExpanded = new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue("TENANT_A");
            }
        }).parserSingle(sql, null).toLowerCase();
        assertTrue(tenantExpanded.contains("r.tenant_id = 'tenant_a'"), tenantExpanded);
        assertTrue(tenantExpanded.contains("rh.tenant_id = 'tenant_a'"), tenantExpanded);

        Map<String, Object> withoutExclusionParams = new HashMap<>();
        withoutExclusionParams.put("originalItemIds", List.of(10L));
        withoutExclusionParams.put("tenantId", "TENANT_A");
        withoutExclusionParams.put("excludeReturnId", null);
        BoundSql withoutExclusion = render(mapper, withoutExclusionParams);
        assertFalse(normalized(withoutExclusion).contains("rh.id <>"), normalized(withoutExclusion));
        assertFalse(parameterNames(withoutExclusion).contains("excludeReturnId"), parameterNames(withoutExclusion).toString());
    }

    private BoundSql render(Class<?> mapper, Map<String, ?> parameters) throws Exception {
        Method method = Arrays.stream(mapper.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("sumConfirmedReturnedQty"))
            .findFirst().orElseThrow();
        Select select = method.getAnnotation(Select.class);
        assertNotNull(select, mapper.getName());
        return new XMLLanguageDriver().createSqlSource(new Configuration(), String.join(" ", select.value()), Map.class)
            .getBoundSql(parameters);
    }

    private String normalized(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").strip().toLowerCase();
    }

    private List<String> parameterNames(BoundSql bound) {
        return bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList();
    }
}
