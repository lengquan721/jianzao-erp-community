package org.erp.master.mapper;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.erp.master.domain.bo.MasterOptionQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 渲染真实 @Select 动态 SQL，再过生产同类型租户解析器；不需要数据库连接。 */
@Tag("dev")
class MasterOptionMapperSqlTest {
    private static final List<Class<?>> MAPPERS = List.of(ErpProductMapper.class, ErpCustomerMapper.class,
        ErpSupplierMapper.class, ErpWarehouseMapper.class, ErpProductCategoryMapper.class);

    @Test
    void allOptionPages_haveBoundedDatabaseLimitAndTenantAndLogicalDelete() throws Exception {
        var params = params(MasterOptionQuery.of("AB", null, 1, 3, 100));
        for (Class<?> mapper : MAPPERS) {
            BoundSql bound = render(mapper, "selectOptions", params);
            String sql = bound.getSql().replaceAll("\\s+", " ");
            assertTrue(sql.contains("where m.del_flag = '0'"), mapper.getName());
            assertTrue(sql.contains("m.status = ?"));
            assertTrue(sql.contains("limit ? offset ?"));
            assertTrue(sql.contains("case when m.code = ? then 0 when m.code like ? escape '!' then 1 else 2 end"));
            assertTrue(sql.contains("m.name like ? escape '!'"));
            assertTrue(bound.getParameterMappings().stream().anyMatch(p -> p.getProperty().equals("q.fetchSize")));
            assertTrue(bound.getParameterMappings().stream().anyMatch(p -> p.getProperty().equals("q.offset")));
            String scoped = tenantSql(sql);
            assertTrue(scoped.contains("m.tenant_id = 'TENANT_A'"), scoped);
            if (mapper == ErpProductMapper.class) {
                assertTrue(scoped.contains("c.tenant_id = 'TENANT_A'"), scoped);
                assertTrue(scoped.contains("s.tenant_id = 'TENANT_A'"), scoped);
                assertTrue(sql.contains("order by case"));
                assertTrue(sql.contains("m.code, m.id limit"));
                assertFalse(sql.contains("order by c.sort"));
                assertTrue(sql.contains("m.spec like ?"));
            }
        }
    }

    @Test
    void exactCodeAndSingleCharacter_doNotTurnIntoUnboundedNameScan() throws Exception {
        for (Class<?> mapper : MAPPERS) {
            String exact = render(mapper, "selectOptions", params(MasterOptionQuery.of(null, "P01", null, 1, 30))).getSql();
            assertTrue(exact.contains("m.code = ?"));
            assertFalse(exact.contains(" like "));
            String shortKeyword = render(mapper, "selectOptions", params(MasterOptionQuery.of("钢", null, null, 1, 30))).getSql();
            assertTrue(shortKeyword.contains("m.code like ?"));
            assertFalse(shortKeyword.contains("m.name like"));
            assertFalse(shortKeyword.contains("m.spec like"));
            assertFalse(shortKeyword.contains("m.phone like"));
        }
    }

    @Test
    void selectedIds_haveNoPageStatusOrKeywordFilter_andAreTenantScoped() throws Exception {
        for (Class<?> mapper : MAPPERS) {
            String sql = render(mapper, "selectOptionsByIds", Map.of("ids", List.of(900L, 901L))).getSql();
            assertTrue(sql.contains("m.id in"));
            assertFalse(sql.contains("m.status ="));
            assertFalse(sql.contains(" like "));
            assertFalse(sql.contains("limit"));
            assertTrue(tenantSql(sql).contains("m.tenant_id = 'TENANT_A'"));
        }
    }

    @Test
    void maliciousKeyword_isBoundValueNotSqlText() throws Exception {
        String keyword = "x' OR 1=1 --_%";
        var params = params(MasterOptionQuery.of(keyword, null, null, 1, 30));
        for (Class<?> mapper : MAPPERS) {
            String sql = render(mapper, "selectOptions", params).getSql();
            assertFalse(sql.contains(keyword));
            assertFalse(sql.contains("1=1"));
            assertNotNull(tenantSql(sql));
        }
    }

    @Test
    void existingExcelAndInternalFullLists_areNotSilentlyLimited() {
        assertFalse(ErpProductMapper.DETAIL_SQL.toLowerCase().contains("limit"));
        // 原 supplier/category service selectList 保持原路径；新 options 不复用它，不能切到 LIMIT200。
        assertTrue(ErpProductMapper.DETAIL_SQL.contains("order by c.sort, c.id, p.code"));
    }

    private Map<String, Object> params(MasterOptionQuery query) {
        Map<String, Object> params = new HashMap<>();
        params.put("q", query);
        params.put("type", "MATERIAL");
        params.put("categoryId", 2L);
        return params;
    }

    private BoundSql render(Class<?> mapper, String name, Map<String, ?> params) throws Exception {
        var method = java.util.Arrays.stream(mapper.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        String script = String.join(" ", method.getAnnotation(Select.class).value());
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class).getBoundSql(params);
    }

    private String tenantSql(String sql) {
        return new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new StringValue("TENANT_A"); }
        }).parserSingle(sql, null);
    }
}
