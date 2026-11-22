#!/usr/bin/env python3
"""P04 standalone real inventory-service hotspot tool. Never Maven/app config/other runner outputs."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile
from xml.etree import ElementTree as ET

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
WORK = HERE.parent
SCHEMA = WORK / 'deploy/mysql/init/04-erp-schema.sql'
# Load only existing pure guards/classpath/process functions. Their __main__/suite/report code NEVER runs.
SHARED = runpy.run_path(str(HERE / 'run_trade_return_integration.py'))
BASE = SHARED['shared']
need, Refused = BASE['need'], BASE['Refused']
sha, digest = SHARED['sha'], SHARED['digest']
PREFIXES = ('stock-hotspot-', 'StockHotspot')
JAVA_SOURCES = ('StockHotspotMySql.java', 'StockHotspotDatabase.java', 'StockHotspotFixture.java',
                'StockHotspotCapture.java', 'StockQueryDatabase.java', 'RefundAuditDatabase.java')
TABLES = ('md_product', 'st_stock', 'st_stock_log')
round_audit, paths = SHARED['round_audit'], SHARED['paths']
STAGE = 'stock-hotspot'
WRAPPER = (HERE / 'stock-hotspot-run.sh').relative_to(paths.ROOT).as_posix()
COMMAND = ('python.exe -B ' + (paths.ENVIRONMENT / 'isolated_mysql.py').relative_to(paths.ROOT).as_posix()
           + ' exec -- bash ' + WRAPPER)
OFFLINE_COMMAND = 'bash ' + WRAPPER + ' --offline'


def extract_schema(raw):
    text, result = raw.decode('utf-8-sig'), {}
    for name in TABLES:
        pattern = (r'\bCREATE TABLE IF NOT EXISTS ' + re.escape(name)
                   + r' \([\s\S]*?\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=\'[^\'\r\n]*\';')
        hits = re.findall(pattern, text)
        need(len(hits) == 1, 'expected exactly one original CREATE: ' + name)
        ddl = hits[0]
        need(ddl.count(';') == 1 and not re.search(r'\b(?:ALTER|DROP|TRUNCATE)\b', ddl, re.I), 'extra DDL refused')
        need(not re.search(r'\bALTER\s+TABLE\s+`?' + re.escape(name) + r'\b', text, re.I), 'later ALTER needs review: ' + name)
        result[name] = {'ddl': ddl, 'original_ddl_sha256': sha(ddl.encode('utf-8'))}
    need('KEY idx_stock_log_product (tenant_id, product_id, biz_date)' in result['st_stock_log']['ddl']
         and 'KEY idx_stock_log_biz (tenant_id, biz_no)' in result['st_stock_log']['ddl']
         and 'UNIQUE KEY uk_st_stock (tenant_id, product_id, warehouse_id)' in result['st_stock']['ddl'],
         'reviewed candidate indexes absent; no simplified DDL fallback')
    return result


def snapshot_sources():
    pending = [HERE / name for name in JAVA_SOURCES]
    pending.append(SHARED['SOURCE_ROOTS'][3] / 'org/erp/master/mapper/MasterOptionSql.java')
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
    items = {p: digest(p) for p in HERE.iterdir() if p.is_file() and not p.name.startswith(PREFIXES)}
    return items | round_audit.predecessors(STAGE, live=not offline, backend_proof=backend)


def unchanged(snapshot):
    return round_audit.unchanged(snapshot)


def stamp():
    return datetime.now(timezone.utc).isoformat()


def main():
    offline = len(sys.argv) == 3 and sys.argv[2] == '--offline'
    # No credential reads at all in offline mode. Live refuses before compilation or any output on a bad contract.
    try:
        need(len(sys.argv) == 2 or offline, 'usage: stock-hotspot-run.py JAVA17_HOME [--offline]')
        if not offline:
            paths.require_exec_round(os.environ)
        secrets = () if offline else BASE['contract']()
    except (Refused, paths.ScopeError) as error:
        print('REFUSED: ' + str(error), file=sys.stderr)
        return 2
    info = {'command': OFFLINE_COMMAND if offline else COMMAND, 'started_at_utc': stamp(), 'passed': False,
            'milestone': 'P04-bounded-real-inventory-service-hotspot', 'stage': 'preflight',
            'offline_build_only': offline, 'database_connected': False if offline else 'not-yet-confirmed',
            'maven_run': False, 'writes_target_classes': False, 'suite': None,
            'not_covered': ['HTTP/Redis', 'production cardinality/skew/concurrency capacity/SLO',
                            'deadlock stress', 'reverse/VOID business lifecycle', 'full schema migration',
                            'replacement or overwrite of stock-query Mapper evidence']}
    prior, inputs, jars, lock = {}, {}, [], None
    try:
        need(os.name == 'nt', 'Windows Temurin 17 launcher required')
        home = paths.java_home(sys.argv[1])
        java, javac = home / 'bin/java.exe', home / 'bin/javac.exe'
        info['standalone_memory'] = paths.require_memory(1536)
        raw_cp, manifest, proof = SHARED['latest_complete_classpath']()
        backend = proof[round_audit.surefire.BACKEND_RECEIPT]
        info['backend_gate_sha256'] = backend
        jars, lombok, discarded = SHARED['jar_classpath'](raw_cp)
        lock = SHARED['jar_lock'](jars)
        sources = snapshot_sources()
        tables = extract_schema(paths.scoped(WORK, SCHEMA, kind='file').read_bytes())
        inputs = {p: sha(raw) for p, raw in sources.items()}
        inputs.update({p: digest(p) for p in (Path(__file__).resolve(), SCHEMA, HERE / 'stock-hotspot-run.sh',
            HERE / 'stock-hotspot-selftest.py', HERE / 'run_trade_return_integration.py', HERE / 'run_refund_business.py')})
        prior = protected_artifacts(offline=offline, backend=backend)
        info['predecessor_sha256'] = round_audit.report_hashes(prior)
        prior.update(proof | paths.java_inputs(home))
        info.update(surefire_classpath_snapshot=manifest, classpath_jar_count=len(jars),
                    discarded_mutable_target_directories=discarded, classpath_jar_lock_sha256=lock,
                    source_count=len(sources), input_sha256={p.relative_to(WORK).as_posix(): h for p, h in inputs.items()},
                    protected_artifact_sha256={p.name: h for p, h in prior.items()},
                    original_ddl_sha256={n: row['original_ddl_sha256'] for n, row in tables.items()})
        with tempfile.TemporaryDirectory(prefix='stock-hotspot-build-', dir=round_audit.scratch()) as directory:
            tmp = Path(directory); outputs, snapshot = tmp / 'classes', tmp / 'src'; outputs.mkdir()
            staged_sources = []
            for p, raw in sources.items():
                dest = snapshot / p.relative_to(WORK); dest.parent.mkdir(parents=True, exist_ok=True); dest.write_bytes(raw); staged_sources.append(dest)
            schema_file = tmp / 'schema.json'; schema_file.write_text(json.dumps(tables, ensure_ascii=False), encoding='utf-8')
            cp = os.pathsep.join(p.as_posix() for p in jars)
            compile_args = tmp / 'javac.args'; info['stage'] = 'javac-current-source-snapshot'
            BASE['argfile'](compile_args, ['--release', '17', '-encoding', 'UTF-8', '-processorpath', lombok,
                '-processor', 'lombok.launch.AnnotationProcessorHider$AnnotationProcessor', '-classpath', cp,
                '-sourcepath', outputs, '-d', outputs, *staged_sources])
            compile_env = {k: v for k, v in os.environ.items() if not k.upper().startswith('ERP_AUDIT_')}
            result = BASE['run'](javac, compile_args, env=compile_env, timeout=75)
            info['javac_exit_code'] = result.returncode
            if result.returncode:
                info['diagnostic'] = (result.stdout + result.stderr)[-7000:]
                raise Refused('javac failed; no database suite started')
            launch = ['-Djava.net.useSystemProxies=false', '-Duser.timezone=UTC', '-Dstdout.encoding=UTF-8',
                      '-Dstderr.encoding=UTF-8', '-classpath', outputs.as_posix() + os.pathsep + cp, 'StockHotspotMySql']
            run_args, result_file = tmp / 'java.args', tmp / 'result.json'
            BASE['argfile'](run_args, [*launch, '--self-test', schema_file])
            result = BASE['run'](java, run_args, env=compile_env, timeout=20)
            info['java_offline_selftest_exit_code'] = result.returncode
            if result.returncode:
                info['diagnostic'] = (result.stdout + result.stderr)[-5000:]
                raise Refused('Java offline mutation tests failed; database untouched')
            need(unchanged(inputs | prior) and SHARED['jar_lock'](jars) == lock, 'input/dependency or predecessor drift before JDBC')
            if not offline:
                info['stage'] = 'real-mysql-service-replay-and-lock-race'
                BASE['argfile'](run_args, [*launch, result_file, schema_file])
                result = BASE['run'](java, run_args, env=os.environ, timeout=220)
                info['java_exit_code'] = result.returncode
                need(result_file.is_file(), 'no structured Java result; cleanup UNKNOWN, do not assume success')
                info['suite'] = json.loads(result_file.read_text(encoding='utf-8'))
                database = info['suite'].get('database', {})
                info['database_connected'] = 'mysql_version' in database
                need(database.get('cleanup_verified') is True and not database.get('cleanup_errors')
                     and len(database.get('created_tables', [])) == len(database.get('dropped_tables', []))
                     and not database.get('owned_objects_remaining'), 'ownership cleanup/no-residue verification failed')
            info['passed'] = offline or (result.returncode == 0 and info['suite'].get('passed') is True)
            info['stage'] = 'offline-compile-and-selftest' if offline else ('complete' if info['passed'] else 'service-or-fixture-failed')
    except (Refused, OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as error:
        info['error'] = str(error) if isinstance(error, Refused) else type(error).__name__
    finally:
        # Verify these even when compilation or a real case fails; never repair/rewrite an existing artifact.
        info['protected_artifacts_unchanged'] = unchanged(prior)
        info['inputs_unchanged'] = unchanged(inputs)
        info['dependencies_unchanged'] = lock is None or SHARED['jar_lock'](jars) == lock
        if not all(info[k] for k in ('protected_artifacts_unchanged', 'inputs_unchanged', 'dependencies_unchanged')):
            info['passed'] = False; info['error'] = 'protected artifact/input/dependency drift; result not accepted'
    info['finished_at_utc'] = stamp(); info = BASE['sanitize'](info, secrets)
    report = round_audit.publish(STAGE, info, offline=offline)
    for name, row in (info['suite'] or {}).get('cases', {}).items():
        print(str(row.get('status', 'failed')).upper() + ': ' + name)
    print(('PASS' if info['passed'] else 'FAIL') + ': ' + info['stage'] + '; report: ' + report.name)
    return 0 if info['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
