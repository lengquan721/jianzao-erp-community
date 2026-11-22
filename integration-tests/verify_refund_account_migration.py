#!/usr/bin/env python3
"""Only the refund-account patch; real MySQL8 through isolated_mysql.py exec, never app config."""
import argparse
import datetime as dt
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sys

HERE = Path(__file__).absolute().parent
sys.path.insert(0, str(HERE))
try:
    import audit_round as round_audit
finally:
    sys.path.pop(0)
paths = round_audit.paths
ROOT = paths.WORK
PATCH = ROOT / 'deploy/mysql/patches/V20261120_01__sale_return_refund_account_id.sql'
SCHEMA = ROOT / 'deploy/mysql/init/04-erp-schema.sql'
SHA256 = '2e2ebfb2330161943f9a392c3f46fc679b1246d8c4a129654d74a2a8dfcf41fa'
VERSION = 'V20261120_01'
PROCEDURE = 'erp_patch_v20261120_01_refund_account'
COMMENT = '草稿及确认时选定的退款结算账户'
DATABASE_RE = re.compile(r'erp_audit_[a-z0-9][a-z0-9_]{0,44}\Z')


class Refusal(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Refusal(message)


def statements(text):
    """Reviewed mysql DELIMITER subset, not a general SQL/multi-statement executor."""
    delimiter, pending, result = ';', [], []
    for line in text.splitlines():
        if line.lstrip().startswith('--') or not line.strip():
            continue
        directive = re.fullmatch(r'\s*DELIMITER\s+(\S+)\s*', line, re.I)
        if directive:
            require(not pending, 'delimiter with pending SQL')
            delimiter = directive.group(1)
            continue
        pending.append(line)
        if line.rstrip().endswith(delimiter):
            combined = '\n'.join(pending).rstrip()
            result.append(combined[:-len(delimiter)].strip())
            pending = []
    require(not pending and delimiter == ';', 'unterminated patch')
    return result


def load_reviewed_inputs():
    raw = paths.scoped(ROOT, PATCH, kind='file').read_bytes()
    require(hashlib.sha256(raw).hexdigest() == SHA256, 'SQL SHA256 mismatch')
    require(paths.scoped(ROOT, PATCH.with_suffix('.sql.sha256'), kind='file').read_text(encoding='utf-8').strip()
            == SHA256 + '  ' + PATCH.name, 'checksum sidecar mismatch')
    sql = raw.decode('utf-8')
    require('\r' not in sql and not sql.startswith('\ufeff'), 'SQL must be UTF8 LF without BOM')
    parts = statements(sql)
    require(len(parts) == 4 and parts[0] == 'SET NAMES utf8mb4', 'unexpected statement shape')
    require(parts[1].startswith('CREATE PROCEDURE `' + PROCEDURE + '`()'), 'unexpected routine')
    require(parts[2] == 'CALL `' + PROCEDURE + '`()', 'unexpected CALL')
    require(parts[3] == 'DROP PROCEDURE `' + PROCEDURE + '`', 'unexpected DROP')
    require(not re.search(r'^\s*(UPDATE|INSERT|DELETE|REPLACE|TRUNCATE|USE|DROP TABLE)\b',
                          sql, re.I | re.M), 'patch contains out-of-scope DML/DDL')
    require(sql.count("'biz_sale_return'") == 2 and sql.count('`biz_sale_return`') == 1
            and sql.count('`' + PROCEDURE + '`') == 3, 'identifier mapping changed')
    require('ALGORITHM=INSTANT' in sql and 'SQL SECURITY INVOKER' in sql, 'patch contract changed')
    schema = paths.scoped(ROOT, SCHEMA, kind='file').read_text(encoding='utf-8')
    match = re.search(r'^CREATE TABLE IF NOT EXISTS biz_sale_return \(\n.*?^\) ENGINE=InnoDB[^\n]*;',
                      schema, re.M | re.S)
    require(match is not None, 'single sale-return CREATE TABLE not found')
    create = match.group(0)
    refund = [line for line in create.splitlines() if re.match(r'\s*refund_account_id\s', line)]
    require(len(refund) == 1 and 'BIGINT DEFAULT NULL' in refund[0] and COMMENT in refund[0],
            'mainline new-schema refund column mismatch')
    legacy = '\n'.join(line for line in create.splitlines() if line not in refund)
    return sql, create, legacy


def endpoint(host, port, database, opt_in):
    require(host == '127.0.0.1' and port == '43306', 'not the literal isolated endpoint')
    require(isinstance(database, str) and DATABASE_RE.fullmatch(database) is not None,
            'not a dedicated audit schema')
    require(opt_in == '1', 'real-MySQL explicit opt-in missing')


def endpoint_negative_guards():
    candidates = [
        ('localhost', '43306', 'erp_audit_ok', '1'),
        ('example.com', '43306', 'erp_audit_ok', '1'),
        ('127.0.0.2', '43306', 'erp_audit_ok', '1'),
        ('127.0.0.1', '3306', 'erp_audit_ok', '1'),
        ('127.0.0.1', '43306', 'mysql', '1'),
        ('127.0.0.1', '43306', 'erp_audit_%', '1'),
        ('127.0.0.1', '43306', 'erp_audit_ok;DROP DATABASE mysql', '1'),
        ('127.0.0.1', '43306', 'erp_audit_ok', '0'),
    ]
    for candidate in candidates:
        try:
            endpoint(*candidate)
        except Refusal:
            continue
        raise Refusal('unsafe endpoint accepted')
    return len(candidates)


def digest(snapshot):
    raw = json.dumps(snapshot, ensure_ascii=False, default=str, sort_keys=True,
                     separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(raw).hexdigest()


def safe_error(error):
    # Never echo driver error strings, SQL, credentials, environment, or raw schema definitions.
    code = error.args[0] if error.args and type(error.args[0]) is int else None
    return {'class': type(error).__name__, 'mysql_code': code}


class Acceptance:
    def __init__(self, connection, sql, report):
        self.conn, self.sql, self.report = connection, sql, report
        self.prefix = 'erp_audit_refund_' + secrets.token_hex(6)
        self.tables, self.views, self.routines = set(), set(), set()
        self.patch_runs = 0
        report['object_prefix'] = self.prefix
        report['snapshots'] = {}
        report['rejected_definitions'] = []

    def check(self, value, label):
        self.report['checks'][label] = bool(value)
        require(value, label)

    def identifier(self, value):
        require(re.fullmatch(re.escape(self.prefix) + r'_[a-z0-9_]+', value) is not None
                and len(value) <= 64, 'object is outside this run ownership prefix')
        return '`' + value + '`'

    def execute(self, sql, params=None):
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
            while cur.nextset():
                pass
            return rows

    def objects(self, name, routine=False):
        self.identifier(name)
        if routine:
            return self.execute('SELECT COUNT(*) FROM information_schema.ROUTINES '
                                'WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME=%s', (name,))[0][0]
        return self.execute('SELECT COUNT(*) FROM information_schema.TABLES '
                            'WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s', (name,))[0][0]

    def create(self, name, source):
        quoted = self.identifier(name)
        require(self.objects(name) == 0, 'refusing existing table/view')
        ddl = source.replace('CREATE TABLE IF NOT EXISTS biz_sale_return (', 'CREATE TABLE ' + quoted + ' (', 1)
        require('biz_sale_return (' not in ddl and ddl.startswith('CREATE TABLE ' + quoted),
                'fixture table mapping failed')
        self.execute(ddl)
        self.tables.add(name)  # Never DROP an object whose CREATE did not succeed here.

    def seed(self, table, with_accounts=False):
        for index, status in enumerate(('DRAFT', 'CONFIRMED', 'VOID')):
            row = {
                'id': 1001 + index, 'tenant_id': 'audit-' + str(index), 'no': 'XT-AUDIT-' + str(index),
                'sale_id': 201 + index, 'sale_no': 'XS-SNAPSHOT-' + str(index), 'customer_id': 301 + index,
                'biz_date': dt.date(2020, 1, 2 + index), 'status': status,
                'total_amount': Decimal('123.45') + index, 'return_cost_amount': Decimal('45.67'),
                'need_refund': index % 2, 'refund_amount': Decimal('89.01'),
                'refund_receipt_id': None if index == 0 else 5001 + index,
                'remark': '历史退款快照-' + status, 'confirmed_by': None if index == 0 else 601,
                'confirmed_at': None if index == 0 else dt.datetime(2020, 1, 3, 4, 5, 6),
                'voided_by': 701 if index == 2 else None,
                'voided_at': dt.datetime(2020, 1, 4, 5, 6, 7) if index == 2 else None,
                'withdrawn_by': None, 'withdrawn_at': None, 'create_dept': 801, 'create_by': 901,
                'create_time': dt.datetime(2019, 12, 31, 23, 58, 57), 'update_by': 902,
                'update_time': dt.datetime(2020, 1, 1, 1, 2, 3), 'del_flag': '1' if index == 2 else '0',
            }
            if with_accounts:
                row['refund_account_id'] = (None, 7007, 9007199254740993)[index]
            columns = ','.join('`' + name + '`' for name in row)
            placeholders = ','.join(['%s'] * len(row))
            self.execute('INSERT INTO ' + self.identifier(table) + ' (' + columns + ') VALUES ('
                         + placeholders + ')', tuple(row.values()))

    def snapshot(self, table, columns=None):
        if columns is not None:
            require(all(re.fullmatch(r'[a-z][a-z0-9_]*', name) for name in columns), 'unsafe column')
        projection = '*' if columns is None else ','.join('`' + name + '`' for name in columns)
        with self.conn.cursor() as cur:
            cur.execute('SELECT ' + projection + ' FROM ' + self.identifier(table) + ' ORDER BY id')
            return {'columns': [field[0] for field in cur.description], 'rows': cur.fetchall()}

    def definition(self, table):
        return self.execute('SHOW CREATE TABLE ' + self.identifier(table))[0][1]

    def metadata(self, table):
        return self.execute('SELECT COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, COLUMN_TYPE, '
                            'IS_NULLABLE, COLUMN_DEFAULT, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT '
                            'FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() '
                            'AND TABLE_NAME=%s ORDER BY ORDINAL_POSITION', (table,))

    def properties(self, table, label):
        rows = [row for row in self.metadata(table) if row[0] == 'refund_account_id']
        self.check(len(rows) == 1, label + '_exactly_one_column')
        row = rows[0]
        self.check(row[2] == 'bigint' and 'unsigned' not in row[3].lower()
                   and 'zerofill' not in row[3].lower() and row[4] == 'YES' and row[5] is None
                   and row[6] == '' and row[7] == '' and row[8] == COMMENT,
                   label + '_signed_bigint_nullable_default_null_ordinary_comment')

    def patch(self, table, expected_rejection=None):
        self.identifier(table)
        proc = self.prefix + '_proc'
        self.identifier(proc)
        require(self.objects(proc, routine=True) == 0, 'refusing existing routine')
        rendered = (self.sql.replace("'biz_sale_return'", "'" + table + "'")
                    .replace('`biz_sale_return`', self.identifier(table))
                    .replace('`' + PROCEDURE + '`', self.identifier(proc)))
        require("'biz_sale_return'" not in rendered and '`biz_sale_return`' not in rendered
                and '`' + PROCEDURE + '`' not in rendered, 'patch mapping incomplete')
        parts = statements(rendered)
        previous = self.execute('SELECT @@SESSION.lock_wait_timeout')[0][0]
        self.patch_runs += 1
        caught = None
        try:
            self.execute(parts[0])
            self.execute(parts[1])
            self.routines.add(proc)
            try:
                self.execute(parts[2])
            except Exception as error:
                caught = error
            if caught is None:
                self.execute(parts[3])
                self.routines.remove(proc)
        finally:
            restored = self.execute('SELECT @@SESSION.lock_wait_timeout')[0][0]
            self.check(restored == previous, 'run_' + str(self.patch_runs) + '_lock_wait_restored')
            if proc in self.routines:
                self.execute('DROP PROCEDURE ' + self.identifier(proc))
                self.routines.remove(proc)
        if expected_rejection is not None:
            valid = (caught is not None and len(caught.args) >= 2 and caught.args[0] == 1644
                     and '[V20261120_01] ' + expected_rejection in caught.args[1])
            self.check(valid, table.removeprefix(self.prefix + '_') + '_rejected_1644')
            self.report['rejected_definitions'].append({
                'case': table.removeprefix(self.prefix + '_'), 'mysql_code': 1644,
                'sqlstate_declared_by_patch': '45000',
            })
        elif caught is not None:
            raise caught
        return caught

    def run(self, new_schema, legacy_schema):
        legacy = self.prefix + '_legacy'
        self.create(legacy, legacy_schema)
        self.seed(legacy)
        before = self.snapshot(legacy)
        before_meta = self.metadata(legacy)
        self.check(len(before['rows']) == 3 and 'refund_account_id' not in before['columns'],
                   'legacy_three_persisted_rows_without_column')
        for iteration in (1, 2):
            self.patch(legacy)
            self.properties(legacy, 'legacy_' + str(iteration))
            after = self.snapshot(legacy, before['columns'])
            self.check(before == after, 'legacy_' + str(iteration) + '_every_original_value_unchanged')
            self.check(self.execute('SELECT COUNT(*) FROM ' + self.identifier(legacy)
                                    + ' WHERE refund_account_id IS NULL')[0][0] == 3,
                       'legacy_' + str(iteration) + '_all_historical_accounts_null_no_backfill')
            self.check(tuple(row for row in self.metadata(legacy) if row[0] != 'refund_account_id') == before_meta,
                       'legacy_' + str(iteration) + '_all_original_column_attributes_unchanged')
        self.report['snapshots']['legacy'] = {
            'rows': 3, 'before_original_columns_sha256': digest(before),
            'after_two_runs_original_columns_sha256': digest(after), 'historical_refund_accounts_null': 3,
        }

        fresh = self.prefix + '_new'
        self.create(fresh, new_schema)
        self.seed(fresh, with_accounts=True)
        before = self.snapshot(fresh)
        before_ddl, before_meta = self.definition(fresh), self.metadata(fresh)
        for iteration in (1, 2):
            self.patch(fresh)
            self.properties(fresh, 'new_' + str(iteration))
            after = self.snapshot(fresh)
            self.check(before == after, 'new_' + str(iteration) + '_all_rows_and_accounts_unchanged')
            self.check(before_ddl == self.definition(fresh) and before_meta == self.metadata(fresh),
                       'new_' + str(iteration) + '_schema_exact_noop')
        self.report['snapshots']['new'] = {
            'rows': 3, 'before_all_columns_sha256': digest(before),
            'after_two_runs_all_columns_sha256': digest(after),
            'retained_account_examples': [None, 7007, 9007199254740993],
        }

        # Each rejection owns an independent table with persisted fixture rows; no forced conversion.
        cases = {
            'varchar': 'VARCHAR(30) NULL DEFAULT NULL',
            'int': 'INT NULL DEFAULT NULL',
            'unsigned': 'BIGINT UNSIGNED NULL DEFAULT NULL',
            'zerofill': 'BIGINT ZEROFILL NULL DEFAULT NULL',
            'notnull': 'BIGINT NOT NULL DEFAULT 0',
            'default_nonnull': 'BIGINT NULL DEFAULT 7',
            'generated_stored': 'BIGINT GENERATED ALWAYS AS (sale_id + 1) STORED',
            'generated_virtual': 'BIGINT GENERATED ALWAYS AS (sale_id + 1) VIRTUAL',
            'invisible': 'BIGINT NULL DEFAULT NULL INVISIBLE',
        }
        for case, column in cases.items():
            table = self.prefix + '_' + case
            source = legacy_schema.replace('  refund_receipt_id BIGINT',
                                           '  refund_account_id ' + column + ',\n  refund_receipt_id BIGINT', 1)
            require(source != legacy_schema, 'negative fixture mapping failed')
            self.create(table, source)
            self.seed(table)
            before, ddl, meta = self.snapshot(table), self.definition(table), self.metadata(table)
            self.patch(table, 'incompatible refund_account_id')
            self.check(before == self.snapshot(table), case + '_rejection_preserved_every_row')
            self.check(ddl == self.definition(table) and meta == self.metadata(table),
                       case + '_rejection_preserved_schema')

        absent = self.prefix + '_missing'
        self.check(self.objects(absent) == 0, 'missing_fixture_absent')
        self.patch(absent, 'expected existing InnoDB biz_sale_return')
        self.check(self.objects(absent) == 0, 'missing_table_not_created')

        myisam = self.prefix + '_myisam'
        self.create(myisam, legacy_schema.replace('ENGINE=InnoDB', 'ENGINE=MyISAM'))
        self.seed(myisam)
        before, ddl = self.snapshot(myisam), self.definition(myisam)
        self.patch(myisam, 'expected existing InnoDB biz_sale_return')
        self.check(before == self.snapshot(myisam) and ddl == self.definition(myisam),
                   'non_innodb_rejected_without_changes')

        view = self.prefix + '_view'
        require(self.objects(view) == 0, 'refusing existing view')
        self.execute('CREATE VIEW ' + self.identifier(view) + ' AS SELECT * FROM ' + self.identifier(legacy))
        self.views.add(view)
        before = self.snapshot(view)
        self.patch(view, 'expected existing InnoDB biz_sale_return')
        self.check(before == self.snapshot(view), 'view_rejected_without_changes')
        self.report['patch_executions'] = self.patch_runs

    def cleanup(self):
        errors = []
        for kind, owned in (('PROCEDURE', self.routines), ('VIEW', self.views), ('TABLE', self.tables)):
            for name in sorted(tuple(owned)):
                try:
                    self.execute('DROP ' + kind + ' ' + self.identifier(name))
                    owned.remove(name)
                except Exception as error:
                    errors.append({'kind': kind, 'name': name, **safe_error(error)})
        self.report['cleanup_errors'] = errors
        tables = self.execute('SELECT TABLE_NAME FROM information_schema.TABLES '
                              'WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,%s)=%s',
                              (len(self.prefix), self.prefix))
        routines = self.execute('SELECT ROUTINE_NAME FROM information_schema.ROUTINES '
                                'WHERE ROUTINE_SCHEMA=DATABASE() AND LEFT(ROUTINE_NAME,%s)=%s',
                                (len(self.prefix), self.prefix))
        self.check(not errors and not tables and not routines, 'all_run_owned_objects_removed')
        self.report['remaining_run_owned_objects'] = 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--static', action='store_true', help='no driver import or database connection')
    args = parser.parse_args()
    try:
        sql, new_schema, legacy_schema = load_reviewed_inputs()
        guard_count = endpoint_negative_guards()
    except Exception as error:
        print(json.dumps({'passed': False, 'stage': 'static', 'error': safe_error(error)}))
        return 1
    if args.static:
        print(json.dumps({'passed': True, 'mode': 'static_only_no_database', 'version': VERSION,
                          'sql_sha256': SHA256, 'parsed_statements': 4,
                          'invalid_endpoint_cases_rejected_without_connecting': guard_count}, indent=2))
        return 0

    report = {
        'version': VERSION, 'sql_sha256': SHA256, 'passed': False, 'predecessor_sha256': {},
        'checked_at_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
        'scope': 'refund_account_id only; no deployment, application config, Spring, Redis or Maven',
        'execution': 'reviewed SQL with only table/routine identifiers mapped to random owned prefixes',
        'checks': {'static_checksum_and_statement_shape': True,
                   'endpoint_negative_guards_before_connect': guard_count == 8},
        'not_verified': ['production rollout', 'full historical migration chain',
                         'other MySQL8 patch versions', 'large tables / MDL contention / concurrent DDL',
                         'ERP application workflows or full backend startup', 'crash/interrupt recovery'],
    }
    conn, suite = None, None
    success = False
    try:
        # No read_default_file/default_group, application YAML, dotenv, JDBC URL or fallback endpoint.
        paths.require_exec_round(os.environ)
        host = os.environ.get('ERP_AUDIT_MYSQL_HOST')
        port = os.environ.get('ERP_AUDIT_MYSQL_PORT')
        database = os.environ.get('ERP_AUDIT_MYSQL_DATABASE')
        endpoint(host, port, database, os.environ.get('ERP_AUDIT_REAL_MYSQL'))
        user = os.environ.get('ERP_AUDIT_MYSQL_USERNAME', '')
        password = os.environ.get('ERP_AUDIT_MYSQL_PASSWORD', '')
        require(re.fullmatch(r'erp_audit_[a-z0-9]+', user) is not None and bool(password),
                'dedicated wrapper credentials missing')
        import pymysql  # Private wrapper PYTHONPATH only; no pip/global package changes here.
        conn = pymysql.connect(host=host, port=int(port), database=database, user=user, password=password,
                               charset='utf8mb4', autocommit=True, connect_timeout=3, read_timeout=20,
                               write_timeout=10, local_infile=False)
        suite = Acceptance(conn, sql, report)
        identity = suite.execute('SELECT VERSION(), DATABASE(), @@port, @@bind_address, @@version_comment')[0]
        suite.check(identity[0].startswith('8.0.') and 'mariadb' not in identity[0].lower()
                    and identity[1] == database and identity[2] == 43306 and identity[3] == '127.0.0.1'
                    and 'mysql' in identity[4].lower(), 'real_mysql8_identity')
        report.update({'host': host, 'port': int(port), 'database': database, 'mysql_version': identity[0]})
        suite.execute("SET SESSION time_zone='+00:00'")
        suite.run(new_schema, legacy_schema)
        success = True
    except Exception as error:
        report['error'] = safe_error(error)
    finally:
        if suite is not None:
            try:
                suite.cleanup()
            except Exception as error:
                report['cleanup_error'] = safe_error(error)
                success = False
        if conn is not None:
            conn.close()
    report['passed'] = success and all(report['checks'].values())
    round_audit.publish('refund-account-migration', report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
