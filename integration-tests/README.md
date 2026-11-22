# Real MySQL8 isolation-test contract (no business configuration)

This directory contains new standalone environment/driver acceptance helpers only. It does **not**
start Spring Boot, import production schemas, use H2/SQLite, or claim that ERP scenarios passed.

## Windows: approved isolated endpoint

From workspace root (`D:/rosoft2023/DEAV-AI开发`, Git Bash):

```bash
ENV_TOOL=audit-tools/erp-remediation/environment/isolated_mysql.py
python.exe "$ENV_TOOL" start          # reuse this round's owned instance, otherwise create one
python.exe "$ENV_TOOL" status         # endpoint/schema/PID; never credentials
python.exe "$ENV_TOOL" verify         # two real connections, transactions, rollback, constraints
python.exe "$ENV_TOOL" exec -- bash audit-tools/erp-remediation/environment/jdbc-smoke.sh
# Run another bounded, standalone integration test without printing passwords:
python.exe "$ENV_TOOL" exec -- python.exe path/to/your_isolated_test.py
python.exe "$ENV_TOOL" stop           # retain own data and credentials for restart
python.exe "$ENV_TOOL" start          # same dedicated schema/data, new recorded process identity
python.exe "$ENV_TOOL" destroy        # final cleanup; removes owned data and credentials
# destroy --purge-binaries additionally removes cached portable binaries/downloads/Python driver.
```

Only the **literal** `127.0.0.1:43306` endpoint is accepted. Schemas must match
`erp_audit_[a-z0-9][a-z0-9_]{0,44}`. The tool generates a random schema/user/password by default.
Never read or echo `.runtime/state.json`, `bootstrap.sql`, environment dumps or private logs.
Credentials are injected into the child process environment (not CLI arguments). Known secrets
are redacted from captured stdout/stderr and the private captured-output file is removed.

Injected contract:

| Variable | Meaning |
|---|---|
| `ERP_AUDIT_MYSQL_HOST` / `ERP_AUDIT_MYSQL_PORT` | literal `127.0.0.1` / `43306` |
| `ERP_AUDIT_MYSQL_DATABASE` | random dedicated prefixed schema |
| `ERP_AUDIT_MYSQL_USERNAME` / `ERP_AUDIT_MYSQL_PASSWORD` | dedicated schema-only user; secret |
| `ERP_AUDIT_MYSQL_JDBC_URL` | validated MySQL URL, no credentials included |
| `ERP_AUDIT_MYSQL_DRIVER` | `com.mysql.cj.jdbc.Driver` |
| `ERP_AUDIT_REAL_MYSQL` | `1`, explicit opt-in |

A standalone Java test is supplied as `AuditJdbcSmoke.java`; it revalidates the whole URL and
schema before connecting, creates only random prefixed InnoDB probe tables, and drops them in
`finally`. It checks real MySQL8 product/version, two connections, uncommitted invisibility,
rollback, commit visibility and UTF8MB4. Windows uses the cached official Connector/J 9.7.0 jar
only (no Maven settings read); override its location using `ERP_AUDIT_JDBC_JAR=/path/to/driver.jar`.
The source-file runner sources the workspace `build-env.sh`, immediately discards all proxy
variables and uses JBR21 with the approved low-memory `JAVA_TOOL_OPTIONS`.

## Requirements for subsequent business integration tests

* Use an explicit standalone integration-test profile or connection factory; validate the host,
  port and database **before any driver connection**. Do not let tests fall back to application
  dev/prod config, config centers, existing Redis, or inherited business environment variables.
* Map only the above dedicated credentials into the particular test datasource. The wrapper
  deliberately does **not** inject generic Spring datasource defaults or boot the application.
* Bootstrap only reviewed minimal fixtures/migrations into the dedicated schema. No business
  database dump, proxy, production address or existing runtime configuration is permitted.
* Tests may perform DDL only on this schema, and must clean created objects or destroy the
  whole dedicated instance after the suite. MySQL DDL implicitly commits: use DML transactions
  to test rollback, never assume a framework test transaction will roll back a CREATE TABLE.
* No Redis is supplied on this host: WSL Ubuntu has no Redis executable/package. Do not pretend
  a fakeRedis/mock is the real Redis acceptance result. If a later case truly needs Redis,
  provision a separately approved ephemeral real service on `127.0.0.1:46379` with independent
  credentials/data/PID/cleanup; neither standard port nor existing Redis is allowed.
* `exec` is a credential-delivery helper, **not an OS/network sandbox**. Invoke only reviewed
  bounded tests that follow this contract; never run a whole backend with default profiles.

## Linux/CI option

See `audit-tools/erp-remediation/environment/ci-compose.yaml` and `ci_mysql.py` in the workspace.
The official real MySQL8 container publishes only `127.0.0.1:43306`, uses a dedicated project and
anonymous disposable volume, and must be cleaned with project-specific `down -v`. No container
runtime is installed by these tools. CI is a supplied alternative, not a locally executed claim.
