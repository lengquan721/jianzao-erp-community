import com.fasterxml.jackson.databind.JsonNode;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Original candidate DDL, random ownership comment, fail-closed fixed-name boundary and finally cleanup. */
final class StockHotspotDatabase implements AutoCloseable {
    static final List<String> TABLES = List.of("md_product", "st_stock", "st_stock_log");
    private record Metadata(String type, String engine, String comment) {}
    private final MysqlDataSource source;
    private final String schema = System.getenv("ERP_AUDIT_MYSQL_DATABASE");
    private final String marker = "stock-hotspot-audit-" + UUID.randomUUID();
    private final Map<String, Object> report;
    private final List<String> created = new ArrayList<>(), dropped = new ArrayList<>(), collisions = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private Connection control;
    private boolean boundaryLock;
    private JdbcTemplate observer;

    StockHotspotDatabase(RefundAuditDatabase.Contract contract, Map<String, Object> report) throws SQLException {
        // Only the existing validated credential factory; never its prepare()/fixtures/main or report.
        source = new RefundAuditDatabase(contract, new LinkedHashMap<>()).dataSource();
        this.report = report;
        report.put("created_tables", created); report.put("dropped_tables", dropped);
        report.put("preexisting_objects", collisions); report.put("cleanup_errors", errors);
        report.put("cleanup_verified", false);
    }
    static void selfTest(JsonNode definitions) throws Exception {
        StockQueryDatabase.selfTest(definitions); // Reviewed pure DDL canonicalization mutation tests, no JDBC.
        for (String name : TABLES) {
            String ddl = definitions.path(name).path("ddl").asText();
            check(StockQueryDatabase.sha(ddl).equals(definitions.path(name).path("original_ddl_sha256").asText()), "DDL hash mismatch");
            check(ddl.startsWith("CREATE TABLE IF NOT EXISTS " + name + " (\n"), "unexpected CREATE shape");
            String changed = ddl.replace("tenant_id VARCHAR(20)", "tenant_id VARCHAR(21)");
            check(!StockQueryDatabase.canonical(ddl).equals(StockQueryDatabase.canonical(changed)), "tenant type mutation missed");
        }
        String product = definitions.path("md_product").path("ddl").asText();
        String mysql = product.replace(" BIGINT NULL COMMENT", " BIGINT DEFAULT NULL COMMENT")
            .replace(" DECIMAL(18,3) NULL COMMENT", " DECIMAL(18,3) DEFAULT NULL COMMENT");
        check(sourceCanonical("md_product", product).equals(StockQueryDatabase.canonical(mysql)), "nullable product SHOW CREATE rendering refused");
        check(!sourceCanonical("md_product", product).equals(StockQueryDatabase.canonical(mysql.replace(
            "category_id BIGINT DEFAULT NULL", "category_id BIGINT DEFAULT 7"))), "product default drift missed");
        check(!sourceCanonical("md_product", product).equals(StockQueryDatabase.canonical(mysql.replace(
            "supplier_id BIGINT DEFAULT NULL", "supplier_id BIGINT NOT NULL"))), "product nullability drift missed");
        StockHotspotFixture.selfTest();
        StockHotspotMySql.lockShapeSelfTest();
    }

    private static String sourceCanonical(String name, String ddl) {
        if ("md_product".equals(name)) {
            // MySQL SHOW CREATE represents these explicit nullable, no-default numeric columns as DEFAULT NULL.
            ddl = ddl.replace("  category_id BIGINT NULL COMMENT", "  category_id BIGINT DEFAULT NULL COMMENT")
                .replace("  supplier_id BIGINT NULL COMMENT", "  supplier_id BIGINT DEFAULT NULL COMMENT")
                .replace("  min_stock DECIMAL(18,3) NULL COMMENT", "  min_stock DECIMAL(18,3) DEFAULT NULL COMMENT")
                .replace("  max_stock DECIMAL(18,3) NULL COMMENT", "  max_stock DECIMAL(18,3) DEFAULT NULL COMMENT");
        }
        return StockQueryDatabase.canonical(ddl);
    }

    void prepare(JsonNode definitions) throws Exception {
        control = source.getConnection();
        check(control.getAutoCommit(), "observer/DDL must be autocommit");
        String version = control.getMetaData().getDatabaseProductVersion();
        check("MySQL".equals(control.getMetaData().getDatabaseProductName()) && version.startsWith("8.0."), "MySQL 8.0 required");
        try (Statement sql = control.createStatement()) {
            sql.setQueryTimeout(8);
            try (ResultSet row = sql.executeQuery("SELECT DATABASE(), @@port, @@bind_address, @@global.local_infile")) {
                check(row.next() && schema.equals(row.getString(1)) && row.getInt(2) == 43306
                    && "127.0.0.1".equals(row.getString(3)) && row.getInt(4) == 0, "connected identity refused");
            }
            boolean dedicated = false;
            try (ResultSet rows = sql.executeQuery("SHOW GRANTS")) {
                while (rows.next()) {
                    String grant = rows.getString(1);
                    check(!grant.contains("WITH GRANT OPTION"), "grant option refused");
                    if (grant.startsWith("GRANT ALL PRIVILEGES ON `" + schema + "`.* TO ")) dedicated = true;
                    else check(grant.startsWith("GRANT USAGE ON *.* TO "), "unexpected privilege refused");
                }
            }
            check(dedicated, "dedicated schema-only grant not confirmed");
            report.put("host", "127.0.0.1"); report.put("port", 43306); report.put("schema", "erp_audit_[REDACTED]");
            report.put("mysql_version", version); report.put("schema_only_grants_verified", true);
            report.put("connector_j", control.getMetaData().getDriverVersion());
            try (ResultSet row = sql.executeQuery("SELECT GET_LOCK('stock-hotspot-fixture-boundary',0)")) {
                check(row.next() && row.getInt(1) == 1, "another hotspot run owns boundary lock"); boundaryLock = true;
            }
            sql.execute("SET SESSION lock_wait_timeout=6");
            for (String name : TABLES) if (!metadata(name).isEmpty()) collisions.add(name);
            check(collisions.isEmpty(), "same-name table/view exists; refusing ALL fixture DDL");
            var evidence = new LinkedHashMap<String, Object>(); report.put("ddl_fingerprints", evidence);
            for (String name : TABLES) {
                String original = definitions.path(name).path("ddl").asText();
                check(StockQueryDatabase.sha(original).equals(definitions.path(name).path("original_ddl_sha256").asText()), "DDL fingerprint refused");
                check(original.startsWith("CREATE TABLE IF NOT EXISTS " + name + " (\n"), "fixed CREATE refused");
                String ddl = original.replaceFirst("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE ")
                    .replaceFirst("COMMENT='[^']*';$", "COMMENT='" + marker + "';");
                sql.execute(ddl); // No IF NOT EXISTS, ALTER, added indexes, hints or simplified schema.
                created.add(name); assertOwned(name);
                String actual;
                try (ResultSet row = sql.executeQuery("SHOW CREATE TABLE `" + name + "`")) {
                    check(row.next(), "SHOW CREATE absent"); actual = row.getString(2);
                }
                String expectedCanonical = sourceCanonical(name, original), actualCanonical = StockQueryDatabase.canonical(actual);
                boolean equal = expectedCanonical.equals(actualCanonical);
                evidence.put(name, Map.of("original_ddl_sha256", StockQueryDatabase.sha(original),
                    "canonical_source_sha256", StockQueryDatabase.sha(expectedCanonical),
                    "canonical_show_create_sha256", StockQueryDatabase.sha(actualCanonical),
                    "matches_original_columns_and_indexes", equal,
                    "show_create", actual.replace(marker, "<this-run-owned-comment>")));
                check(equal, "SHOW CREATE differs from candidate columns/indexes: " + name);
            }
        }
        observer = new JdbcTemplate(new SingleConnectionDataSource(control, true)); observer.setQueryTimeout(8);
    }
    private List<Metadata> metadata(String name) throws SQLException {
        var entries = new ArrayList<Metadata>();
        try (PreparedStatement sql = control.prepareStatement("SELECT TABLE_TYPE,ENGINE,TABLE_COMMENT FROM information_schema.tables "
            + "WHERE table_schema=DATABASE() AND LOWER(table_name)=?")) {
            sql.setQueryTimeout(8); sql.setString(1, name);
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) entries.add(new Metadata(rows.getString(1), rows.getString(2), rows.getString(3)));
            }
        }
        return entries;
    }
    void assertOwned(String name) throws SQLException {
        check(created.contains(name) && metadata(name).equals(List.of(new Metadata("BASE TABLE", "InnoDB", marker))), "ownership changed; refusing writes/drop");
    }
    void assertAllOwned() throws SQLException { for (String name : TABLES) assertOwned(name); }
    MysqlDataSource dataSource() { return source; }
    JdbcTemplate observer() { return observer; }
    @Override public void close() {
        if (control != null) {
            try (Connection connection = control; Statement sql = connection.createStatement()) {
                sql.setQueryTimeout(8);
                for (int i = created.size() - 1; i >= 0; i--) {
                    String name = created.get(i);
                    try {
                        assertOwned(name); sql.execute("DROP TABLE `" + name + "`");
                        check(metadata(name).isEmpty(), "owned table remains after DROP"); dropped.add(name);
                    } catch (SQLException | RuntimeException error) {
                        errors.add(name + ": " + error.getClass().getSimpleName() + (error instanceof SQLException e ? " #" + e.getErrorCode() : ""));
                    }
                }
                if (boundaryLock) {
                    try (ResultSet row = sql.executeQuery("SELECT RELEASE_LOCK('stock-hotspot-fixture-boundary')")) {
                        boolean released = row.next() && row.getInt(1) == 1;
                        report.put("boundary_lock_released", released);
                        if (!released) errors.add("boundary lock release not verified");
                    }
                }
            } catch (SQLException error) { errors.add("cleanup connection: SQL #" + error.getErrorCode()); }
        }
        report.put("cleanup_verified", errors.isEmpty() && created.size() == dropped.size());
        report.put("owned_objects_remaining", created.stream().filter(n -> !dropped.contains(n)).toList());
    }
    static void check(boolean condition, String message) { StockHotspotFixture.check(condition, message); }
}
