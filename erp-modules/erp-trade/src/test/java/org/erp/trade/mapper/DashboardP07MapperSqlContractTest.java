package org.erp.trade.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.erp.stock.domain.vo.ErpStockAlertTopRow;
import org.erp.stock.mapper.ErpStockAlertMapper;
import org.erp.trade.domain.vo.DashboardAggregateVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过 mapper 注册与真实参数名称解析生成 BoundSql，不打开会话或连接数据库。 */
@Tag("dev")
class DashboardP07MapperSqlContractTest {

    @Test
    void returnsBindTenantAndHalfOpenPeriodAndProjectOnlySignedSums() throws Exception {
        String tenantId = "TENANT_P07";
        Date from = java.sql.Date.valueOf("2026-12-01");
        Date to = java.sql.Date.valueOf("2027-01-01");
        Method method = DashboardAggregateMapper.class.getMethod("salesReturnCostSummary", String.class, Date.class, Date.class);
        BoundSql bound = bind(method, DashboardAggregateVo.class, tenantId, from, to);
        String sql = normalized(bound.getSql());

        // 只聚合退货头，不分组、不取明细，且不提前改变符号或精度。
        assertEquals(normalized("""
            select sum(r.total_amount) as amount, sum(r.return_cost_amount) as cost
            from biz_sale_return r
            where r.tenant_id = ? and r.del_flag = '0' and r.status = 'CONFIRMED'
              and r.biz_date >= ? and r.biz_date < ?
            """), sql);
        assertBindings(bound, List.of("tenantId", "from", "to"), List.of(tenantId, from, to));

        String scoped = new TenantLineInnerInterceptor(() -> new StringValue(tenantId)).parserSingle(sql, null);
        assertTrue(scoped.contains("r.tenant_id = 'TENANT_P07'"), scoped);
    }

    @Test
    void stockCardBindsFiveWithoutProductFilterAndCutsAfterCountingPerType() throws Exception {
        Method method = ErpStockAlertMapper.class.getMethod("selectTopAlerts", Long.class, int.class);
        BoundSql bound = bind(method, ErpStockAlertTopRow.class, null, 5);
        String sql = normalized(bound.getSql());

        assertBindings(bound, List.of("limit"), List.of(5));
        assertTrue(sql.startsWith("select r.productId, r.productName, r.totalQty, r.gap, r.alertType, r.alertCount"
            + " from (select "), sql);
        assertTrue(sql.contains("count(*) over (partition by a.alertType) as alertCount"), sql);
        assertTrue(sql.contains("row_number() over (partition by a.alertType"
            + " order by abs(a.gap) desc, a.productId asc) as alertRank"), sql);
        assertTrue(sql.endsWith("a where a.alertType is not null) r where r.alertRank <= ? order by r.alertType, r.alertRank"), sql);
    }

    private static BoundSql bind(Method method, Class<?> projection, Object... args) {
        var configuration = new MybatisConfiguration();
        Class<?> mapper = method.getDeclaringClass();
        configuration.addMapper(mapper);
        var statement = configuration.getMappedStatement(mapper.getName() + "." + method.getName());
        assertEquals(projection, statement.getResultMaps().get(0).getType());
        Object params = new ParamNameResolver(configuration, method).getNamedParams(args);
        return statement.getBoundSql(params);
    }

    private static void assertBindings(BoundSql bound, List<String> names, List<?> values) {
        List<String> properties = bound.getParameterMappings().stream().map(ParameterMapping::getProperty).toList();
        assertEquals(names, properties);
        Map<?, ?> arguments = (Map<?, ?>) bound.getParameterObject();
        assertEquals(values, properties.stream().map(arguments::get).toList());
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }
}
