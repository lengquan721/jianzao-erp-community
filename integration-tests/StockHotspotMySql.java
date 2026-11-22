import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaRequestForMock;
import cn.dev33.satoken.context.mock.SaResponseForMock;
import cn.dev33.satoken.context.mock.SaStorageForMock;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.mybatis.handler.InjectionMetaObjectHandler;
import org.dromara.common.tenant.handle.PlusTenantLineHandler;
import org.dromara.common.tenant.helper.TenantHelper;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.stock.mapper.ErpStockLogMapper;
import org.erp.stock.mapper.ErpStockMapper;
import org.erp.stock.service.IInventoryService;
import org.erp.stock.service.impl.InventoryServiceImpl;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** P04 bounded real application-service acceptance, not HTTP, production load or an SLO benchmark. */
public final class StockHotspotMySql {
    private static final String A = StockHotspotFixture.A, B = StockHotspotFixture.B;
    private static final long PRODUCT = StockHotspotFixture.PRODUCT, WAREHOUSE = StockHotspotFixture.WAREHOUSE;
    private static final Date DAY = Date.valueOf("2026-11-20");
    private final StockHotspotDatabase db;
    private final JdbcTemplate sql, observer;
    private final IInventoryService inventory;
    private final TransactionTemplate outer;
    private final StockHotspotCapture capture;
    private final SaTokenContextForThreadLocal identity;

    private StockHotspotMySql(StockHotspotDatabase db, StockHotspotCapture capture, SaTokenContextForThreadLocal identity) throws Exception {
        this.db = db; this.capture = capture; this.identity = identity;
        sql = new JdbcTemplate(db.dataSource()); sql.setQueryTimeout(12); observer = db.observer();
        var manager = new DataSourceTransactionManager(db.dataSource()); manager.setDefaultTimeout(24);
        outer = new TransactionTemplate(manager); // Default isolation: NEVER force READ_COMMITTED to make a race pass.
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(NoLoggingImpl.class); config.setDefaultStatementTimeout(12);
        var plus = new MybatisPlusInterceptor(); plus.addInnerInterceptor(new TenantLineInnerInterceptor(new PlusTenantLineHandler()));
        var factory = new MybatisSqlSessionFactoryBean(); factory.setConfiguration(config);
        factory.setGlobalConfig(GlobalConfigUtils.defaults().setBanner(false)
            .setIdentifierGenerator(new DefaultIdentifierGenerator(1, 3)).setMetaObjectHandler(new InjectionMetaObjectHandler()));
        factory.setDataSource(db.dataSource()); factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setPlugins(plus, capture);
        var sessionFactory = Objects.requireNonNull(factory.getObject());
        for (Class<?> mapper : List.of(ErpStockMapper.class, ErpStockLogMapper.class, ErpProductMapper.class,
            ErpSupplierMapper.class, ErpCustomerMapper.class)) config.addMapper(mapper);
        var session = new SqlSessionTemplate(sessionFactory);
        var target = new InventoryServiceImpl(session.getMapper(ErpStockMapper.class), session.getMapper(ErpStockLogMapper.class),
            session.getMapper(ErpProductMapper.class), session.getMapper(ErpSupplierMapper.class), session.getMapper(ErpCustomerMapper.class));
        var advice = new TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); advice.afterPropertiesSet();
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(advice);
        inventory = (IInventoryService) proxy.getProxy();
        check(AopUtils.isCglibProxy(inventory) && AopUtils.getTargetClass(inventory) == InventoryServiceImpl.class, "wrong real-service proxy");
    }
    private <T> T as(String tenant, Supplier<T> call) {
        check(!identity.isValid(), "thread identity leaked");
        identity.setContext(new SaRequestForMock(), new SaResponseForMock(), new SaStorageForMock());
        try { return TenantHelper.dynamic(tenant, call); } finally { identity.clearContext(); }
    }
    private void reset() throws Exception {
        db.assertAllOwned();
        try (Connection c = db.dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(12); s.executeUpdate("DELETE FROM st_stock_log"); s.executeUpdate("DELETE FROM st_stock");
                s.executeUpdate("DELETE FROM md_product"); c.commit();
            } catch (Throwable error) { c.rollback(); throw error; }
        }
    }
    private void seedHistory(int count) throws Exception {
        var a = StockHotspotFixture.rows(A, count); var b = StockHotspotFixture.rows(B, count);
        check(a.size() + b.size() <= StockHotspotFixture.MAX_TOTAL, "total row cap exceeded"); reset();
        try (Connection c = db.dataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO st_stock(id,tenant_id,product_id,warehouse_id,qty,avg_cost) VALUES(?,?,?,?,?,99)")) {
                    for (String tenant : List.of(A, B)) {
                        p.setLong(1, tenant.equals(A) ? 5000001 : 5000002); p.setString(2, tenant); p.setLong(3, PRODUCT);
                        p.setLong(4, WAREHOUSE); p.setBigDecimal(5, StockHotspotFixture.quantity(count)); p.addBatch();
                    }
                    p.setQueryTimeout(12); p.executeBatch();
                }
                var rows = new ArrayList<>(a); rows.addAll(b);
                for (int from = 0; from < rows.size(); from += 250) {
                    var batch = rows.subList(from, Math.min(from + 250, rows.size()));
                    String values = String.join(",", Collections.nCopies(batch.size(), "(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"));
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO st_stock_log(id,tenant_id,biz_type,biz_no,biz_id,product_id,warehouse_id,qty,unit_cost,amount,balance_after,biz_date,seq,del_flag) VALUES" + values)) {
                        int i = 1;
                        for (var row : batch) {
                            p.setLong(i++, row.id()); p.setString(i++, row.tenant()); p.setString(i++, row.type());
                            p.setString(i++, "SH-" + row.id()); p.setLong(i++, row.id()); p.setLong(i++, PRODUCT); p.setLong(i++, WAREHOUSE);
                            p.setInt(i++, row.qty()); p.setInt(i++, row.cost()); p.setLong(i++, (long) row.qty() * row.cost());
                            p.setInt(i++, row.balance()); p.setDate(i++, DAY); p.setInt(i++, 0); p.setString(i++, "0");
                        }
                        p.setQueryTimeout(12); p.executeUpdate();
                    }
                }
                c.commit();
            } catch (Throwable error) { c.rollback(); throw error; }
        }
    }
    private Map<String, Object> stock(JdbcTemplate jdbc, String tenant) {
        return jdbc.queryForMap("SELECT qty,avg_cost FROM st_stock WHERE tenant_id=? AND product_id=? AND warehouse_id=? AND del_flag='0'", tenant, PRODUCT, WAREHOUSE);
    }
    private void assertStock(JdbcTemplate jdbc, String tenant, BigDecimal qty, BigDecimal cost) {
        var actual = stock(jdbc, tenant); decimal(qty, actual.get("qty"), "qty"); decimal(cost, actual.get("avg_cost"), "avg_cost");
    }
    private String historyHash() throws Exception {
        return StockQueryDatabase.sha(observer.queryForList("SELECT * FROM st_stock_log ORDER BY id").toString());
    }
    private void replay(int count, Map<String, Object> evidence) throws Exception {
        evidence.put("rows_per_tenant", count); evidence.put("total_log_rows", count * 2); seedHistory(count);
        check(scalar(observer, "SELECT COUNT(*) FROM st_stock_log") == count * 2L, "seed count mismatch");
        String logsBefore = historyHash();
        var tenants = new ArrayList<Map<String, Object>>(); evidence.put("tenants", tenants);
        for (String tenant : List.of(A, B)) {
            String other = tenant.equals(A) ? B : A; var foreignBefore = stock(observer, other);
            var expected = StockHotspotFixture.rows(tenant, count);
            var samples = new ArrayList<Double>();
            for (int i = 0; i < 6; i++) {
                boolean warmup = i == 0; long start = System.nanoTime();
                as(tenant, () -> { capture.call(tenant, expected, warmup, () -> inventory.replayAvgCost(PRODUCT, WAREHOUSE)); return null; });
                if (!warmup) samples.add((System.nanoTime() - start) / 1000000.0);
                assertStock(observer, tenant, StockHotspotFixture.quantity(count), StockHotspotFixture.average(tenant));
                check(foreignBefore.equals(stock(observer, other)), "replay modified another tenant stock");
            }
            check(logsBefore.equals(historyHash()), "replay modified/removed/added a history row");
            var sorted = new ArrayList<>(samples); sorted.sort(Double::compareTo);
            tenants.add(Map.of("tenant", tenant, "expected_qty", StockHotspotFixture.quantity(count),
                "expected_avg_cost", StockHotspotFixture.average(tenant), "actual_stock", stock(observer, tenant),
                "warmup_calls", 1, "measured_calls", samples.size(), "service_wall_samples_ms", samples,
                "p50_ms_nearest_rank", sorted.get(2), "p95_ms_nearest_rank", sorted.get(4),
                "other_tenant_and_all_history_unchanged", true));
        }
        evidence.put("history_before_after_sha256", logsBefore);
        evidence.put("oracle", "All first n-1 receipts: qty=1 at A=2/B=17; final receipt qty=n-1 at A=6/B=25. Closed form qty=2(n-1), average=A4/B21, distinct last-row cost detects truncation. Snapshot qty is checked, not repaired by replayAvgCost.");
    }
    private Map<String, Object> snapshot() {
        return Map.of("stock", observer.queryForList("SELECT * FROM st_stock ORDER BY id"),
            "logs", observer.queryForList("SELECT * FROM st_stock_log ORDER BY id"));
    }
    private void inbound(String no, int qty, int cost) {
        inventory.inbound("PURCHASE", no, no.equals("SH-W1") ? 71001L : 71002L,
            PRODUCT, WAREHOUSE, BigDecimal.valueOf(qty), BigDecimal.valueOf(cost), "NONE", null, DAY, 0);
    }
    private void seedRace() throws Exception {
        reset();
        sql.update("INSERT INTO md_product(id,tenant_id,code,name,qty_scale) VALUES(?,?,'SH-P','Synthetic hotspot product',0)", PRODUCT, A);
        for (String tenant : List.of(A, B)) {
            boolean a = tenant.equals(A); int qty = a ? 10 : 19, cost = a ? 6 : 17;
            sql.update("INSERT INTO st_stock(id,tenant_id,product_id,warehouse_id,qty,avg_cost) VALUES(?,?,?,?,?,?)",
                a ? 5000001 : 5000002, tenant, PRODUCT, WAREHOUSE, qty, cost);
            sql.update("INSERT INTO st_stock_log(id,tenant_id,biz_type,biz_no,product_id,warehouse_id,qty,unit_cost,amount,balance_after,biz_date) VALUES(?,?,'OPENING','SH-OPEN',?,?,?,?,?,?,?)",
                a ? 1000001 : 2000001, tenant, PRODUCT, WAREHOUSE, qty, cost, qty * cost, qty, DAY);
        }
    }
    private void race(Map<String, Object> evidence) throws Exception {
        seedRace(); var before = snapshot(); var otherBefore = stock(observer, B);
        CountDownLatch w1HasWritten = new CountDownLatch(1), w2HasConnection = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicLong w1id = new AtomicLong(), w2id = new AtomicLong(); long observerId = scalar(observer, "SELECT CONNECTION_ID()");
        var isolations = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var samples = new ArrayList<Map<String, Object>>();
        evidence.put("observed_own_w2_wait_samples", samples);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> w1 = pool.submit(() -> as(A, () -> outer.execute(status -> {
                check(TransactionSynchronizationManager.isActualTransactionActive(), "W1 outer transaction absent");
                sql.execute("SET SESSION innodb_lock_wait_timeout=10");
                w1id.set(scalar(sql, "SELECT CONNECTION_ID()"));
                isolations.put("w1", Objects.requireNonNull(sql.queryForObject("SELECT @@transaction_isolation", String.class)));
                inbound("SH-W1", 5, 10); // Real service writes; outer Spring transaction retains its InnoDB row lock.
                assertStock(sql, A, new BigDecimal("15.000"), new BigDecimal("7.3333"));
                w1HasWritten.countDown(); await(release, 15, "W1 not released"); return null;
            })));
            if (!w1HasWritten.await(7, TimeUnit.SECONDS)) {
                if (w1.isDone()) w1.get(); throw new IllegalStateException("W1 did not reach real pre-commit latch");
            }
            Future<?> w2 = pool.submit(() -> as(A, () -> outer.execute(status -> {
                sql.execute("SET SESSION innodb_lock_wait_timeout=10");
                w2id.set(scalar(sql, "SELECT CONNECTION_ID()"));
                isolations.put("w2", Objects.requireNonNull(sql.queryForObject("SELECT @@transaction_isolation", String.class)));
                w2HasConnection.countDown(); inbound("SH-W2", 3, 20); return null;
            })));
            if (!w2HasConnection.await(7, TimeUnit.SECONDS)) {
                if (w2.isDone()) w2.get(); throw new IllegalStateException("W2 has no own bound transaction connection");
            }
            check(w1id.get() > 0 && w2id.get() > 0 && w1id.get() != w2id.get()
                && observerId != w1id.get() && observerId != w2id.get(), "three distinct physical connections required");
            evidence.put("mysql_connection_ids", Map.of("w1", w1id.get(), "w2", w2id.get(), "observer", observerId));
            evidence.put("actual_transaction_isolation_no_override", isolations);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            for (int poll = 0; System.nanoTime() < deadline && poll < 80 && samples.size() < 2 && !w2.isDone(); poll++) {
                Map<String, Object> sample = ownWaitingSelect(w2id.get());
                if (sample != null) samples.add(sample);
                if (samples.size() < 2) Thread.sleep(50); // Throttle actual SHOW polling; elapsed time alone is NEVER evidence.
            }
            check(samples.size() >= 2 && !w2.isDone() && !w1.isDone() && release.getCount() == 1,
                "NOT PASSED: unable to observe own W2 stock SELECT FOR UPDATE blocked behind held W1 key");
            check(before.equals(snapshot()), "independent observer saw uncommitted stock/log writes");
            evidence.put("precommit_invisible_on_independent_autocommit_observer", true);
            evidence.put("wait_evidence_basis", "Two own-ID SHOW FULL PROCESSLIST samples of the exact same-key/tenant production SELECT FOR UPDATE while W1's completed inbound retains its conflicting lock and both futures remain incomplete. Not a timer-only race or a data_lock_waits privilege claim.");
            release.countDown(); w1.get(24, TimeUnit.SECONDS); w2.get(24, TimeUnit.SECONDS);
            evidence.put("both_real_transactions_committed_without_timeout_or_deadlock", true);
            // Persist actuals before asserting; an RR/stale-read defect must be a visible FAILED case, not hidden by RC.
            evidence.put("actual_final_stock", stock(observer, A));
            evidence.put("actual_a_logs", observer.queryForList("SELECT biz_no,qty,unit_cost,amount,balance_after FROM st_stock_log WHERE tenant_id=? ORDER BY id", A));
            evidence.put("serial_expected", Map.of("starting_qty", "10.000", "starting_avg_cost", "6.0000",
                "w1_qty_cost_balance", List.of("5.000", "10.0000", "15.000"),
                "w2_qty_cost_balance", List.of("3.000", "20.0000", "18.000"), "final_qty", "18.000", "final_avg_cost", "9.4444"));
            assertStock(observer, A, new BigDecimal("18.000"), new BigDecimal("9.4444"));
            for (String no : List.of("SH-W1", "SH-W2")) {
                var logs = observer.queryForList("SELECT biz_type,biz_id,qty,unit_cost,amount,balance_after,product_id,warehouse_id,del_flag FROM st_stock_log WHERE tenant_id=? AND biz_no=?", A, no);
                check(logs.size() == 1, "each real inbound must write exactly one log"); var row = logs.get(0); boolean first = no.equals("SH-W1");
                check("PURCHASE".equals(row.get("biz_type")) && "0".equals(row.get("del_flag")), "wrong log type/delete flag");
                check(((Number) row.get("biz_id")).longValue() == (first ? 71001 : 71002)
                    && ((Number) row.get("product_id")).longValue() == PRODUCT && ((Number) row.get("warehouse_id")).longValue() == WAREHOUSE,
                    "inbound wrote another business/dimension key");
                decimal(new BigDecimal(first ? "5.000" : "3.000"), row.get("qty"), "inbound qty");
                decimal(new BigDecimal(first ? "10.0000" : "20.0000"), row.get("unit_cost"), "inbound cost");
                decimal(new BigDecimal(first ? "50.00" : "60.00"), row.get("amount"), "inbound amount");
                decimal(new BigDecimal(first ? "15.000" : "18.000"), row.get("balance_after"), "serial balance_after");
            }
            check(scalar(observer, "SELECT COUNT(*) FROM st_stock_log") == 4, "expected two openings plus exactly two new logs");
            check(scalar(observer, "SELECT COUNT(*) FROM st_stock_log WHERE tenant_id=?", B) == 1
                && otherBefore.equals(stock(observer, B)), "foreign tenant fixture changed during race");
            evidence.put("exact_once_balances_and_foreign_tenant_unchanged", true);
        } finally {
            release.countDown(); pool.shutdown();
            boolean stopped = pool.awaitTermination(26, TimeUnit.SECONDS);
            if (!stopped) { pool.shutdownNow(); stopped = pool.awaitTermination(20, TimeUnit.SECONDS); }
            evidence.put("workers_terminated_before_fixture_cleanup", stopped);
            check(stopped, "workers remain active; race/cleanup cannot be accepted");
        }
    }
    /** Only read Info/State AFTER matching our captured W2 connection ID; never retain other process rows. */
    private Map<String, Object> ownWaitingSelect(long ownW2Id) {
        check(ownW2Id > 0, "unbound contender id");
        return observer.query("SHOW FULL PROCESSLIST", rows -> {
            while (rows.next()) {
                if (rows.getLong("Id") != ownW2Id) continue;
                if (!"Query".equals(rows.getString("Command"))) return null;
                String ownSql = rows.getString("Info");
                if (!stockLockShape(ownSql)) return null;
                // No SQL, username, host, schema or unrelated process information is emitted.
                return Map.<String, Object>of("own_connection_id", ownW2Id, "same_key_tenant_select_for_update", true,
                    "own_state", Objects.toString(rows.getString("State"), ""), "statement_sha256", shaUnchecked(ownSql));
            }
            return null;
        });
    }
    static boolean stockLockShape(String query) {
        if (query == null) return false;
        String q = query.toLowerCase(Locale.ROOT).replace("`", "").replaceAll("\\s+", " ").strip();
        return q.matches("select .+ from st_stock where .+ for update")
            && q.contains("tenant_id = '" + A + "'") && !q.contains("st_stock_log") && !q.contains(" limit ")
            && q.matches(".*\\bproduct_id\\s*=\\s*" + PRODUCT + "\\b.*")
            && q.matches(".*\\bwarehouse_id\\s*=\\s*" + WAREHOUSE + "\\b.*");
    }
    static void lockShapeSelfTest() {
        String valid = "SELECT * FROM st_stock WHERE product_id = " + PRODUCT + " AND warehouse_id = " + WAREHOUSE
            + " AND del_flag = '0' AND tenant_id = '" + A + "' FOR UPDATE";
        check(stockLockShape(valid), "own lock shape not recognized");
        check(!stockLockShape(valid.replace("FOR UPDATE", "")) && !stockLockShape(valid.replace(A, B))
            && !stockLockShape(valid.replace("" + WAREHOUSE, "77092")) && !stockLockShape(valid.replace("st_stock", "st_stock_log"))
            && !stockLockShape(null) && !stockLockShape("SELECT SLEEP(2)"), "non-own/non-lock query incorrectly accepted");
    }
    private static String shaUnchecked(String text) {
        try { return StockQueryDatabase.sha(text); } catch (Exception error) { throw new IllegalStateException("SHA256 unavailable"); }
    }
    private static long scalar(JdbcTemplate jdbc, String query, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(query, Long.class, args));
    }
    private static void decimal(BigDecimal expected, Object actual, String label) {
        check(actual instanceof BigDecimal && expected.compareTo((BigDecimal) actual) == 0, label + " mismatch: expected " + expected + ", actual " + actual);
    }
    private static void await(CountDownLatch latch, int seconds, String label) {
        try { check(latch.await(seconds, TimeUnit.SECONDS), label); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(label); }
    }
    private static GenericApplicationContext tenantSpring() {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("stock-hotspot-audit", Map.of("tenant.enable", "true")));
        var context = new GenericApplicationContext(); context.setEnvironment(env);
        context.registerBean(SpringUtils.class); context.refresh(); return context;
    }
    @FunctionalInterface private interface Case { void run(Map<String, Object> evidence) throws Exception; }
    private static void caseRun(Map<String, Object> cases, String name, Case action, RefundAuditDatabase.Contract contract) {
        var evidence = new LinkedHashMap<String, Object>(); cases.put(name, evidence); evidence.put("status", "failed");
        try { action.run(evidence); evidence.put("status", "passed"); }
        catch (Throwable error) {
            evidence.put("error_class", error.getClass().getSimpleName());
            if (error.getMessage() != null) evidence.put("error", contract.redact(error.getMessage()));
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected result path and original-DDL manifest");
        var json = new ObjectMapper(); var definitions = json.readTree(Path.of(args[1]).toFile());
        if (args[0].equals("--self-test")) {
            StockHotspotDatabase.selfTest(definitions);
            System.out.println("PASS: hotspot Java fixture/DDL/lock-shape offline mutations; no JDBC connection"); return;
        }
        var report = new LinkedHashMap<String, Object>(); var database = new LinkedHashMap<String, Object>();
        var cases = new LinkedHashMap<String, Object>(); var capture = new StockHotspotCapture();
        report.put("passed", false); report.put("database", database); report.put("cases", cases); report.put("real_replay_queries", capture.queries);
        report.put("scope", "P04 bounded real InventoryServiceImpl/CGLIB annotation transactions/MyBatis tenant interceptor/MySQL8; synthetic legal receipts. No HTTP/Redis/production capacity, no business source fixes, no replacement of stock-query Mapper evidence.");
        report.put("timing_scope", "One warmup then five samples per tenant/cardinality; nearest-rank P50/P95. Local service+transaction+observational ID validation wall time only, JVM/compiler/seed excluded. Small n, NOT an SLO, load benchmark, production percentile or acceptance threshold.");
        RefundAuditDatabase.Contract contract = null;
        try {
            contract = RefundAuditDatabase.Contract.fromExec();
            StockHotspotDatabase.selfTest(definitions);
            ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("ROOT").setLevel(Level.ERROR);
            SaManager.setConfig(new SaTokenConfig().setIsPrint(false).setIsLog(false).setDataRefreshPeriod(-1));
            var identity = new SaTokenContextForThreadLocal(); SaManager.setSaTokenContext(identity);
            try (var spring = tenantSpring(); var db = new StockHotspotDatabase(contract, database)) {
                db.prepare(definitions); var test = new StockHotspotMySql(db, capture, identity);
                for (int count : List.of(1000, 10000)) caseRun(cases, "real_replay_" + count + "_per_tenant", row -> test.replay(count, row), contract);
                caseRun(cases, "two_real_inbounds_same_stock_key", test::race, contract);
            }
            report.put("passed", cases.size() == 3 && cases.values().stream().allMatch(v -> "passed".equals(((Map<?, ?>) v).get("status")))
                && Boolean.TRUE.equals(database.get("cleanup_verified")));
        } catch (Throwable error) {
            report.put("setup_error_class", error.getClass().getSimpleName());
            if (contract != null && error.getMessage() != null) report.put("setup_error", contract.redact(error.getMessage()));
        }
        // Including all errors: never emit raw exception stacks/SQL/process-list rows/credentials.
        String serialized = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        java.nio.file.Files.writeString(Path.of(args[0]), (contract == null ? serialized : contract.redact(serialized)) + "\n");
        System.out.println("STOCK_HOTSPOT: " + (Boolean.TRUE.equals(report.get("passed")) ? "PASS" : "FAIL"));
        System.exit(Boolean.TRUE.equals(report.get("passed")) ? 0 : 1);
    }
    static void check(boolean value, String message) { StockHotspotFixture.check(value, message); }
}
