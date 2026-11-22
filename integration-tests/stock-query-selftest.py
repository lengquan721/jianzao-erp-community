#!/usr/bin/env python3
"""Offline stdlib tests. No runtime state, credentials, Maven, JDBC or private logs are read."""
import os
from pathlib import Path
import runpy
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
RUNNER = runpy.run_path(str(HERE / 'stock-query-run.py'))
BASE = RUNNER['BASE']


def synthetic_environment():
    schema = 'erp_audit_offline_selftest'
    return {'ERP_AUDIT_REAL_MYSQL': '1', 'ERP_AUDIT_MYSQL_HOST': '127.0.0.1', 'ERP_AUDIT_MYSQL_PORT': '43306',
            'ERP_AUDIT_MYSQL_DATABASE': schema, 'ERP_AUDIT_MYSQL_USERNAME': 'erp_audit_' + 'b' * 10,
            'ERP_AUDIT_MYSQL_PASSWORD': 'a' * 64, 'ERP_AUDIT_MYSQL_DRIVER': 'com.mysql.cj.jdbc.Driver',
            'ERP_AUDIT_MYSQL_JDBC_URL': 'jdbc:mysql://127.0.0.1:43306/' + schema
                + '?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true',
            'JAVA_TOOL_OPTIONS': BASE['TOOL_OPTIONS']}


class StockQuerySafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = RUNNER['SCHEMA'].read_bytes()

    def test_original_full_schema_and_indexes(self):
        tables = RUNNER['extract_schema'](self.raw)
        self.assertEqual(set(tables), {'st_stock', 'st_stock_log'})
        log = tables['st_stock_log']['ddl']
        self.assertIn('KEY idx_stock_log_product (tenant_id, product_id, biz_date)', log)
        self.assertIn('KEY idx_stock_log_biz (tenant_id, biz_no)', log)
        self.assertIn('balance_after DECIMAL(18,3)', log)
        self.assertEqual(tables['st_stock_log']['original_ddl_sha256'], RUNNER['sha'](log.encode('utf-8')))
        self.assertIn(log, self.raw.decode('utf-8-sig'))

    def test_missing_production_index_refused(self):
        raw = self.raw.replace(b'KEY idx_stock_log_product (tenant_id, product_id, biz_date)', b'KEY simplified (product_id)')
        with self.assertRaises(RUNNER['Refused']): RUNNER['extract_schema'](raw)

    def test_duplicate_create_refused(self):
        ddl = RUNNER['extract_schema'](self.raw)['st_stock_log']['ddl'].encode('utf-8')
        with self.assertRaises(RUNNER['Refused']): RUNNER['extract_schema'](self.raw + b'\n' + ddl)

    def test_later_alter_refused(self):
        with self.assertRaises(RUNNER['Refused']):
            RUNNER['extract_schema'](self.raw + b'\nALTER TABLE st_stock_log DROP INDEX idx_stock_log_product;')

    def test_synthetic_exec_contract_accepted(self):
        with patch.dict(os.environ, synthetic_environment(), clear=True): self.assertEqual(len(BASE['contract']()), 3)

    def test_endpoint_schema_url_proxy_jvm_drift_refused(self):
        cases = [('ERP_AUDIT_REAL_MYSQL', '0'), ('ERP_AUDIT_MYSQL_HOST', 'localhost'),
                 ('ERP_AUDIT_MYSQL_PORT', '3306'), ('ERP_AUDIT_MYSQL_DATABASE', 'business'),
                 ('ERP_AUDIT_MYSQL_JDBC_URL', 'jdbc:mysql://127.0.0.1:3306/business'),
                 ('HTTPS_PROXY', 'http://invalid'), ('JAVA_TOOL_OPTIONS', '-Xmx2g'), ('CLASSPATH', '.')]
        for name, value in cases:
            with self.subTest(variable=name):
                environment = synthetic_environment(); environment[name] = value
                with patch.dict(os.environ, environment, clear=True):
                    with self.assertRaises(RUNNER['Refused']): BASE['contract']()

    def test_source_closure_includes_real_mapper_and_tenant_handler(self):
        sources = RUNNER['snapshot_sources']()
        names = {path.name for path in sources}
        self.assertTrue({'StockQueryMySql.java', 'StockQueryCapture.java', 'StockQueryDatabase.java',
                         'ErpStockLogMapper.java', 'ErpStockMapper.java', 'PlusTenantLineHandler.java'} <= names)
        self.assertFalse(any('target' in path.parts for path in sources))


if __name__ == '__main__':
    unittest.main(verbosity=2)
