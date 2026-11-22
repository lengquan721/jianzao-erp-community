#!/usr/bin/env python3
"""Bounded stock Mapper milestone. No Maven, production configuration or mutable class output."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile
from xml.etree import ElementTree as ET

HERE = Path(__file__).resolve().parent
WORK = HERE.parent
SCHEMA = WORK / 'deploy/mysql/init/04-erp-schema.sql'
# Pure reviewed helpers only: neither existing suite/main/report is executed.
SHARED = runpy.run_path(str(HERE / 'run_trade_return_integration.py'))
BASE = SHARED['shared']
need, Refused = BASE['need'], BASE['Refused']
sha, digest = SHARED['sha'], SHARED['digest']
round_audit, paths = SHARED['round_audit'], SHARED['paths']
STAGE = 'stock-query'
WRAPPER = (HERE / 'stock-query-run.sh').relative_to(paths.ROOT).as_posix()
COMMAND = ('python.exe -B ' + (paths.ENVIRONMENT / 'isolated_mysql.py').relative_to(paths.ROOT).as_posix()
           + ' exec -- bash ' + WRAPPER)
NEW_PREFIXES = ('stock-query-', 'StockQuery')
JAVA_SOURCES = ('StockQueryMySql.java', 'StockQueryDatabase.java', 'StockQueryCapture.java', 'RefundAuditDatabase.java')
TABLE_NAMES = ('st_stock', 'st_stock_log')
UNTESTED = ['HTTP/application/controller/service end-to-end', 'p95/percentiles/throughput/capacity',
            'production cardinality/skew/cache or hardware', 'concurrent load/lock contention/deadlocks',
            'replay recalculation writes and service transaction orchestration',
            'full schema migration', 'EXPLAIN ANALYZE (deliberately not run)']


def extract_schema(raw):
    """Keep original column/key declarations byte-for-byte; only fixed CREATE statements are accepted."""
    text = raw.decode('utf-8-sig')
    result = {}
    for name in TABLE_NAMES:
        pattern = (r'\bCREATE TABLE IF NOT EXISTS ' + re.escape(name)
                   + r' \([\s\S]*?\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=\'[^\'\r\n]*\';')
        hits = re.findall(pattern, text)
        need(len(hits) == 1, 'expected exactly one original CREATE for ' + name)
        ddl = hits[0]
        need(ddl.count(';') == 1 and not re.search(r'\b(?:ALTER|DROP|TRUNCATE)\b', ddl, re.I),
             'unexpected extra DDL for ' + name)
        need(not re.search(r'\bALTER\s+TABLE\s+`?' + re.escape(name) + r'\b', text, re.I),
             'later ALTER requires explicit review for ' + name)
        result[name] = {'ddl': ddl, 'original_ddl_sha256': sha(ddl.encode('utf-8'))}
    need('KEY idx_stock_log_product (tenant_id, product_id, biz_date)' in result['st_stock_log']['ddl']
         and 'KEY idx_stock_log_biz (tenant_id, biz_no)' in result['st_stock_log']['ddl'],
         'reviewed stock log indexes absent; no simplified fixture fallback')
    return result


def snapshot_sources():
    pending = [HERE / name for name in JAVA_SOURCES]
    captured = {}
    while pending:
        path = paths.scoped(WORK, pending.pop(), kind='file')
        if path in captured:
            continue
        need(path.is_relative_to(WORK), 'source outside candidate worktree refused')
        raw = path.read_bytes()
        captured[path] = raw
        for name in SHARED['IMPORT'].findall(raw.decode('utf-8-sig')):
            parts = name.split('.')
            while len(parts) > 3:
                variant = Path(*parts).with_suffix('.java')
                hits = [root / variant for root in SHARED['SOURCE_ROOTS'] if (root / variant).is_file()]
                need(len(hits) <= 1, 'duplicate current production source: ' + name)
                if hits:
                    pending.extend(hits)
                    break
                parts.pop()
    return dict(sorted(captured.items()))


def protected_artifacts(*, offline=True, backend=None):
    # Keep other source/doc artifacts; live also requires the new migration/refund/trade predecessors.
    items = {p: digest(p) for p in HERE.iterdir() if p.is_file() and not p.name.startswith(NEW_PREFIXES)}
    return items | round_audit.predecessors(STAGE, live=not offline, backend_proof=backend)


def stamp():
    return datetime.now(timezone.utc).isoformat()


def main():
    offline = len(sys.argv) == 3 and sys.argv[2] == '--offline'
    try:
        if not offline:
            paths.require_exec_round(os.environ)
        secrets = () if offline else BASE['contract']()
    except (Refused, paths.ScopeError) as error:
        print('REFUSED: ' + str(error), file=sys.stderr)
        return 2
    info = {'command': COMMAND, 'started_at_utc': stamp(), 'passed': False,
            'milestone': 'bounded-real-mysql-stock-query-not-capacity', 'stage': 'preflight',
            'maven_run': False, 'writes_target_classes': False, 'suite': None, 'not_covered': UNTESTED}
    try:
        need(os.name == 'nt' and (len(sys.argv) == 2 or offline), 'Windows Temurin 17 launcher required')
        info['offline_build_only'] = offline
        if offline:
            info['command'] = 'bash ' + WRAPPER + ' --offline'
        home = paths.java_home(sys.argv[1])
        java, javac = home / 'bin/java.exe', home / 'bin/javac.exe'
        info['standalone_memory'] = paths.require_memory(1536)
        raw_cp, manifest, proof = SHARED['latest_complete_classpath']()
        backend = proof[round_audit.surefire.BACKEND_RECEIPT]
        info['backend_gate_sha256'] = backend
        jars, lombok, discarded = SHARED['jar_classpath'](raw_cp)
        lock = SHARED['jar_lock'](jars)
        sources = snapshot_sources()
        schema_raw = paths.scoped(WORK, SCHEMA, kind='file').read_bytes()
        tables = extract_schema(schema_raw)
        inputs = {path: sha(raw) for path, raw in sources.items()}
        inputs.update({path: digest(path) for path in (Path(__file__).resolve(), SCHEMA,
            HERE / 'stock-query-run.sh', HERE / 'stock-query-selftest.py',
            HERE / 'run_trade_return_integration.py', HERE / 'run_refund_business.py')})
        prior = protected_artifacts(offline=offline, backend=backend)
        info['predecessor_sha256'] = round_audit.report_hashes(prior)
        prior.update(proof | paths.java_inputs(home))
        info.update(surefire_snapshot=manifest, classpath_jar_count=len(jars), discarded_target_directories=discarded,
                    classpath_jar_lock_sha256=lock,
                    dependencies=[{'coordinate': p.relative_to(SHARED['M2']).as_posix(), 'sha256': digest(p)} for p in jars],
                    input_sha256={p.relative_to(WORK).as_posix(): h for p, h in inputs.items()},
                    protected_artifact_sha256={p.name: h for p, h in prior.items()},
                    ddl_extraction={name: row['original_ddl_sha256'] for name, row in tables.items()})
        with tempfile.TemporaryDirectory(prefix='stock-query-build-', dir=round_audit.scratch()) as directory:
            tmp = Path(directory)
            outputs, snapshot = tmp / 'classes', tmp / 'src'
            outputs.mkdir()
            staged_sources = []
            for path, content in sources.items():
                target = snapshot / path.relative_to(WORK)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                staged_sources.append(target)
            schema_file = tmp / 'schema.json'
            schema_file.write_text(json.dumps(tables, ensure_ascii=False), encoding='utf-8')
            cp = os.pathsep.join(p.as_posix() for p in jars)
            info['stage'] = 'javac-current-source-snapshot'
            compile_args = tmp / 'javac.args'
            BASE['argfile'](compile_args, ['--release', '17', '-encoding', 'UTF-8',
                '-processorpath', lombok, '-processor', 'lombok.launch.AnnotationProcessorHider$AnnotationProcessor',
                '-classpath', cp, '-sourcepath', outputs, '-d', outputs, *staged_sources])
            compile_env = {k: v for k, v in os.environ.items() if not k.upper().startswith('ERP_AUDIT_')}
            result = BASE['run'](javac, compile_args, env=compile_env, timeout=75)
            info['javac_exit_code'] = result.returncode
            if result.returncode:
                info['diagnostic'] = (result.stdout + result.stderr)[-7000:]
                raise Refused('javac failed; no JDBC query suite started')
            run_args, result_file = tmp / 'java.args', tmp / 'result.json'
            launch = ['-Djava.net.useSystemProxies=false', '-Duser.timezone=UTC',
                '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-classpath', outputs.as_posix() + os.pathsep + cp,
                'StockQueryMySql']
            # Run actual Java schema canonicalization mutation tests before ANY database connection.
            BASE['argfile'](run_args, [*launch, '--self-test', schema_file])
            result = BASE['run'](java, run_args, env=compile_env, timeout=20)
            info['java_offline_selftest_exit_code'] = result.returncode
            if result.returncode:
                info['diagnostic'] = (result.stdout + result.stderr)[-5000:]
                raise Refused('Java schema safety self-tests failed; database untouched')
            need(SHARED['jar_lock'](jars) == lock and round_audit.unchanged(inputs | prior),
                 'source/dependency drift before JDBC; refusing execution')
            if not offline:
                info['stage'] = 'real-mysql-query-scenes'
                BASE['argfile'](run_args, [*launch, result_file, schema_file])
                result = BASE['run'](java, run_args, env=os.environ, timeout=170)
                info['java_exit_code'] = result.returncode
                if not result_file.is_file():
                    info['diagnostic'] = (result.stdout + result.stderr)[-5000:]
                    raise Refused('Java emitted no structured result; cleanup NOT assumed')
                info['suite'] = json.loads(result_file.read_text(encoding='utf-8'))
                database = info['suite'].get('database', {})
                need(database.get('cleanup_verified') is True and not database.get('cleanup_errors')
                     and len(database.get('created_tables', [])) == len(database.get('dropped_tables', [])),
                     'ownership cleanup/no-residue verification failed')
            info['lock_unchanged_through_execution'] = SHARED['jar_lock'](jars) == lock and all(
                digest(p) == h for p, h in inputs.items())
            info['protected_artifacts_unchanged'] = round_audit.unchanged(prior)
            need(info['lock_unchanged_through_execution'] and info['protected_artifacts_unchanged'],
                 'input/evidence drift; result not accepted as pinned')
            if offline:
                info['passed'], info['stage'] = True, 'offline-compile-and-selftest'
            else:
                info['passed'] = result.returncode == 0 and info['suite'].get('passed') is True
                info['stage'] = 'complete' if info['passed'] else 'query-or-fixture-failed'
    except (Refused, OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as error:
        info['error'] = str(error) if isinstance(error, Refused) else type(error).__name__
    info['finished_at_utc'] = stamp()
    info = BASE['sanitize'](info, secrets)
    report = round_audit.publish(STAGE, info, offline=offline)
    if not offline:
        database = (info['suite'] or {}).get('database', {})
        print('Owned cleanup verified: ' + str(database.get('cleanup_verified')))
    print(('PASS' if info['passed'] else 'FAIL') + ': ' + info['stage'] + '; report: ' + report.name)
    return 0 if info['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
