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

/** Guarded DDL and observer; never implements business commands in SQL. */
final class TradeReturnAuditDatabase implements AutoCloseable {
    private record Table(String name, String columns) {}
    private record Metadata(String engine, String comment) {}
    private static final String ID = "id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(20) NOT NULL,\n";
    private static final String AUDIT = """
        create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        update_by BIGINT NULL, update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
        """;
    private static final String STATE_AUDIT = """
        confirmed_by BIGINT NULL, confirmed_at DATETIME NULL, withdrawn_by BIGINT NULL, withdrawn_at DATETIME NULL,
        voided_by BIGINT NULL, voided_at DATETIME NULL,
        """;
    private static final String LIVE = "del_flag CHAR(1) NOT NULL DEFAULT '0', PRIMARY KEY (id)";
    private static final String FULL = STATE_AUDIT + AUDIT + LIVE;
    // Explicit minimal schema, reviewed against deploy/mysql/init/04-erp-schema.sql, including
    // the final source_order columns and D15 active_flag. Only exercised mapper/entity columns and keys.
    private static final List<Table> TABLES = List.of(
        new Table("md_customer", ID + """
            code VARCHAR(32) NOT NULL, name VARCHAR(100) NOT NULL, contact VARCHAR(50), phone VARCHAR(30),
            address VARCHAR(200), opening_debt DECIMAL(18,2) NOT NULL DEFAULT 0,
            tax_no VARCHAR(50), tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 1,
            remark VARCHAR(500),
            """ + AUDIT + LIVE + ", UNIQUE KEY uk_md_customer_code (tenant_id, code)"),
        new Table("md_supplier", ID + """
            code VARCHAR(32) NOT NULL, name VARCHAR(100) NOT NULL, contact VARCHAR(50), phone VARCHAR(30),
            address VARCHAR(200), opening_payable DECIMAL(18,2) NOT NULL DEFAULT 0,
            tax_no VARCHAR(50), tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
            status TINYINT NOT NULL DEFAULT 1, remark VARCHAR(500),
            """ + AUDIT + LIVE + ", UNIQUE KEY uk_md_supplier_code (tenant_id, code)"),
        new Table("fin_opening", ID + """
            party_type VARCHAR(8) NOT NULL, party_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            opening_date DATE DEFAULT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', remark VARCHAR(500),
            """ + AUDIT + LIVE + ", UNIQUE KEY uk_fin_opening (tenant_id, party_type, party_id)"),
        new Table("md_product", ID + """
            code VARCHAR(32) NOT NULL, name VARCHAR(100) NOT NULL, spec VARCHAR(100), unit VARCHAR(16) NOT NULL DEFAULT '',
            type VARCHAR(16) NOT NULL DEFAULT 'PRODUCT', category_id BIGINT NULL, supplier_id BIGINT NULL,
            qty_scale TINYINT NOT NULL DEFAULT 0, cost_price DECIMAL(18,4) NOT NULL DEFAULT 0,
            purchase_price DECIMAL(18,4) NOT NULL DEFAULT 0, tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX',
            freight DECIMAL(18,4) NOT NULL DEFAULT 0, min_stock DECIMAL(18,3), max_stock DECIMAL(18,3),
            status TINYINT NOT NULL DEFAULT 1, remark VARCHAR(500),
            """ + AUDIT + LIVE + ", UNIQUE KEY uk_md_product_code (tenant_id, code)"),
        new Table("md_warehouse", ID + """
            code VARCHAR(32) NOT NULL, name VARCHAR(100) NOT NULL, status TINYINT NOT NULL DEFAULT 1, remark VARCHAR(500),
            """ + AUDIT + LIVE + ", UNIQUE KEY uk_md_warehouse_code (tenant_id, code)"),
        new Table("fin_account", ID + """
            code VARCHAR(32) NOT NULL, name VARCHAR(50) NOT NULL, type VARCHAR(16) NOT NULL DEFAULT 'CASH',
            opening_balance DECIMAL(18,2) NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 1, remark VARCHAR(500),
            """ + AUDIT + LIVE + ", UNIQUE KEY uk_fin_account_code (tenant_id, code)"),
        // AP opening overlap reads fin_payment even when this suite creates no payment.
        new Table("fin_payment", ID + """
            no VARCHAR(32) NOT NULL, supplier_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            biz_date DATE NOT NULL, account VARCHAR(50), account_id BIGINT NULL, remark VARCHAR(500),
            status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
            """ + FULL + ", UNIQUE KEY uk_fin_payment_no (tenant_id, no)"
                + ", KEY idx_fin_payment_supplier (tenant_id, supplier_id, biz_date)"),
        new Table("biz_purchase_order", ID + """
            no VARCHAR(32) NOT NULL, supplier_id BIGINT NOT NULL, biz_date DATE NOT NULL, due_date DATE NULL,
            status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', exec_status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED',
            supplier_order_no VARCHAR(64) NOT NULL DEFAULT '', buyer_id BIGINT NULL, buyer_name VARCHAR(50) NOT NULL DEFAULT '',
            contact_name VARCHAR(64) NOT NULL DEFAULT '', contact_phone VARCHAR(32) NOT NULL DEFAULT '',
            allow_delay_days INT NOT NULL DEFAULT 0, over_tol_pct DECIMAL(5,2) NOT NULL DEFAULT 0,
            tax_type VARCHAR(20) NOT NULL DEFAULT 'VAT_INVOICE', tax_rate DECIMAL(8,4) NOT NULL DEFAULT 13,
            goods_amount DECIMAL(18,2) NOT NULL DEFAULT 0, remark VARCHAR(500),
            """ + FULL + ", UNIQUE KEY uk_biz_purchase_order_no (tenant_id, no)"
                + ", KEY idx_biz_po_status (tenant_id, status, exec_status, due_date)"
                + ", KEY idx_biz_po_supplier (tenant_id, supplier_id, status)"),
        new Table("biz_purchase_order_item", ID + """
            order_id BIGINT NOT NULL, seq INT NOT NULL DEFAULT 0, product_id BIGINT NOT NULL,
            qty DECIMAL(18,3) NOT NULL DEFAULT 0, price DECIMAL(18,4) NOT NULL DEFAULT 0,
            tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX', tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
            amount DECIMAL(18,2) NOT NULL DEFAULT 0, due_date DATE NOT NULL, promised_date DATE NULL,
            executed_qty DECIMAL(18,3) NOT NULL DEFAULT 0, closed_qty DECIMAL(18,3) NOT NULL DEFAULT 0,
            closed_flag TINYINT NOT NULL DEFAULT 0, close_reason VARCHAR(200), closed_by BIGINT NULL,
            closed_at DATETIME NULL, remark VARCHAR(500),
            """ + AUDIT + LIVE + ", KEY idx_biz_poi_order (tenant_id, order_id, seq)"
                + ", KEY idx_biz_poi_product (tenant_id, product_id), KEY idx_biz_poi_due (tenant_id, closed_flag, due_date)"),
        new Table("biz_purchase", ID + """
            no VARCHAR(32) NOT NULL, source_order_id BIGINT NULL, source_order_no VARCHAR(32),
            supplier_id BIGINT NOT NULL, biz_date DATE NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
            tax_type VARCHAR(20) NOT NULL DEFAULT 'VAT_INVOICE', tax_rate DECIMAL(8,4) NOT NULL DEFAULT 13,
            goods_amount DECIMAL(18,2) NOT NULL DEFAULT 0, freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            freight_alloc_method VARCHAR(20) NOT NULL DEFAULT 'BY_QTY', total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            contact_name VARCHAR(64) NOT NULL DEFAULT '', contact_phone VARCHAR(32) NOT NULL DEFAULT '', remark VARCHAR(500),
            """ + FULL + ", UNIQUE KEY uk_biz_purchase_no (tenant_id, no)"
                + ", KEY idx_biz_purchase_date (tenant_id, biz_date), KEY idx_biz_purchase_supplier (tenant_id, supplier_id)"
                + ", KEY idx_biz_purchase_source (tenant_id, source_order_id)"),
        new Table("biz_purchase_item", ID + """
            order_id BIGINT NOT NULL, source_order_item_id BIGINT NULL, product_id BIGINT NOT NULL, seq INT NOT NULL DEFAULT 0,
            qty DECIMAL(18,3) NOT NULL DEFAULT 0, price DECIMAL(18,4) NOT NULL DEFAULT 0,
            tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX', tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
            amount DECIMAL(18,2) NOT NULL DEFAULT 0, freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            freight_per_unit DECIMAL(18,4) NOT NULL DEFAULT 0, cost_price DECIMAL(18,4) NOT NULL DEFAULT 0, remark VARCHAR(500),
            """ + AUDIT + LIVE + ", KEY idx_purchase_item_order (tenant_id, order_id)"
                + ", KEY idx_purchase_item_product (tenant_id, product_id), KEY idx_purchase_item_source (tenant_id, source_order_item_id)"),
        new Table("biz_purchase_return", ID + """
            no VARCHAR(32) NOT NULL, purchase_id BIGINT NOT NULL, purchase_no VARCHAR(32) NOT NULL,
            supplier_id BIGINT NOT NULL, biz_date DATE NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
            total_amount DECIMAL(18,2) NOT NULL DEFAULT 0, return_cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            remark VARCHAR(500),
            """ + FULL + ", UNIQUE KEY uk_biz_purchase_return_no (tenant_id, no)"
                + ", KEY idx_purchase_return_purchase (tenant_id, purchase_id)"
                + ", KEY idx_purchase_return_supplier (tenant_id, supplier_id), KEY idx_purchase_return_date (tenant_id, biz_date)"),
        new Table("biz_purchase_return_item", ID + """
            order_id BIGINT NOT NULL, purchase_item_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
            qty DECIMAL(18,3) NOT NULL DEFAULT 0, price DECIMAL(18,4) NOT NULL DEFAULT 0,
            amount DECIMAL(18,2) NOT NULL DEFAULT 0, unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0,
            return_cost DECIMAL(18,2) NOT NULL DEFAULT 0, remark VARCHAR(500), seq INT NOT NULL DEFAULT 0,
            """ + AUDIT + LIVE + ", KEY idx_purchase_return_item_order (tenant_id, order_id)"
                + ", KEY idx_purchase_return_item_src (tenant_id, purchase_item_id)"
                + ", KEY idx_purchase_return_item_product (tenant_id, product_id)"),
        new Table("biz_sale_order", ID + """
            no VARCHAR(32) NOT NULL, customer_id BIGINT NOT NULL, biz_date DATE NOT NULL, due_date DATE NULL,
            status VARCHAR(16) NOT NULL, exec_status VARCHAR(16) NOT NULL, over_tol_pct DECIMAL(5,2) NOT NULL DEFAULT 0,
            """ + LIVE + ", UNIQUE KEY uk_biz_sale_order_no (tenant_id, no)"),
        new Table("biz_sale_order_item", ID + """
            order_id BIGINT NOT NULL, seq INT NOT NULL DEFAULT 0, product_id BIGINT NOT NULL,
            qty DECIMAL(18,3) NOT NULL, executed_qty DECIMAL(18,3) NOT NULL, closed_qty DECIMAL(18,3) NOT NULL DEFAULT 0,
            closed_flag TINYINT NOT NULL DEFAULT 0, due_date DATE NOT NULL,
            """ + LIVE + ", KEY idx_biz_soi_order (tenant_id, order_id, seq)"),
        new Table("biz_sale", ID + """
            no VARCHAR(32) NOT NULL, customer_id BIGINT NOT NULL, biz_date DATE NOT NULL, status VARCHAR(16) NOT NULL,
            tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX', tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
            goods_amount DECIMAL(18,2) NOT NULL DEFAULT 0, freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            freight_alloc_method VARCHAR(20) NOT NULL DEFAULT 'BY_QTY', total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0, gross_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
            gross_margin DECIMAL(8,4) NOT NULL DEFAULT 0, remark VARCHAR(500),
            source_order_id BIGINT NULL, source_order_no VARCHAR(32), contact_name VARCHAR(64) NOT NULL DEFAULT '',
            contact_phone VARCHAR(32) NOT NULL DEFAULT '', delivery_address VARCHAR(255) NOT NULL DEFAULT '',
            """ + FULL + ", UNIQUE KEY uk_biz_sale_no (tenant_id, no), KEY idx_biz_sale_source (tenant_id, source_order_id)"),
        new Table("biz_sale_item", ID + """
            order_id BIGINT NOT NULL, source_order_item_id BIGINT NULL, product_id BIGINT NOT NULL,
            new_product_snapshot JSON NULL, qty DECIMAL(18,3) NOT NULL, price DECIMAL(18,4) NOT NULL,
            tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX', tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
            amount DECIMAL(18,2) NOT NULL DEFAULT 0, unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0,
            cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0, gross_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
            remark VARCHAR(500), seq INT NOT NULL DEFAULT 0,
            """ + AUDIT + LIVE + ", KEY idx_sale_item_order (tenant_id, order_id), KEY idx_sale_item_source (tenant_id, source_order_item_id)"),
        new Table("biz_sale_return", ID + """
            no VARCHAR(32) NOT NULL, sale_id BIGINT NOT NULL, sale_no VARCHAR(32) NOT NULL, customer_id BIGINT NOT NULL,
            biz_date DATE NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            return_cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0, need_refund TINYINT NOT NULL DEFAULT 0,
            refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0, refund_account_id BIGINT NULL, refund_receipt_id BIGINT NULL,
            remark VARCHAR(500),
            """ + FULL + ", UNIQUE KEY uk_biz_sale_return_no (tenant_id, no), KEY idx_sale_return_sale (tenant_id, sale_id)"),
        new Table("biz_sale_return_item", ID + """
            order_id BIGINT NOT NULL, sale_item_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
            qty DECIMAL(18,3) NOT NULL, price DECIMAL(18,4) NOT NULL, amount DECIMAL(18,2) NOT NULL,
            unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0, return_cost DECIMAL(18,2) NOT NULL DEFAULT 0,
            remark VARCHAR(500), seq INT NOT NULL DEFAULT 0,
            """ + AUDIT + LIVE + ", KEY idx_sale_return_item_order (tenant_id, order_id), KEY idx_sale_return_item_src (tenant_id, sale_item_id)"),
        new Table("st_stock", ID + """
            product_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, qty DECIMAL(18,3) NOT NULL DEFAULT 0,
            avg_cost DECIMAL(18,4) NOT NULL DEFAULT 0,
            """ + AUDIT + LIVE + ", UNIQUE KEY uk_st_stock (tenant_id, product_id, warehouse_id)"),
        new Table("st_stock_log", ID + """
            biz_type VARCHAR(20) NOT NULL, biz_no VARCHAR(32) NOT NULL DEFAULT '', biz_id BIGINT NULL,
            product_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, qty DECIMAL(18,3) NOT NULL,
            unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            partner_type VARCHAR(16) DEFAULT 'NONE', partner_id BIGINT NULL, balance_after DECIMAL(18,3),
            biz_date DATE NOT NULL, seq INT NOT NULL DEFAULT 0,
            """ + AUDIT + LIVE + ", KEY idx_stock_log_biz (tenant_id, biz_no), KEY idx_stock_log_product (tenant_id, product_id, biz_date)"),
        new Table("fin_ar_ap", ID + """
            party_type VARCHAR(8) NOT NULL, party_id BIGINT NOT NULL, source_type VARCHAR(16) NOT NULL,
            source_id BIGINT NULL, biz_date DATE NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            settled_amount DECIMAL(18,2) NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
            active_flag TINYINT GENERATED ALWAYS AS (IF(status = 'VOID', NULL, 1)) STORED,
            """ + AUDIT + LIVE + ", UNIQUE KEY uk_fin_ar_ap_source (tenant_id, source_type, source_id, active_flag)"
                + ", KEY idx_ar_ap_party (tenant_id, party_type, party_id, status)"
                + ", KEY idx_ar_ap_source (tenant_id, source_type, source_id)"),
        new Table("fin_receipt", ID + """
            no VARCHAR(32) NOT NULL, customer_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
            biz_date DATE NOT NULL, account VARCHAR(50), account_id BIGINT NULL, negative TINYINT NOT NULL DEFAULT 0,
            remark VARCHAR(500), status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
            """ + FULL + ", UNIQUE KEY uk_fin_receipt_no (tenant_id, no)"
                + ", KEY idx_fin_receipt_customer (tenant_id, customer_id, biz_date)"),
        new Table("fin_receipt_alloc", ID + """
            receipt_id BIGINT NOT NULL, ar_ap_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL,
            """ + AUDIT + LIVE + ", KEY idx_receipt_alloc_receipt (tenant_id, receipt_id)"),
        new Table("sys_sequence", ID + """
            biz_type VARCHAR(10) NOT NULL, seq_date CHAR(8) NOT NULL, current_value INT NOT NULL DEFAULT 0,
            create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id), UNIQUE KEY uk_sys_sequence (tenant_id, biz_type, seq_date)
            """)
    );
    private static final String SEQUENCE = "sys_sequence";

    private final String schema = System.getenv("ERP_AUDIT_MYSQL_DATABASE");
    private final String marker = "trade-return-audit-" + UUID.randomUUID();
    private final MysqlDataSource source;
    private final Map<String, Object> report;
    private final List<String> created = new ArrayList<>(), dropped = new ArrayList<>(), collisions = new ArrayList<>();
    private final List<String> cleanupErrors = new ArrayList<>();
    private Connection admin;
    private JdbcTemplate reader;

    TradeReturnAuditDatabase(RefundAuditDatabase.Contract contract, Map<String, Object> report) throws SQLException {
        // Reuse only the credential-validated factory, never the old prepare()/fixtures/suite.
        source = new RefundAuditDatabase(contract, new LinkedHashMap<>()).dataSource();
        this.report = report;
        report.put("created_tables", created);
        report.put("dropped_tables", dropped);
        report.put("preexisting_objects", collisions);
        report.put("cleanup_errors", cleanupErrors);
        report.put("cleanup_verified", false);
    }

    void prepare() throws SQLException {
        admin = source.getConnection();
        check(admin.getAutoCommit(), "observer/DDL connection must be autocommit");
        var version = admin.getMetaData().getDatabaseProductVersion();
        check("MySQL".equals(admin.getMetaData().getDatabaseProductName()) && version.startsWith("8.0."), "MySQL 8.0 required");
        try (Statement sql = admin.createStatement()) {
            try (ResultSet row = sql.executeQuery("SELECT DATABASE(), @@port, @@bind_address, @@global.local_infile")) {
                check(row.next() && schema.equals(row.getString(1)) && row.getInt(2) == 43306
                    && "127.0.0.1".equals(row.getString(3)) && row.getInt(4) == 0, "connected identity refused");
            }
            var dedicated = false;
            try (ResultSet rows = sql.executeQuery("SHOW GRANTS")) {
                while (rows.next()) {
                    String grant = rows.getString(1);
                    check(!grant.contains("WITH GRANT OPTION"), "grant option refused");
                    if (grant.startsWith("GRANT ALL PRIVILEGES ON `" + schema + "`.* TO ")) dedicated = true;
                    else check(grant.startsWith("GRANT USAGE ON *.* TO "), "only dedicated schema grants allowed");
                }
            }
            check(dedicated, "dedicated schema grant was not verified");
            report.put("mysql_version", version);
            report.put("connector_j", admin.getMetaData().getDriverVersion());
            report.put("host", "127.0.0.1");
            report.put("port", 43306);
            report.put("schema", "erp_audit_[REDACTED]");
            report.put("schema_only_grant_verified", true);
            sql.execute("SET SESSION lock_wait_timeout=6");
            for (Table table : TABLES) {
                if (!metadata(table.name).isEmpty()) collisions.add(table.name);
            }
            check(collisions.isEmpty(), "fixed fixture table/view exists; no CREATE/DROP performed");
            for (Table table : TABLES) {
                // No IF NOT EXISTS: never claim ownership of a name if CREATE loses a race.
                sql.execute("CREATE TABLE `" + table.name + "` (" + table.columns
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='" + marker + "'");
                created.add(table.name);
                check(owned(table.name), "created fixture does not have expected engine/ownership: " + table.name);
            }
        }
        reader = new JdbcTemplate(new SingleConnectionDataSource(admin, true));
    }

    private List<Metadata> metadata(String name) throws SQLException {
        List<Metadata> result = new ArrayList<>();
        try (PreparedStatement sql = admin.prepareStatement("SELECT ENGINE, TABLE_COMMENT FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND LOWER(table_name)=?")) {
            sql.setString(1, name);
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) result.add(new Metadata(rows.getString(1), rows.getString(2)));
            }
        }
        return result;
    }

    private boolean owned(String name) throws SQLException { return metadata(name).equals(List.of(new Metadata("InnoDB", marker))); }
    MysqlDataSource dataSource() { return source; }
    JdbcTemplate reader() { return reader; }

    Map<String, List<Map<String, Object>>> snapshot() { return snapshot(reader); }

    Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Table table : TABLES) {
            // Include all tenants and even soft deletes. Numbering's REQUIRES_NEW is asserted separately.
            if (!SEQUENCE.equals(table.name)) rows.put(table.name, jdbc.queryForList("SELECT * FROM `" + table.name + "` ORDER BY id"));
        }
        return rows;
    }

    @Override
    public void close() {
        if (admin != null) {
            try (Connection connection = admin; Statement sql = connection.createStatement()) {
                for (int i = created.size() - 1; i >= 0; i--) {
                    String table = created.get(i);
                    try {
                        check(owned(table), "ownership changed; refusing DROP");
                        sql.execute("DROP TABLE `" + table + "`");
                        check(metadata(table).isEmpty(), "fixture name remains after drop");
                        dropped.add(table);
                    } catch (SQLException | RuntimeException error) {
                        cleanupErrors.add(table + ": " + error.getClass().getSimpleName()
                            + (error instanceof SQLException e ? " #" + e.getErrorCode() : ""));
                    }
                }
            } catch (SQLException error) {
                cleanupErrors.add("closing fixture connection: SQL #" + error.getErrorCode());
            }
        }
        report.put("cleanup_verified", cleanupErrors.isEmpty() && dropped.size() == created.size());
    }

    static void check(boolean value, String text) { if (!value) throw new IllegalStateException(text); }
}
