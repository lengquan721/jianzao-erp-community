package org.erp.stock.mapper;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class StockMapperSqlTest {

    @Test
    void balanceCountAndPage_shareDatabaseFiltersAndStableSort() throws Exception {
        Map<String, Object> params = Map.of("pattern", "%钢管%", "type", "MATERIAL", "offset", 100L, "size", 50);
        String count = render(ErpStockMapper.class, "countBalances", params).getSql();
        String page = render(ErpStockMapper.class, "selectBalancePage", params).getSql();
        for (String sql : List.of(count, page)) {
            assertTrue(sql.contains("p.type = ?"));
            assertTrue(sql.contains("lower(p.name) like ? escape '!'"));
            assertTrue(sql.contains("s.del_flag = '0'"));
            String scoped = tenantSql(sql);
            assertTrue(scoped.contains("s.tenant_id = 'TENANT_A'"), scoped);
            assertTrue(scoped.contains("p.tenant_id = 'TENANT_A'"), scoped);
        }
        assertTrue(page.contains("order by s.product_id, s.warehouse_id, s.id limit ? offset ?"));
        assertFalse(count.contains("limit"));
        String unfiltered = render(ErpStockMapper.class, "selectBalancePage", new HashMap<>(Map.of("offset", 0L, "size", 50))).getSql();
        assertTrue(unfiltered.contains("left join md_product"));
        assertFalse(unfiltered.contains("p.type ="));
        assertFalse(unfiltered.contains(" like "));
    }

    @Test
    void logCursor_isDateBoundedStrictAndUsesStableDateIdOrder() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("productId", 1L); params.put("startDate", LocalDate.of(2026, 1, 1));
        params.put("endDate", LocalDate.of(2026, 12, 31)); params.put("fetchSize", 101);
        String first = render(ErpStockLogMapper.class, "selectLogPage", params).getSql();
        assertTrue(first.contains("biz_date between ? and ?"));
        assertFalse(first.contains("id <"));
        assertTrue(first.contains("order by biz_date desc, id desc limit ?"));
        params.put("beforeDate", LocalDate.of(2026, 12, 31)); params.put("beforeId", 5L);
        String next = render(ErpStockLogMapper.class, "selectLogPage", params).getSql();
        assertTrue(next.contains("(biz_date < ? or (biz_date = ? and id < ?))"), next);
        assertFalse(next.contains("offset"));
        assertTrue(tenantSql(next).contains("tenant_id = 'TENANT_A'"));
    }

    @Test
    void replayAndReversal_areNarrowCurrentReadsAfterInventoryLock() throws Exception {
        String replay = render(ErpStockLogMapper.class, "selectReplayRowsForUpdate", Map.of("productId", 1L, "warehouseId", 1L)).getSql();
        assertTrue(replay.startsWith("select id, biz_type, qty, unit_cost from"));
        assertTrue(replay.endsWith("order by id for update"));
        assertFalse(replay.contains("biz_date"));
        assertFalse(replay.contains("amount"));
        assertFalse(replay.contains("limit")); // 无检查点证据，全历史，不得在成本核算中截断。
        Map<String, Object> params = new HashMap<>(); params.put("bizNo", "P01");
        String bill = render(ErpStockLogMapper.class, "selectBillRowsForUpdate", params).getSql();
        assertTrue(bill.endsWith("order by id for update"));
        assertTrue(bill.contains("partner_id"));
        assertFalse(bill.contains("biz_type !=")); // 一次读取 origin+VOID 两种，便于锁后判重。
        for (String sql : List.of(replay, bill)) {
            assertTrue(tenantSql(sql).contains("tenant_id = 'TENANT_A'"));
        }
    }

    @Test
    void alertTopN_hasFullCountsStablePerTypeRankAndCorrectThreshold() throws Exception {
        Map<String, Object> params = new HashMap<>(); params.put("limit", 5);
        String sql = render(ErpStockAlertMapper.class, "selectTopAlerts", params).getSql();
        assertTrue(sql.contains("count(*) over (partition by a.alertType) as alertCount"));
        assertTrue(sql.contains("row_number() over (partition by a.alertType order by abs(a.gap) desc, a.productId asc)"));
        assertTrue(sql.contains("where r.alertRank <= ?"));
        assertTrue(sql.contains("then 'HIGH'"));
        assertTrue(sql.contains("then coalesce(s.total_qty, 0) - p.max_stock"));
        assertTrue(sql.contains("where del_flag = '0'"));
        assertTrue(sql.contains("group by tenant_id, product_id"));
        String scoped = tenantSql(sql);
        assertTrue(scoped.contains("p.tenant_id = 'TENANT_A'"), scoped);
        assertTrue(scoped.toLowerCase().contains("from st_stock where del_flag = '0' and tenant_id = 'tenant_a'"), scoped);
        assertTrue(scoped.contains("s.tenant_id = p.tenant_id"), scoped);
        String full = render(ErpStockAlertMapper.class, "selectAlerts", params).getSql();
        assertFalse(full.toLowerCase().contains("limit"));
        assertFalse(full.contains("alertRank"));
        assertTrue(full.contains("order by abs(a.gap) desc, a.productId asc"));
        params.put("alertType", "HIGH");
        assertTrue(render(ErpStockAlertMapper.class, "selectAlerts", params).getSql().contains("a.alertType = ?"));
    }

    @Test
    void openingPage_projectsBothNamesAndUsesIndependentLimit_andQuantitiesAggregateAllWarehouses() throws Exception {
        Map<String, Object> params = Map.of("warehouseId", 3L, "offset", 0L, "size", 5001);
        String sql = render(StOpeningMapper.class, "selectDetails", params).getSql();
        assertTrue(sql.contains("p.name as productName"));
        assertTrue(sql.contains("w.name as warehouseName"));
        assertTrue(sql.contains("o.warehouse_id = ?"));
        assertTrue(sql.contains("order by o.product_id, o.warehouse_id, o.id limit ? offset ?"));
        String scoped = tenantSql(sql);
        for (String alias : List.of("o", "p", "w")) {
            assertTrue(scoped.contains(alias + ".tenant_id = 'TENANT_A'"), scoped);
        }
        String quantities = render(ErpStockMapper.class, "selectQuantityTotals", Map.of("ids", List.of(1L, 2L))).getSql();
        assertTrue(quantities.contains("sum(qty) as qty"));
        assertFalse(quantities.contains("warehouse_id ="));
        assertTrue(tenantSql(quantities).contains("tenant_id = 'TENANT_A'"));
    }

    private BoundSql render(Class<?> mapper, String name, Map<String, ?> params) throws Exception {
        var method = java.util.Arrays.stream(mapper.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        String script = String.join(" ", method.getAnnotation(Select.class).value());
        BoundSql bound = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class).getBoundSql(params);
        // 标准化动态 XML 换行，方便断言语义片段。
        return new BoundSql(new Configuration(), bound.getSql().replaceAll("\\s+", " ").strip(), bound.getParameterMappings(), params);
    }

    private String tenantSql(String sql) {
        return new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new StringValue("TENANT_A"); }
        }).parserSingle(sql, null);
    }
}
