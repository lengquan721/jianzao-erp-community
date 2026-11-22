import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.erp.stock.domain.ErpStockLog;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Observes the production replay's actual mapped result; never changes SQL, parameters or returned rows. */
@Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
public final class StockHotspotCapture implements Interceptor {
    private static final class Scope {
        final String tenant;
        final List<StockHotspotFixture.Row> expected;
        final boolean warmup;
        int queries;
        Scope(String tenant, List<StockHotspotFixture.Row> expected, boolean warmup) {
            this.tenant = tenant; this.expected = expected; this.warmup = warmup;
        }
    }
    private final ThreadLocal<Scope> current = new ThreadLocal<>();
    final List<Map<String, Object>> queries = new ArrayList<>();
    void call(String tenant, List<StockHotspotFixture.Row> expected, boolean warmup, Runnable action) {
        check(current.get() == null, "probe scope leaked");
        Scope scope = new Scope(tenant, expected, warmup); current.set(scope);
        try { action.run(); check(scope.queries == 1, "service did not issue exactly one full-history replay query"); }
        finally { current.remove(); }
    }
    @Override public Object intercept(Invocation invocation) throws Throwable {
        Scope scope = current.get();
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        String sql = handler.getBoundSql().getSql();
        String normalized = sql.toLowerCase(Locale.ROOT).replace("`", "").replaceAll("\\s+", " ").strip();
        if (scope == null || !normalized.contains("from st_stock_log")) return invocation.proceed();
        check(normalized.endsWith("for update") && !normalized.contains(" limit ") && !normalized.contains("/*+"), "full-history current-read shape changed");
        check(normalized.contains("tenant_id = '" + scope.tenant + "'"), "tenant predicate missing from actual replay SQL");
        Statement statement = (Statement) invocation.getArgs()[0];
        check(TransactionSynchronizationManager.isActualTransactionActive() && !statement.getConnection().getAutoCommit(), "replay not inside live Spring transaction");
        var evidence = new LinkedHashMap<String, Object>(); queries.add(evidence); scope.queries++;
        evidence.put("status", "failed"); evidence.put("tenant", scope.tenant); evidence.put("warmup", scope.warmup);
        evidence.put("final_bound_sql", sql); evidence.put("transaction_active_auto_commit_false", true);
        Object result = invocation.proceed();
        check(result instanceof List<?>, "expected real mapped replay rows");
        var ids = new ArrayList<Long>();
        for (Object value : (List<?>) result) {
            check(value instanceof ErpStockLog, "wrong real Mapper result type"); ids.add(((ErpStockLog) value).getId());
        }
        evidence.put("expected_rows", scope.expected.size()); evidence.put("actual_rows", ids.size());
        evidence.put("actual_ordered_ids_sha256", StockQueryDatabase.sha(ids.toString()));
        evidence.put("expected_ordered_ids_sha256", StockQueryDatabase.sha(scope.expected.stream().map(StockHotspotFixture.Row::id).toList().toString()));
        StockHotspotFixture.exactIds(ids, scope.expected);
        evidence.put("exact_ids_no_truncation_duplicates_or_foreign_tenant", true); evidence.put("status", "passed");
        return result;
    }
    private static void check(boolean value, String message) { StockHotspotFixture.check(value, message); }
}
