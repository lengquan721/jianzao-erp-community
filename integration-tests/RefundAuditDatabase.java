import com.mysql.cj.jdbc.MysqlDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Connection/DDL boundary. JDBC is used for guards, fixtures and independent assertions, not finance writes. */
final class RefundAuditDatabase implements AutoCloseable {
    static final class Contract {
        private static final String URL_TAIL = "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
            + "&useSSL=false&allowPublicKeyRetrieval=true";
        private final String schema;
        private final String user;
        private final String password;
        private final String url;

        private Contract(String schema, String user, String password, String url) {
            this.schema = schema;
            this.user = user;
            this.password = password;
            this.url = url;
        }

        static Contract fromExec() {
            check("1".equals(System.getenv("ERP_AUDIT_REAL_MYSQL")), "real MySQL opt-in missing");
            check("127.0.0.1".equals(env("HOST")) && "43306".equals(env("PORT")), "endpoint refused");
            String schema = env("DATABASE");
            check(schema.matches("erp_audit_[a-z0-9][a-z0-9_]{0,44}"), "audit schema refused");
            String url = env("JDBC_URL");
            check(url.equals("jdbc:mysql://127.0.0.1:43306/" + schema + URL_TAIL), "JDBC URL refused");
            check(env("DRIVER").equals("com.mysql.cj.jdbc.Driver"), "JDBC driver refused");
            String user = env("USERNAME");
            String password = env("PASSWORD");
            check(user.matches("erp_audit_[a-f0-9]{10}") && password.matches("[a-f0-9]{64}"),
                "dedicated exec app account refused");
            return new Contract(schema, user, password, url);
        }

        private static String env(String key) {
            String value = System.getenv("ERP_AUDIT_MYSQL_" + key);
            check(value != null && !value.isBlank(), "missing exec variable: ERP_AUDIT_MYSQL_" + key);
            return value;
        }

        String redact(String value) {
            return value.replace(password, "[REDACTED]").replace(user, "[REDACTED]")
                .replace(schema, "erp_audit_[REDACTED]");
        }
    }

    private record Table(String name, String columns) {}
    private record Metadata(String engine, String comment) {}
    // Only the tables/columns/indexes used by the selected concrete mappers/services. Reviewed against
    // deploy/mysql/init/04-erp-schema.sql incl. D15 active_flag constraint. No business data or schema import.
    private static final String ID_TENANT = "id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(20) NOT NULL,\n";
    private static final String AUDIT = """
        create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        update_by BIGINT NULL, update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
        """;
    private static final String LIVE = "del_flag CHAR(1) NOT NULL DEFAULT '0', PRIMARY KEY (id),\n";
    private static final List<Table> TABLES = List.of(
        new Table("md_customer", ID_TENANT + """
            code VARCHAR(32) NOT NULL, name VARCHAR(100) NOT NULL, contact VARCHAR(50), phone VARCHAR(30),
            address VARCHAR(200), opening_debt DECIMAL(18,2) NOT NULL DEFAULT 0,
            tax_no VARCHAR(50), tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
            status TINYINT NOT NULL DEFAULT 1, remark VARCHAR(500),
            """ + AUDIT + LIVE + "UNIQUE KEY uk_md_customer_code (tenant_id, code)"),
        new Table("md_supplier", ID_TENANT + """
            code VARCHAR(32) NOT NULL, name VARCHAR(100) NOT NULL, contact VARCHAR(50), phone VARCHAR(30),
            address VARCHAR(200), opening_payable DECIMAL(18,2) NOT NULL DEFAULT 0,
            tax_no VARCHAR(50), tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
            status TINYINT NOT NULL DEFAULT 1, remark VARCHAR(500),
            """ + AUDIT + LIVE + "UNIQUE KEY uk_md_supplier_code (tenant_id, code)"),
        new Table("fin_opening", ID_TENANT + """
            party_type VARCHAR(8) NOT NULL, party_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            opening_date DATE DEFAULT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', remark VARCHAR(500),
            """ + AUDIT + LIVE + "UNIQUE KEY uk_fin_opening (tenant_id, party_type, party_id)"),
        new Table("fin_account", ID_TENANT + """
            code VARCHAR(32) NOT NULL, name VARCHAR(50) NOT NULL, type VARCHAR(16) NOT NULL DEFAULT 'CASH',
            opening_balance DECIMAL(18,2) NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 1, remark VARCHAR(500),
            """ + AUDIT + LIVE + "UNIQUE KEY uk_fin_account_code (tenant_id, code)"),
        new Table("biz_sale_return", ID_TENANT + """
            no VARCHAR(32) NOT NULL, sale_id BIGINT NOT NULL, sale_no VARCHAR(32) NOT NULL, customer_id BIGINT NOT NULL,
            biz_date DATE NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', total_amount DECIMAL(18,2) NOT NULL,
            need_refund TINYINT NOT NULL DEFAULT 0, refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            refund_account_id BIGINT NULL, refund_receipt_id BIGINT NULL,
            """ + LIVE + "UNIQUE KEY uk_biz_sale_return_no (tenant_id, no)"),
        // Source-number projection for the AP payment fixture; not a purchase service fixture.
        new Table("biz_purchase", ID_TENANT + """
            no VARCHAR(32) NOT NULL, supplier_id BIGINT NOT NULL, biz_date DATE NOT NULL,
            status VARCHAR(16) NOT NULL,
            """ + LIVE + "UNIQUE KEY uk_biz_purchase_no (tenant_id, no)"),
        new Table("fin_ar_ap", ID_TENANT + """
            party_type VARCHAR(8) NOT NULL, party_id BIGINT NOT NULL, source_type VARCHAR(16) NOT NULL,
            source_id BIGINT NULL, biz_date DATE NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            settled_amount DECIMAL(18,2) NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
            active_flag TINYINT GENERATED ALWAYS AS (IF(status = 'VOID', NULL, 1)) STORED,
            """ + AUDIT + LIVE + "UNIQUE KEY uk_fin_ar_ap_source (tenant_id, source_type, source_id, active_flag)"
                + ", KEY idx_ar_ap_party (tenant_id, party_type, party_id, status)"
                + ", KEY idx_ar_ap_source (tenant_id, source_type, source_id)"),
        new Table("fin_receipt", ID_TENANT + """
            no VARCHAR(32) NOT NULL, customer_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            biz_date DATE NOT NULL, account VARCHAR(50), account_id BIGINT NULL, negative TINYINT NOT NULL DEFAULT 0,
            remark VARCHAR(500), status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
            confirmed_by BIGINT NULL, confirmed_at DATETIME NULL, withdrawn_by BIGINT NULL, withdrawn_at DATETIME NULL,
            voided_by BIGINT NULL, voided_at DATETIME NULL,
            """ + AUDIT + LIVE + "UNIQUE KEY uk_fin_receipt_no (tenant_id, no)"
                + ", KEY idx_fin_receipt_customer (tenant_id, customer_id, biz_date)"),
        new Table("fin_payment", ID_TENANT + """
            no VARCHAR(32) NOT NULL, supplier_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            biz_date DATE NOT NULL, account VARCHAR(50), account_id BIGINT NULL,
            remark VARCHAR(500), status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
            confirmed_by BIGINT NULL, confirmed_at DATETIME NULL, withdrawn_by BIGINT NULL, withdrawn_at DATETIME NULL,
            voided_by BIGINT NULL, voided_at DATETIME NULL,
            """ + AUDIT + LIVE + "UNIQUE KEY uk_fin_payment_no (tenant_id, no)"
                + ", KEY idx_fin_payment_supplier (tenant_id, supplier_id, biz_date)"),
        new Table("fin_payment_alloc", ID_TENANT + """
            payment_id BIGINT NOT NULL, ar_ap_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            """ + AUDIT + LIVE + "KEY idx_payment_alloc_payment (tenant_id, payment_id)"
                + ", KEY idx_payment_alloc_ar (tenant_id, ar_ap_id)"),
        new Table("fin_receipt_alloc", ID_TENANT + """
            receipt_id BIGINT NOT NULL, ar_ap_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            """ + AUDIT + LIVE + "KEY idx_receipt_alloc_receipt (tenant_id, receipt_id)"),
        new Table("sys_sequence", ID_TENANT + """
            biz_type VARCHAR(10) NOT NULL, seq_date CHAR(8) NOT NULL, current_value INT NOT NULL DEFAULT 0,
            create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id),
            UNIQUE KEY uk_sys_sequence (tenant_id, biz_type, seq_date)
            """)
    );
    static final List<String> FIXTURE_TABLES = TABLES.stream().map(Table::name).toList();
    static final List<String> BUSINESS_TABLES = FIXTURE_TABLES.stream()
        .filter(name -> !name.equals("sys_sequence")).toList();

    private final Contract contract;
    private final String marker = "refund-business-audit-" + UUID.randomUUID();
    private final Map<String, Object> report;
    private final List<String> created = new ArrayList<>();
    private final List<String> dropped = new ArrayList<>();
    private final List<String> collisions = new ArrayList<>();
    private final List<String> cleanupErrors = new ArrayList<>();
    private final MysqlDataSource dataSource;
    private Connection control;
    private JdbcTemplate observer;

    RefundAuditDatabase(Contract validated, Map<String, Object> report) throws SQLException {
        this.contract = validated;
        this.report = report;
        report.put("created_tables", created);
        report.put("dropped_tables", dropped);
        report.put("preexisting_objects", collisions);
        report.put("cleanup_errors", cleanupErrors);
        report.put("cleanup_verified", false);
        dataSource = new MysqlDataSource();
        dataSource.setURL(validated.url);
        dataSource.setUser(validated.user);
        dataSource.setPassword(validated.password);
        dataSource.setConnectTimeout(3000);
        dataSource.setSocketTimeout(18000);
        dataSource.setAllowLoadLocalInfile(false);
        dataSource.setAllowMultiQueries(false);
    }

    void prepare() throws SQLException {
        control = dataSource.getConnection();
        observer = new JdbcTemplate(new SingleConnectionDataSource(control, true));
        check(control.getAutoCommit(), "observer must remain in autocommit");
        check("MySQL".equals(control.getMetaData().getDatabaseProductName()), "not MySQL");
        String version = control.getMetaData().getDatabaseProductVersion();
        check(version.startsWith("8.0."), "only MySQL 8.0 accepted");
        try (Statement sql = control.createStatement();
             ResultSet row = sql.executeQuery("SELECT DATABASE(), @@port, @@bind_address, @@global.local_infile")) {
            check(row.next() && contract.schema.equals(row.getString(1)) && row.getInt(2) == 43306
                && "127.0.0.1".equals(row.getString(3)) && row.getInt(4) == 0, "connected identity refused");
        }
        verifyGrants();
        report.put("host", "127.0.0.1");
        report.put("port", 43306);
        report.put("schema", "erp_audit_[REDACTED]");
        report.put("mysql_version", version);
        report.put("jdbc_driver_version", control.getMetaData().getDriverVersion());
        report.put("dedicated_schema_only_grants", true);
        try (Statement sql = control.createStatement()) {
            sql.execute("SET SESSION lock_wait_timeout=8");
            // Preflight the ENTIRE fixed set, including views, before any CREATE. On collision do no DDL.
            for (Table table : TABLES) {
                if (!metadata(table.name()).isEmpty()) collisions.add(table.name());
            }
            check(collisions.isEmpty(), "fixed fixture table/view already exists; refusing any DDL");
            for (Table table : TABLES) {
                // Deliberately no IF NOT EXISTS: a racing foreign CREATE cannot become our owned object.
                sql.execute("CREATE TABLE `" + table.name() + "` (\n" + table.columns()
                    + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='" + marker + "'");
                created.add(table.name());
                check(metadata(table.name()).equals(List.of(new Metadata("InnoDB", marker))),
                    "not an owned InnoDB fixture: " + table.name());
            }
        }
    }

    private void verifyGrants() throws SQLException {
        boolean dedicated = false;
        try (Statement sql = control.createStatement(); ResultSet rows = sql.executeQuery("SHOW GRANTS")) {
            while (rows.next()) {
                String grant = rows.getString(1);
                if (grant.startsWith("GRANT ALL PRIVILEGES ON `" + contract.schema + "`.* TO ")) dedicated = true;
                else check(grant.startsWith("GRANT USAGE ON *.* TO ") && !grant.contains("WITH GRANT OPTION"),
                    "unexpected grant; refusing fixture writes");
                check(!grant.contains("WITH GRANT OPTION"), "grant option refused");
            }
        }
        check(dedicated, "dedicated schema grants not confirmed");
    }

    private List<Metadata> metadata(String table) throws SQLException {
        List<Metadata> entries = new ArrayList<>();
        try (PreparedStatement stmt = control.prepareStatement("SELECT ENGINE, TABLE_COMMENT "
            + "FROM information_schema.tables WHERE table_schema = DATABASE() AND LOWER(table_name) = ?")) {
            stmt.setString(1, table);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) entries.add(new Metadata(rows.getString(1), rows.getString(2)));
            }
        }
        return entries;
    }

    MysqlDataSource dataSource() { return dataSource; }
    JdbcTemplate observer() { return observer; }

    static Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate reader) {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            // No tenant/del_flag filter: detect even orphaned, soft-deleted or wrongly tenanted writes.
            state.put(table, reader.queryForList("SELECT * FROM `" + table + "` ORDER BY id"));
        }
        return state;
    }

    @Override
    public void close() {
        if (control != null) {
            List<String> reverse = new ArrayList<>(created);
            Collections.reverse(reverse);
            try (Connection connection = control; Statement sql = connection.createStatement()) {
                for (String table : reverse) {
                    try {
                        // Never drop an unowned name (including a replaced object).
                        check(metadata(table).equals(List.of(new Metadata("InnoDB", marker))),
                            "fixture ownership changed; no drop");
                        sql.execute("DROP TABLE `" + table + "`");
                        check(metadata(table).isEmpty(), "fixture still exists after drop");
                        dropped.add(table);
                    } catch (SQLException | RuntimeException error) {
                        cleanupErrors.add(table + ": " + error.getClass().getSimpleName()
                            + (error instanceof SQLException sqlError ? " (#" + sqlError.getErrorCode() + ")" : ""));
                    }
                }
            } catch (SQLException error) {
                cleanupErrors.add("cleanup connection: SQL error #" + error.getErrorCode());
            }
        }
        report.put("cleanup_verified", cleanupErrors.isEmpty() && dropped.size() == created.size());
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
