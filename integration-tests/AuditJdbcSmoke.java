import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Real MySQL8 contract, run with env-only credentials; never launches the business application. */
public class AuditJdbcSmoke {
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            rows.next();
            return rows.getLong(1);
        }
    }
    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
    public static void main(String[] args) throws Exception {
        String host = required("ERP_AUDIT_MYSQL_HOST");
        String port = required("ERP_AUDIT_MYSQL_PORT");
        String database = required("ERP_AUDIT_MYSQL_DATABASE");
        String user = required("ERP_AUDIT_MYSQL_USERNAME");
        String password = required("ERP_AUDIT_MYSQL_PASSWORD");
        String jdbc = required("ERP_AUDIT_MYSQL_JDBC_URL");
        require(host.equals("127.0.0.1") && port.equals("43306"), "Not the isolated endpoint");
        require(database.matches("erp_audit_[a-z0-9][a-z0-9_]{0,44}"), "Not an audit schema");
        String expected = "jdbc:mysql://127.0.0.1:43306/" + database
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        require(jdbc.equals(expected), "JDBC URL mismatch; refusing connection");
        require(required("ERP_AUDIT_REAL_MYSQL").equals("1"), "Explicit real database opt-in missing");
        Class.forName("com.mysql.cj.jdbc.Driver");
        String table = "erp_audit_jdbc_" + UUID.randomUUID().toString().replace("-", "");
        String verifiedVersion;
        String verifiedDriver;
        try (Connection writer = DriverManager.getConnection(jdbc, user, password);
             Connection observer = DriverManager.getConnection(jdbc, user, password)) {
            require(writer.getMetaData().getDatabaseProductName().equals("MySQL"), "Not MySQL");
            String version = writer.getMetaData().getDatabaseProductVersion();
            require(version.startsWith("8.0."), "MySQL8.0 required");
            verifiedVersion = version;
            verifiedDriver = writer.getMetaData().getDriverVersion();
            try (Statement statement = writer.createStatement()) {
                statement.execute("CREATE TABLE `" + table + "` (id INT PRIMARY KEY, note VARCHAR(80)) ENGINE=InnoDB");
            }
            try {
                writer.setAutoCommit(false);
                writer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try (PreparedStatement insert = writer.prepareStatement("INSERT INTO `" + table + "` VALUES (?,?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, "事务回滚验证");
                    insert.executeUpdate();
                    require(count(observer, table) == 0, "Uncommitted row leaked");
                    writer.rollback();
                    require(count(observer, table) == 0, "Rollback left rows behind");
                    insert.setInt(1, 2);
                    insert.setString(2, "MySQL8 JDBC ✓");
                    insert.executeUpdate();
                    writer.commit();
                    require(count(observer, table) == 1, "Commit not visible");
                }
                try (Statement statement = observer.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT note FROM `" + table + "` WHERE id=2")) {
                    rows.next();
                    require(rows.getString(1).equals("MySQL8 JDBC ✓"), "UTF8MB4 roundtrip failed");
                }
            } finally {
                writer.rollback();
                writer.setAutoCommit(true);
                try (Statement statement = writer.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS `" + table + "`");
                }
            }
        }
        String report = System.getenv("ERP_AUDIT_JDBC_REPORT");
        if (report != null && !report.isBlank()) {
            String result = "{\n  \"passed\": true,\n  \"host\": \"127.0.0.1\",\n  \"port\": 43306,"
                + "\n  \"database\": " + json(database) + ",\n  \"mysql_version\": " + json(verifiedVersion)
                + ",\n  \"driver\": " + json(verifiedDriver)
                + ",\n  \"java_runtime\": " + json(System.getProperty("java.runtime.version"))
                + ",\n  \"checks\": [\"real_mysql8\", \"two_connections\", \"uncommitted_isolation\","
                + " \"rollback\", \"commit\", \"utf8mb4\", \"probe_table_removed\"],"
                + "\n  \"checked_at_utc\": " + json(Instant.now().toString()) + "\n}\n";
            Files.writeString(Path.of(report), result, StandardCharsets.UTF_8);
        }
        System.out.println("PASS: MySQL " + verifiedVersion + "; Connector/J " + verifiedDriver
            + "; two connections; isolation; rollback; commit; UTF8MB4; probe removed");
    }
}
