import com.fasterxml.jackson.databind.JsonNode;
import com.mysql.cj.jdbc.MysqlDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fixed-name, original-DDL fixtures. Own only successful CREATEs bearing this run's random COMMENT. */
final class StockQueryDatabase implements AutoCloseable {
    static final List<String> TABLES = List.of("st_stock", "st_stock_log");
    private record Metadata(String type, String engine, String comment) {}
    private static final Pattern TOKENS = Pattern.compile("'(?:''|[^'])*'|`[^`]*`|[a-zA-Z_][a-zA-Z_0-9]*|[0-9]+(?:\\.[0-9]+)?|[^\\s]");
    private final MysqlDataSource source;
    private final String schema = System.getenv("ERP_AUDIT_MYSQL_DATABASE");
    private final String marker = "stock-query-audit-" + UUID.randomUUID();
    private final Map<String, Object> report;
    private final List<String> created = new ArrayList<>(), dropped = new ArrayList<>(), collisions = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private Connection control;
    private boolean namedLock;

    StockQueryDatabase(RefundAuditDatabase.Contract contract, Map<String, Object> report) throws SQLException {
        source = new RefundAuditDatabase(contract, new LinkedHashMap<>()).dataSource();
        this.report = report;
        report.put("created_tables", created);
        report.put("dropped_tables", dropped);
        report.put("preexisting_objects", collisions);
        report.put("cleanup_errors", errors);
        report.put("cleanup_verified", false);
    }

    static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Only MySQL rendering differences, NOT omitted columns/keys/defaults/comments, are normalized. */
    static String canonical(String ddl) {
        var lines = new ArrayList<String>();
        for (String line : ddl.replace("\r", "").strip().split("\n")) {
            var tokens = new ArrayList<String>();
            var matcher = TOKENS.matcher(line);
            while (matcher.find()) {
                String t = matcher.group();
                if (t.startsWith("`")) t = t.substring(1, t.length() - 1).toLowerCase(Locale.ROOT);
                else if (!t.startsWith("'")) t = t.toLowerCase(Locale.ROOT);
                tokens.add(t);
            }
            if (tokens.isEmpty()) continue;
            boolean numericColumn = tokens.size() > 1 && Set.of("bigint", "int", "tinyint", "decimal").contains(tokens.get(1));
            var normalized = new ArrayList<String>();
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (t.equals("if") && i + 2 < tokens.size() && tokens.get(i + 1).equals("not")
                    && tokens.get(i + 2).equals("exists")) { i += 2; continue; }
                if (t.equals("collate") && tokens.get(0).equals(")")) {
                    check(i + 2 < tokens.size() && tokens.get(i + 1).equals("=")
                        && tokens.get(i + 2).equals("utf8mb4_0900_ai_ci"), "unexpected implicit table collation");
                    i += 2; continue;
                }
                if (t.equals("auto_increment") && tokens.get(0).equals(")")) {
                    check(i + 2 < tokens.size() && tokens.get(i + 1).equals("=")
                        && tokens.get(i + 2).matches("[0-9]+"), "unexpected AUTO_INCREMENT table option");
                    i += 2; continue;
                }
                if (t.equals("comment") && tokens.get(0).equals(")")) {
                    check(i + 2 < tokens.size() && tokens.get(i + 1).equals("=")
                        && tokens.get(i + 2).startsWith("'"), "unexpected table comment syntax");
                    normalized.add("comment"); normalized.add("="); normalized.add("'<ownership>'");
                    i += 2; continue;
                }
                if (t.equals("current_timestamp") && i + 2 < tokens.size()
                    && tokens.get(i + 1).equals("(") && tokens.get(i + 2).equals(")")) i += 2;
                if (numericColumn && i > 0 && tokens.get(i - 1).equals("default")
                    && t.matches("'[-+]?[0-9]+(?:\\.[0-9]+)?'"))
                    t = new BigDecimal(t.substring(1, t.length() - 1)).stripTrailingZeros().toPlainString();
                normalized.add(t);
            }
            while (!normalized.isEmpty() && Set.of(",", ";").contains(normalized.get(normalized.size() - 1)))
                normalized.remove(normalized.size() - 1);
            lines.add(String.join(" ", normalized));
        }
        return String.join("\n", lines);
    }

    static void selfTest(JsonNode definitions) throws Exception {
        for (String name : TABLES) {
            String raw = definitions.path(name).path("ddl").asText();
            check(sha(raw).equals(definitions.path(name).path("original_ddl_sha256").asText()), "DDL manifest hash mismatch");
            String simulated = raw.replace("CREATE TABLE IF NOT EXISTS " + name, "CREATE TABLE `" + name + "`")
                .replace("BIGINT", "bigint").replace(" DEFAULT 0 ", " DEFAULT '0.0000' ")
                .replace("CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP()")
                .replace("DEFAULT CHARSET=utf8mb4 COMMENT=", "AUTO_INCREMENT=2000001 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=")
                .replace("COMMENT='库存流水'", "COMMENT='stock-query-audit-test'")
                .replace("COMMENT='库存快照'", "COMMENT='stock-query-audit-test'");
            check(canonical(raw).equals(canonical(simulated)), "allowed SHOW CREATE rendering failed: " + name);
            check(!canonical(raw).equals(canonical(raw.replace("product_id BIGINT", "product_id INT"))), "type drift missed");
            check(!canonical(raw).equals(canonical(raw.replace("qty DECIMAL(18,3)", "qty DECIMAL(19,3)"))), "precision drift missed");
            check(!canonical(raw).equals(canonical(raw.replace("DEFAULT 0", "DEFAULT 1"))), "default drift missed");
            check(!canonical(raw).equals(canonical(raw.replace("PRIMARY KEY (id)", "PRIMARY KEY (tenant_id, id)"))), "PK drift missed");
        }
        String log = definitions.path("st_stock_log").path("ddl").asText();
        check(!canonical(log).equals(canonical(log.replace("  KEY idx_stock_log_product (tenant_id, product_id, biz_date),\n", ""))),
            "missing production index missed");
        check(!canonical(log).equals(canonical(log.replace("(tenant_id, product_id, biz_date)", "(product_id, tenant_id, biz_date)"))),
            "index order drift missed");
        boolean refused = false;
        try { canonical(log.replace("DEFAULT CHARSET=utf8mb4", "DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin")); }
        catch (IllegalStateException expected) { refused = true; }
        check(refused, "unexpected collation was accepted");
    }

    void prepare(JsonNode definitions) throws Exception {
        control = source.getConnection();
        check(control.getAutoCommit(), "DDL observer must be autocommit");
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
                    else check(grant.startsWith("GRANT USAGE ON *.* TO "), "unexpected grant refused");
                }
            }
            check(dedicated, "dedicated schema grant not confirmed");
            report.put("host", "127.0.0.1"); report.put("port", 43306); report.put("schema", "erp_audit_[REDACTED]");
            report.put("mysql_version", version); report.put("connector_j", control.getMetaData().getDriverVersion());
            report.put("schema_only_grant_verified", true);
            try (ResultSet row = sql.executeQuery("SELECT GET_LOCK('stock-query-fixture-boundary',0)")) {
                check(row.next() && row.getInt(1) == 1, "another stock query fixture run owns the boundary lock");
                namedLock = true;
            }
            sql.execute("SET SESSION lock_wait_timeout=6");
            for (String name : TABLES) if (!metadata(name).isEmpty()) collisions.add(name);
            check(collisions.isEmpty(), "same-name table/view preexists; refusing ALL fixture DDL");
            var evidence = new LinkedHashMap<String, Object>();
            report.put("ddl_fingerprints", evidence);
            for (String name : TABLES) {
                String original = definitions.path(name).path("ddl").asText();
                check(sha(original).equals(definitions.path(name).path("original_ddl_sha256").asText()), "DDL fingerprint refused");
                check(original.startsWith("CREATE TABLE IF NOT EXISTS " + name + " (\n"), "fixed original CREATE refused");
                String ddl = original.replaceFirst("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE ")
                    .replaceFirst("COMMENT='[^']*';$", "COMMENT='" + marker + "';");
                // No IF NOT EXISTS and no ALTER/index/hint changes. Never claim a losing CREATE race.
                sql.execute(ddl);
                created.add(name);
                assertOwned(name);
                String actual;
                try (ResultSet row = sql.executeQuery("SHOW CREATE TABLE `" + name + "`")) {
                    check(row.next(), "SHOW CREATE absent"); actual = row.getString(2);
                }
                boolean equal = canonical(original).equals(canonical(actual));
                var row = new LinkedHashMap<String, Object>();
                row.put("original_ddl_sha256", sha(original)); row.put("created_ddl_sha256", sha(ddl));
                row.put("raw_show_create_sha256", sha(actual)); row.put("canonical_source_sha256", sha(canonical(original)));
                row.put("canonical_show_create_sha256", sha(canonical(actual))); row.put("matches_original_columns_and_indexes", equal);
                row.put("show_create", actual.replace(marker, "<this-run-owned-comment>"));
                row.put("normalization", "identifier quoting/case/whitespace; numeric default rendering; CURRENT_TIMESTAMP(); implicit utf8mb4_0900_ai_ci; table ownership COMMENT; table AUTO_INCREMENT counter only");
                evidence.put(name, row);
                check(equal, "SHOW CREATE differs from original production DDL: " + name);
            }
        }
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
        check(created.contains(name) && metadata(name).equals(List.of(new Metadata("BASE TABLE", "InnoDB", marker))),
            "fixture ownership changed; no writes/drop allowed");
    }
    MysqlDataSource dataSource() { return source; }

    @Override public void close() {
        if (control != null) {
            try (Connection connection = control; Statement sql = connection.createStatement()) {
                sql.setQueryTimeout(10);
                for (int i = created.size() - 1; i >= 0; i--) {
                    String name = created.get(i);
                    try {
                        assertOwned(name);
                        sql.execute("DROP TABLE `" + name + "`");
                        check(metadata(name).isEmpty(), "owned fixture remains after DROP");
                        dropped.add(name);
                    } catch (SQLException | RuntimeException error) {
                        errors.add(name + ": " + error.getClass().getSimpleName()
                            + (error instanceof SQLException e ? " #" + e.getErrorCode() : ""));
                    }
                }
                if (namedLock) {
                    try (ResultSet row = sql.executeQuery("SELECT RELEASE_LOCK('stock-query-fixture-boundary')")) {
                        report.put("boundary_lock_released", row.next() && row.getInt(1) == 1);
                    }
                }
            } catch (SQLException error) { errors.add("cleanup connection: SQL #" + error.getErrorCode()); }
        }
        report.put("cleanup_verified", errors.isEmpty() && created.size() == dropped.size());
        report.put("owned_objects_remaining", created.stream().filter(n -> !dropped.contains(n)).toList());
    }

    static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
