import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.LocalCacheScope;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.tenant.handle.PlusTenantLineHandler;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.stock.domain.ErpStockLog;
import org.erp.stock.domain.vo.ErpStockLogVo;
import org.erp.stock.mapper.ErpStockLogMapper;
import org.erp.stock.mapper.ErpStockMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounded real Mapper queries, not the inventory service, HTTP or capacity acceptance. */
public final class StockQueryMySql {
    private static final String A = "928101", B = "928102";
    private static final long PRODUCT = 88001, WAREHOUSE = 77001;
    private static final LocalDate START = LocalDate.of(2026, 11, 1), END = LocalDate.of(2026, 11, 30);
    private record Row(long id, String tenant, long product, long warehouse, LocalDate date, String deleted, int seq) {}
    private final StockQueryDatabase db;
    private final StockQueryCapture capture;
    private final ErpStockLogMapper logs;
    private final ErpStockMapper stock;
    private final TransactionTemplate transaction;
    private List<Row> seeded;

    private StockQueryMySql(StockQueryDatabase db, StockQueryCapture capture) throws Exception {
        this.db = db; this.capture = capture;
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true); config.setLogImpl(NoLoggingImpl.class);
        config.setDefaultStatementTimeout(8); config.setCacheEnabled(false); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var plus = new MybatisPlusInterceptor();
        plus.addInnerInterceptor(new TenantLineInnerInterceptor(new PlusTenantLineHandler()));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config); factory.setGlobalConfig(GlobalConfigUtils.defaults().setBanner(false));
        factory.setDataSource(db.dataSource()); factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setPlugins(plus, capture);
        var sessions = Objects.requireNonNull(factory.getObject());
        config.addMapper(ErpStockLogMapper.class); config.addMapper(ErpStockMapper.class);
        var session = new SqlSessionTemplate(sessions);
        logs = session.getMapper(ErpStockLogMapper.class); stock = session.getMapper(ErpStockMapper.class);
        var manager = new DataSourceTransactionManager(db.dataSource());
        manager.setDefaultTimeout(18);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private void seed(int count) throws Exception {
        check(count <= 10000 && count * 2 <= 40000, "fixture bound exceeded");
        for (String table : StockQueryDatabase.TABLES) db.assertOwned(table);
        seeded = new ArrayList<>();
        for (String tenant : List.of(A, B)) for (int i = 1; i <= count; i++) {
            LocalDate date = switch (i % 10) {
                case 0 -> START.minusDays(1); case 1 -> END.plusDays(1); case 2, 3 -> START;
                case 4, 5 -> END; default -> START.plusDays(9 + i % 4);
            };
            seeded.add(new Row((tenant.equals(A) ? 1000000L : 2000000L) + i, tenant,
                i % 7 == 0 ? PRODUCT + 1 : PRODUCT, i % 3 == 0 ? WAREHOUSE + 1 : WAREHOUSE,
                date, i % 17 == 0 ? "1" : "0", i));
        }
        try (Connection c = db.dataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement sql = c.createStatement()) {
                    sql.setQueryTimeout(8); sql.executeUpdate("DELETE FROM st_stock_log"); sql.executeUpdate("DELETE FROM st_stock");
                }
                try (PreparedStatement sql = c.prepareStatement("INSERT INTO st_stock(id,tenant_id,product_id,warehouse_id) VALUES(?,?,?,?)")) {
                    long id = 5000000;
                    for (String tenant : List.of(A, B)) {
                        sql.setLong(1, ++id); sql.setString(2, tenant); sql.setLong(3, PRODUCT); sql.setLong(4, WAREHOUSE);
                        sql.addBatch();
                    }
                    sql.setQueryTimeout(8); sql.executeBatch();
                }
                for (int from = 0; from < seeded.size(); from += 250) {
                    int until = Math.min(from + 250, seeded.size());
                    String values = String.join(",", java.util.Collections.nCopies(until - from, "(?,?,?,?,?,?,?,?,?,1,2,2)"));
                    try (PreparedStatement sql = c.prepareStatement("INSERT INTO st_stock_log(id,tenant_id,product_id,warehouse_id,biz_date,del_flag,seq,biz_type,biz_no,qty,unit_cost,amount) VALUES" + values)) {
                        int p = 1;
                        for (Row row : seeded.subList(from, until)) {
                            sql.setLong(p++, row.id); sql.setString(p++, row.tenant); sql.setLong(p++, row.product);
                            sql.setLong(p++, row.warehouse); sql.setDate(p++, Date.valueOf(row.date)); sql.setString(p++, row.deleted);
                            sql.setInt(p++, row.seq); sql.setString(p++, row.seq % 11 == 0 ? "VOID" : "PURCHASE");
                            sql.setString(p++, "SQ-SHARED-BILL-" + row.seq % 16);
                        }
                        sql.setQueryTimeout(8); sql.executeUpdate();
                    }
                }
                c.commit();
            } catch (Throwable error) { c.rollback(); throw error; }
        }
    }

    private <T> T as(String tenant, Supplier<T> action) {
        return TenantHelper.dynamic(tenant, action);
    }
    private List<Row> expected(String tenant, LocalDate start, LocalDate end) {
        return seeded.stream().filter(r -> r.tenant.equals(tenant) && r.product == PRODUCT && r.deleted.equals("0")
            && !r.date.isBefore(start) && !r.date.isAfter(end))
            .sorted(Comparator.comparing(Row::date).thenComparingLong(Row::id).reversed()).toList();
    }
    private Map<String, Object> pages(String scene, String tenant, LocalDate start, LocalDate end, int fetch) throws Exception {
        List<Long> wanted = expected(tenant, start, end).stream().map(Row::id).toList();
        var actual = new ArrayList<Long>();
        LocalDate before = null; Long beforeId = null;
        int pages = 0, maxRows = 0;
        while (true) {
            LocalDate date = before; Long id = beforeId;
            List<ErpStockLogVo> page = as(tenant, () -> capture.call(scene, tenant, "ErpStockLogMapper.selectLogPage", false, fetch,
                () -> logs.selectLogPage(PRODUCT, start, end, date, id, fetch)));
            pages++; maxRows = Math.max(maxRows, page.size());
            check(pages <= seeded.size() / fetch + 3, "pagination did not terminate");
            for (ErpStockLogVo row : page) actual.add(row.getId());
            if (page.size() < fetch) break;
            ErpStockLogVo last = page.get(page.size() - 1); before = last.getBizDate(); beforeId = last.getId();
        }
        check(wanted.equals(actual), "cursor returned wrong order/missing/duplicate/foreign/soft-deleted/out-of-window row");
        check(actual.stream().distinct().count() == actual.size(), "duplicate across pages");
        var result = new LinkedHashMap<String, Object>();
        result.put("tenant", tenant); result.put("window_start", start.toString()); result.put("window_end", end.toString());
        result.put("fetch_size", fetch); result.put("page_calls_including_terminal", pages); result.put("max_returned_per_call", maxRows);
        result.put("expected_rows", wanted.size()); result.put("actual_rows", actual.size()); result.put("ordered_ids_sha256", StockQueryDatabase.sha(actual.toString()));
        result.put("exact_order_no_duplicates_no_omissions", true);
        return result;
    }

    private Map<String, Object> replay(String scene, String tenant) {
        List<Long> wanted = seeded.stream().filter(r -> r.tenant.equals(tenant) && r.product == PRODUCT
            && r.warehouse == WAREHOUSE && r.deleted.equals("0")).map(Row::id).sorted().toList();
        int first = capture.queries.size();
        List<ErpStockLog> rows = as(tenant, () -> transaction.execute(status -> {
            var locked = capture.call(scene, tenant, "ErpStockMapper.selectForUpdate", true, 1,
                () -> stock.selectForUpdate(PRODUCT, WAREHOUSE));
            check(locked != null && tenant.equals(locked.getTenantId()), "stock lock wrong tenant");
            return capture.call(scene, tenant, "ErpStockLogMapper.selectReplayRowsForUpdate", true, -1,
                () -> logs.selectReplayRowsForUpdate(PRODUCT, WAREHOUSE));
        }));
        check(wanted.equals(rows.stream().map(ErpStockLog::getId).toList()), "replay returned foreign/deleted/missing/order-invalid row");
        check(capture.queries.size() == first + 2, "stock lock/replay query missing");
        Object connection = capture.queries.get(first).get("mysql_connection_id");
        check(connection.equals(capture.queries.get(first + 1).get("mysql_connection_id")), "transaction split between lock/replay/EXPLAIN");
        return Map.of("tenant", tenant, "expected_rows", wanted.size(), "actual_rows", rows.size(),
            "stock_lock_replay_and_explain_same_transaction_connection", true,
            "row_bound_note", "Replay has NO production LIMIT; only fixture cardinality is bounded, not a capacity claim.");
    }

    private Map<String, Object> missingTenant(String scene) {
        check(TenantHelper.getTenantId() == null, "tenant context leaked");
        int prepares = capture.handlerPrepares, jdbc = capture.jdbcPrepares, calls = capture.queries.size();
        int refused = 0;
        for (String operation : List.of("page", "replay")) {
            try {
                capture.call(scene, null, operation, false, -1, () -> operation.equals("page")
                    ? logs.selectLogPage(PRODUCT, START, END, null, null, 10)
                    : logs.selectReplayRowsForUpdate(PRODUCT, WAREHOUSE));
                throw new AssertionError("missing tenant query unexpectedly succeeded");
            } catch (RuntimeException error) {
                Throwable root = error; while (root.getCause() != null) root = root.getCause();
                check(root instanceof IllegalStateException && root.getMessage().contains("缺少租户上下文"), "not production fail-closed tenant rejection");
                refused++;
            }
        }
        check(prepares == capture.handlerPrepares && jdbc == capture.jdbcPrepares && calls == capture.queries.size(), "tenantless SQL reached prepare/query");
        return Map.of("rejected_mapper_calls", refused, "statement_handler_prepare_delta", capture.handlerPrepares - prepares,
            "jdbc_prepare_delta", capture.jdbcPrepares - jdbc, "failed_before_business_sql", true);
    }

    private void scene(int count, Map<String, Object> scene) throws Exception {
        scene.put("status", "failed"); scene.put("rows_per_tenant", count); scene.put("total_stock_log_rows", count * 2);
        long start = System.nanoTime(); seed(count); scene.put("fixture_seed_wall_ms", StockQueryCapture.millis(start));
        start = System.nanoTime();
        var pagination = new ArrayList<Map<String, Object>>(); scene.put("pagination", pagination);
        var replays = new ArrayList<Map<String, Object>>(); scene.put("locked_replay", replays);
        String name = "rows-per-tenant-" + count;
        for (String tenant : List.of(A, B)) {
            pagination.add(pages(name, tenant, START, END, 137));
            pagination.add(pages(name + "-same-day", tenant, END, END, 137));
            pagination.add(pages(name + "-empty-window", tenant, END.plusYears(1), END.plusYears(1), 137));
            List<ErpStockLogVo> one = as(tenant, () -> capture.call(name, tenant, "selectLogPage-limit-1", false, 1,
                () -> logs.selectLogPage(PRODUCT, START, END, null, null, 1)));
            check(one.size() == 1 && one.get(0).getId().equals(expected(tenant, START, END).get(0).id), "LIMIT 1 result mismatch");
            replays.add(replay(name, tenant));
        }
        scene.put("missing_tenant", missingTenant(name));
        scene.put("query_scene_wall_ms_including_explain_probes", StockQueryCapture.millis(start));
        scene.put("correctness", "Exact synthetic expected IDs: tenant isolation, soft-delete, inclusive dates, tie-date composite cursor and no duplicates/omissions.");
        scene.put("status", "passed");
    }

    private static GenericApplicationContext tenantSpring() {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("stock-query-audit", Map.of("tenant.enable", "true")));
        var context = new GenericApplicationContext(); context.setEnvironment(env);
        context.registerBean(SpringUtils.class); context.refresh(); return context;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected result path and extracted DDL manifest");
        var json = new ObjectMapper();
        JsonNode definitions = json.readTree(Path.of(args[1]).toFile());
        if (args[0].equals("--self-test")) {
            StockQueryDatabase.selfTest(definitions);
            System.out.println("PASS: Java original-DDL/canonicalization mutation self-tests; no JDBC connection"); return;
        }
        var report = new LinkedHashMap<String, Object>(); var database = new LinkedHashMap<String, Object>();
        var scenes = new ArrayList<Map<String, Object>>(); var capture = new StockQueryCapture();
        report.put("passed", false); report.put("database", database); report.put("scenes", scenes); report.put("queries", capture.queries);
        report.put("timing_scope", "One local sample per real Mapper query; JDBC execute+mapping only, EXPLAIN separate. Not p95/capacity/HTTP end-to-end.");
        report.put("fixture_shape", "Two synthetic tenants share product/warehouse dimension values and business codes; globally unique log and stock primary keys. No master/HTTP fixtures.");
        RefundAuditDatabase.Contract contract = null;
        try {
            contract = RefundAuditDatabase.Contract.fromExec();
            ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("ROOT").setLevel(Level.ERROR);
            SaManager.setConfig(new SaTokenConfig().setIsPrint(false).setIsLog(false).setDataRefreshPeriod(-1));
            try (var spring = tenantSpring(); var db = new StockQueryDatabase(contract, database)) {
                db.prepare(definitions); var test = new StockQueryMySql(db, capture);
                for (int count : List.of(1000, 10000)) { var scene = new LinkedHashMap<String, Object>(); scenes.add(scene); test.scene(count, scene); }
            }
            report.put("passed", scenes.size() == 2 && scenes.stream().allMatch(s -> "passed".equals(s.get("status")))
                && Boolean.TRUE.equals(database.get("cleanup_verified")));
        } catch (Throwable error) {
            report.put("error_class", error.getClass().getSimpleName());
            if (contract != null && error.getMessage() != null) report.put("error", contract.redact(error.getMessage()));
        }
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
        System.out.println("STOCK_QUERY: " + (Boolean.TRUE.equals(report.get("passed")) ? "PASS" : "FAIL"));
        System.exit(Boolean.TRUE.equals(report.get("passed")) ? 0 : 1);
    }
    static void check(boolean value, String message) { StockQueryDatabase.check(value, message); }
}
