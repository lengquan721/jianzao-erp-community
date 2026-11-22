#!/usr/bin/env python3
"""Pure offline guard tests; no DB, Maven, private file, existing runner main or output writes."""
import ast
from contextlib import ExitStack
import os
from pathlib import Path
import runpy
import socket
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
with patch.object(socket, 'socket', side_effect=AssertionError('network forbidden in offline guard tests')):
    RUN = runpy.run_path(str(HERE / 'stock-hotspot-run.py'))
BASE = RUN['BASE']


class Guards(unittest.TestCase):
    def test_python_syntax_without_pyc(self):
        for name in ('stock-hotspot-run.py', 'stock-hotspot-selftest.py'):
            ast.parse((HERE / name).read_text(encoding='utf-8'), filename=name)

    def test_original_ddl_extraction_and_indexes(self):
        raw = RUN['SCHEMA'].read_bytes()
        tables = RUN['extract_schema'](raw)
        self.assertEqual(set(tables), {'md_product', 'st_stock', 'st_stock_log'})
        for row in tables.values():
            self.assertIn(row['ddl'].encode('utf-8'), raw)
            self.assertEqual(row['original_ddl_sha256'], RUN['sha'](row['ddl'].encode('utf-8')))
        for before, after in (
            (b'KEY idx_stock_log_product (tenant_id, product_id, biz_date)', b'KEY removed_index (product_id)'),
            (b'UNIQUE KEY uk_st_stock (tenant_id, product_id, warehouse_id)', b'KEY removed_unique (product_id)'),
        ):
            with self.assertRaises(RUN['Refused']):
                RUN['extract_schema'](raw.replace(before, after))
        for extra in ('\nALTER TABLE st_stock ADD extra INT;', '\n' + tables['st_stock']['ddl']):
            with self.assertRaises(RUN['Refused']):
                RUN['extract_schema'](raw + extra.encode('utf-8'))

    def test_contract_mutations_no_connection(self):
        schema = 'erp_audit_offline_test'
        env = {'ERP_AUDIT_REAL_MYSQL': '1', 'ERP_AUDIT_MYSQL_HOST': '127.0.0.1', 'ERP_AUDIT_MYSQL_PORT': '43306',
               'ERP_AUDIT_MYSQL_DATABASE': schema, 'ERP_AUDIT_MYSQL_USERNAME': 'erp_audit_' + 'a' * 10,
               'ERP_AUDIT_MYSQL_PASSWORD': 'b' * 64, 'ERP_AUDIT_MYSQL_DRIVER': 'com.mysql.cj.jdbc.Driver',
               'ERP_AUDIT_MYSQL_JDBC_URL': 'jdbc:mysql://127.0.0.1:43306/' + schema
                   + '?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true',
               'JAVA_TOOL_OPTIONS': BASE['TOOL_OPTIONS']}
        with patch.dict(os.environ, env, clear=True):
            self.assertEqual(len(BASE['contract']()), 3)
        for key, wrong in (
            ('ERP_AUDIT_REAL_MYSQL', '0'), ('ERP_AUDIT_MYSQL_HOST', 'localhost'), ('ERP_AUDIT_MYSQL_PORT', '3306'),
            ('ERP_AUDIT_MYSQL_DATABASE', 'erp_live'), ('ERP_AUDIT_MYSQL_USERNAME', 'root'),
            ('ERP_AUDIT_MYSQL_PASSWORD', 'bad'), ('ERP_AUDIT_MYSQL_DRIVER', 'other.Driver'),
            ('ERP_AUDIT_MYSQL_JDBC_URL', env['ERP_AUDIT_MYSQL_JDBC_URL'] + '&allowMultiQueries=true'),
            ('http_proxy', 'http://127.0.0.1:10808'), ('CLASSPATH', 'injected'), ('JAVA_TOOL_OPTIONS', '-Xmx4g'),
        ):
            with patch.dict(os.environ, {**env, key: wrong}, clear=True), self.assertRaises(RUN['Refused']):
                BASE['contract']()
        for key in tuple(k for k in env if k.startswith('ERP_AUDIT_')):
            missing = dict(env); missing.pop(key)
            with patch.dict(os.environ, missing, clear=True), self.assertRaises(RUN['Refused']):
                BASE['contract']()

    def test_current_source_closure_no_prior_business_suites(self):
        sources = RUN['snapshot_sources']()
        self.assertTrue(all(p.is_relative_to(RUN['WORK']) for p in sources))
        names = {p.name for p in sources}
        self.assertIn('InventoryServiceImpl.java', names)
        self.assertIn('ErpStockLogMapper.java', names)
        self.assertIn('PlusTenantLineHandler.java', names)
        self.assertIn('MasterOptionSql.java', names)
        self.assertTrue({'StockHotspotMySql.java', 'StockHotspotFixture.java'} <= names)
        self.assertFalse({'TradeReturnMySql.java', 'RefundBusinessMySql.java', 'StockQueryMySql.java'} & names)
        protected = RUN['protected_artifacts']()
        for name in ('StockQueryMySql.java', 'TradeReturnMySql.java', 'run_trade_return_integration.py'):
            self.assertIn(HERE / name, protected)
        self.assertTrue(all(not p.name.endswith('-latest.json') or p.parent == RUN['round_audit'].REPORTS
                            for p in protected))
        self.assertTrue(all(not p.name.startswith(RUN['PREFIXES']) for p in protected))
        self.assertTrue(RUN['unchanged'](protected))


if __name__ == '__main__':
    # Any accidental process/network action during guards is an immediate failure. Reads only public candidate inputs.
    with ExitStack() as stack:
        stack.enter_context(patch.object(socket, 'socket', side_effect=AssertionError('network forbidden')))
        stack.enter_context(patch.object(subprocess, 'run', side_effect=AssertionError('subprocess forbidden')))
        unittest.main(verbosity=2)
