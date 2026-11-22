import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Observe real JDBC prepare and post-tenant BoundSql. EXPLAIN uses that exact SQL + actual MyBatis binder. */
@Intercepts({
    @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
    @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class})
})
public final class StockQueryCapture implements Interceptor {
    record Scope(String scene, String tenant, String operation, boolean locked, int rowLimit) {}
    private record Prepared(String sql, BoundSql bound, List<Map<String, Object>> parameters) {}
    private final ThreadLocal<Scope> current = new ThreadLocal<>();
    private final Map<Statement, Prepared> prepared = new IdentityHashMap<>();
    final List<Map<String, Object>> queries = new ArrayList<>();
    int handlerPrepares, jdbcPrepares;
    private final ObjectMapper json = new ObjectMapper();

    <T> T call(String scene, String tenant, String operation, boolean locked, int rowLimit, Supplier<T> action) {
        check(current.get() == null, "probe context leak");
        current.set(new Scope(scene, tenant, operation, locked, rowLimit));
        try { return action.get(); } finally { current.remove(); }
    }

    @Override public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        Scope scope = Objects.requireNonNull(current.get(), "unexpected Mapper query outside a scene");
        if (invocation.getMethod().getName().equals("prepare")) {
            handlerPrepares++;
            Connection real = (Connection) invocation.getArgs()[0];
            var actualSql = new ArrayList<String>();
            // Observational wrapper only during prepare: all methods invoke the same physical connection unchanged.
            Connection observing = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args != null && args.length > 0 && args[0] instanceof String sql) {
                        jdbcPrepares++; actualSql.add(sql);
                    }
                    try { return method.invoke(real, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
            invocation.getArgs()[0] = observing;
            Statement statement;
            try { statement = (Statement) invocation.proceed(); }
            finally { invocation.getArgs()[0] = real; }
            BoundSql bound = handler.getBoundSql();
            check(actualSql.size() == 1 && actualSql.get(0).equals(bound.getSql()), "actual JDBC prepare differs from final BoundSql");
            check(scope.tenant != null && bound.getSql().contains("tenant_id = '" + scope.tenant + "'"),
                "production tenant predicate absent from final SQL");
            check(!bound.getSql().toLowerCase().contains("force index") && !bound.getSql().contains("/*+"), "hint refused");
            statement.setQueryTimeout(8);
            prepared.put(statement, new Prepared(actualSql.get(0), bound, parameters(bound)));
            return statement;
        }
        Statement statement = (Statement) invocation.getArgs()[0];
        Prepared input = Objects.requireNonNull(prepared.remove(statement), "query lacks observed JDBC prepare");
        check(input.sql.equals(handler.getBoundSql().getSql()), "SQL changed after prepare");
        Connection connection = statement.getConnection();
        boolean transaction = TransactionSynchronizationManager.isActualTransactionActive() && !connection.getAutoCommit();
        if (scope.locked) check(transaction && input.sql.strip().toLowerCase().endsWith("for update"), "lock query not inside one live transaction");
        var row = new LinkedHashMap<String, Object>();
        row.put("ordinal", queries.size() + 1); row.put("scene", scope.scene); row.put("operation", scope.operation);
        row.put("tenant", scope.tenant); row.put("final_bound_sql", input.sql); row.put("jdbc_prepare_sql_equals_bound_sql", true);
        row.put("sql_sha256", StockQueryDatabase.sha(input.sql)); row.put("parameters", input.parameters);
        row.put("transaction_active", transaction); row.put("connection_auto_commit", connection.getAutoCommit());
        row.put("status", "failed"); queries.add(row);
        long started = System.nanoTime();
        Object result = invocation.proceed();
        row.put("jdbc_execute_and_mybatis_mapping_ms", millis(started));
        check(result instanceof List<?>, "expected mapped List result");
        int count = ((List<?>) result).size();
        row.put("actual_returned_rows", count); row.put("configured_row_limit", scope.rowLimit < 0 ? null : scope.rowLimit);
        if (scope.rowLimit >= 0) check(count <= scope.rowLimit, "real SELECT exceeded configured LIMIT");
        try (Statement identity = connection.createStatement()) {
            identity.setQueryTimeout(8);
            try (ResultSet id = identity.executeQuery("SELECT CONNECTION_ID()")) {
                check(id.next(), "missing connection id"); row.put("mysql_connection_id", id.getLong(1));
            }
        }
        started = System.nanoTime();
        // Plain EXPLAIN is not ANALYZE; no SELECT is reconstructed and FOR UPDATE is NOT stripped.
        try (PreparedStatement explain = connection.prepareStatement("EXPLAIN FORMAT=JSON " + input.sql)) {
            explain.setQueryTimeout(8);
            handler.getParameterHandler().setParameters(explain);
            try (ResultSet plan = explain.executeQuery()) {
                check(plan.next(), "EXPLAIN JSON absent");
                JsonNode parsed = json.readTree(plan.getString(1));
                row.put("explain_format_json", parsed);
                var facts = new ArrayList<Map<String, Object>>();
                collectTables(parsed, facts);
                row.put("plan_tables", facts);
                row.put("using_filesort", hasTrue(parsed, "using_filesort"));
                row.put("full_table_or_index_scan", facts.stream().anyMatch(t -> List.of("ALL", "index").contains(t.get("access_type"))));
            }
        }
        row.put("explain_only_ms", millis(started));
        row.put("explain_same_sql_and_parameter_handler", true);
        row.put("explain_on_same_connection_before_transaction_end", scope.locked ? transaction : null);
        row.put("status", "passed");
        return result;
    }

    private static List<Map<String, Object>> parameters(BoundSql bound) {
        var values = new ArrayList<Map<String, Object>>();
        Object parameter = bound.getParameterObject();
        for (ParameterMapping mapping : bound.getParameterMappings()) {
            if (mapping.getMode() == ParameterMode.OUT) continue;
            String name = mapping.getProperty();
            Object value;
            if (bound.hasAdditionalParameter(name)) value = bound.getAdditionalParameter(name);
            else if (parameter instanceof Map<?, ?> map) value = map.get(name);
            else throw new IllegalStateException("unexpected non-map Mapper parameter contract");
            var row = new LinkedHashMap<String, Object>();
            row.put("position", values.size() + 1); row.put("property", name);
            row.put("java_type", mapping.getJavaType().getName());
            row.put("jdbc_type", mapping.getJdbcType() == null ? null : mapping.getJdbcType().name());
            row.put("value", value instanceof TemporalAccessor || value instanceof java.util.Date ? value.toString() : value);
            values.add(row);
        }
        return values;
    }

    private static void collectTables(JsonNode node, List<Map<String, Object>> result) {
        if (node.isObject() && node.has("table_name")) {
            var table = new LinkedHashMap<String, Object>();
            for (String key : List.of("table_name", "access_type", "possible_keys", "key", "used_key_parts", "key_length",
                "ref", "rows_examined_per_scan", "rows_produced_per_join", "filtered", "using_index", "attached_condition"))
                if (node.has(key)) table.put(key, node.get(key).isTextual() ? node.get(key).asText() : node.get(key));
            result.add(table);
        }
        if (node.isContainerNode()) node.forEach(child -> collectTables(child, result));
    }
    private static boolean hasTrue(JsonNode node, String key) {
        if (node.isObject() && node.path(key).asBoolean(false)) return true;
        if (node.isContainerNode()) for (JsonNode child : node) if (hasTrue(child, key)) return true;
        return false;
    }
    static double millis(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    static void check(boolean value, String message) { StockQueryDatabase.check(value, message); }
}
