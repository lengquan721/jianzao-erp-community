#!/usr/bin/env python3
"""Standalone trade return chain. Independently compile sources, never execute Maven or a business profile."""
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
TRADE = WORK / 'erp-modules/erp-trade'
M2 = (Path.home() / '.m2/repository').resolve()
PRIOR = ('RefundAuditDatabase.java', 'RefundBusinessMySql.java', 'FinOpeningMySql.java', 'run_refund_business.py',
         'run-refund-business.sh', 'refund-business-acceptance.md', 'verify_refund_account_migration.py',
         'refund-account-migration-acceptance.md')
COMMON = WORK / 'jianzao-backend/jianzao-common'
SOURCE_ROOTS = tuple(p / 'src/main/java' for p in (
    *(WORK / 'erp-modules' / f'erp-{name}' for name in ('trade', 'fin', 'stock', 'master')),
    *(COMMON / f'jianzao-common-{name}' for name in ('core', 'mybatis', 'tenant', 'satoken', 'redis')),
))
JAVA_SOURCES = ('TradeReturnMySql.java', 'TradeReturnAuditDatabase.java', 'PurchaseOpeningMySql.java', 'RefundAuditDatabase.java')
EXPLICIT_SERVICE_SOURCES = (
    SOURCE_ROOTS[0] / 'org/erp/trade/service/impl/ErpPurchaseServiceImpl.java',
    SOURCE_ROOTS[0] / 'org/erp/trade/service/impl/ErpPurchaseReturnServiceImpl.java',
    SOURCE_ROOTS[3] / 'org/erp/master/service/impl/ErpProductServiceImpl.java',
)
REQUIRED_CASES = frozenset({
    'real_confirm_withdraw_atomic_round_trip',
    'fault_after_finance_inventory_dml_rolls_back_whole_confirm',
    'opening_cutoff_blocks_trade_confirm_and_rolls_back_save_confirm',
} | {
    f'{kind}_{entry}_{day}_cutoff_denied_next_day_chain'
    for kind in ('purchase', 'purchase_return')
    for entry in ('confirm', 'insert_confirm', 'update_confirm')
    for day in ('on', 'before')
} | {
    f'{kind}_{ordering}' for kind in ('purchase', 'purchase_return')
    for ordering in ('opening_first_rr_real_confirm', 'trade_first_rr_opening_save_confirm')
})
IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?((?:org\.erp|org\.dromara)\.[\w.]+)(?:\.\*)?\s*;', re.M)

# The explicit static-only mode does not import the runtime/process/database helpers at all.
if sys.argv[1:] != ['--static-selftest']:
    # Reuse reviewed connection/process guards only, not the old suite or its main/report.
    shared = runpy.run_path(str(HERE / 'run_refund_business.py'))
    need, Refused = shared['need'], shared['Refused']
    round_audit, paths = shared['round_audit'], shared['paths']
    COMMAND = ('python.exe -B ' + (paths.ENVIRONMENT / 'isolated_mysql.py').relative_to(paths.ROOT).as_posix()
               + ' exec -- bash ' + (HERE / 'run-trade-return-integration.sh').relative_to(paths.ROOT).as_posix())


def sha(data):
    return hashlib.sha256(data).hexdigest()


def digest(path):
    return round_audit.checked_digest(path)


def latest_complete_classpath():
    # Exact report, stage time, XML bytes, sources and Java tied to this round's backend gate.
    return round_audit.surefire.consume('erp-trade')


def jar_classpath(raw):
    jars, discarded_dirs = [], 0
    for text in filter(None, raw.split(os.pathsep)):
        path = shared['checked_cp_path'](text)
        if path.suffix.lower() != '.jar':
            discarded_dirs += 1
            continue
        if path.name.startswith(('junit-', 'mockito-', 'surefire-', 'mysql-connector-j-', 'jianzao-', 'erp-')):
            continue
        need(path.is_relative_to(M2) and path.is_file(), f'local dependency missing or outside cache: {path.name}')
        jars.append(path)
    driver = shared['approved_driver']()
    jars = list(dict.fromkeys([driver, *jars]))
    lombok = [p for p in jars if p.name.startswith('lombok-')]
    need(len(lombok) == 1, 'expected exactly one cached Lombok processor')
    return jars, lombok[0], discarded_dirs


def jar_lock(jars):
    # Digest ordered repository-relative coordinates + bytes without reporting full paths or XML properties.
    return sha(''.join(f'{path.relative_to(M2).as_posix()}\t{digest(path)}\n' for path in jars).encode('utf-8'))


def collect_sources(scope, require):
    pending = [*(HERE / n for n in JAVA_SOURCES), *EXPLICIT_SERVICE_SOURCES]
    # Erp* mapper source uses a same-package helper without an import.
    pending.append(SOURCE_ROOTS[3] / 'org/erp/master/mapper/MasterOptionSql.java')
    captured = {}
    while pending:
        path = scope(pending.pop())
        if path in captured:
            continue
        require(path.is_relative_to(WORK), 'source outside isolated repair worktree refused')
        require(path.suffix == '.java' and not {'target', 'classes'}.intersection(path.relative_to(WORK).parts),
                'generated/target class fallback refused')
        raw = path.read_bytes()
        captured[path] = raw
        for name in IMPORT.findall(raw.decode('utf-8-sig')):
            # Handle a nested import by resolving the outer .java. No generated or target sources.
            parts = name.split('.')
            while len(parts) > 3:
                variant = Path(*parts).with_suffix('.java')
                hits = [p / variant for p in SOURCE_ROOTS if (p / variant).is_file()]
                require(len(hits) <= 1, 'duplicate production source for ' + name)
                if hits:
                    pending.extend(hits)
                    break
                parts.pop()
    return dict(sorted(captured.items()))


def snapshot_sources():
    return collect_sources(lambda path: paths.scoped(WORK, path, kind='file'), need)


def refund_input_proof(reports):
    # A predecessor from before the opening cases were compiled must not satisfy this run.
    report = paths.scoped(round_audit.REPORTS, 'refund-business-latest.json', kind='file')
    raw = report.read_bytes()
    need(sha(raw) == reports.get(report), 'refund predecessor changed while checking its source binding')
    inputs = shared['source_inputs'](shared['source_paths']())
    source_hashes = shared['fingerprints'](inputs)
    need(json.loads(raw).get('source_sha256') == source_hashes,
         'refund predecessor did not exercise the current harness and production source closure')
    return {path: source_hashes[path.relative_to(WORK).as_posix()] for path in inputs}


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def java_tokens(text):
    # Lexical checks only: not a Java compiler. Strings/text blocks are opaque to delimiter checks.
    pattern = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'
                         r"'(?:\\.|[^'\\])*'|[A-Za-z_$][\w$]*|[^\s]")
    return [m.group() for m in pattern.finditer(text) if not m.group().startswith(('//', '/*'))]


def balanced(tokens):
    stack = []
    for token in tokens:
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            if not stack or stack.pop() != {')': '(', ']': '[', '}': '{'}[token]:
                raise AssertionError('Java delimiter mismatch')
    if stack:
        raise AssertionError('Java delimiter left open')


def fixture_ddl(text):
    tokens, constants, tables = java_tokens(text), {}, {}

    def value(parts):
        out = []
        for token in parts:
            if token == '+':
                continue
            if token.startswith('"""'):
                out.append(token[3:-3])
            elif token.startswith('"'):
                out.append(json.loads(token))
            else:
                out.append(constants[token])
        return ''.join(out)

    for i, token in enumerate(tokens):
        if tokens[i:i + 4] == ['private', 'static', 'final', 'String']:
            name = tokens[i + 4]
            if name in ('ID', 'AUDIT', 'STATE_AUDIT', 'LIVE', 'FULL'):
                end = tokens.index(';', i + 6)
                constants[name] = value(tokens[i + 6:end])
        if tokens[i:i + 3] == ['new', 'Table', '(']:
            end, depth = i + 3, 1
            while depth:
                if tokens[end] == '(':
                    depth += 1
                elif tokens[end] == ')':
                    depth -= 1
                end += 1
            name = json.loads(tokens[i + 3])
            if name in tables:
                raise AssertionError('duplicate fixture table ' + name)
            tables[name] = value(tokens[i + 5:end - 1])
    return tables


def sql_parts(text):
    parts, start, depth, quote = [], 0, 0, False
    for i, char in enumerate(text):
        if char == "'":
            quote = not quote
        elif not quote:
            if char == '(':
                depth += 1
            elif char == ')':
                depth -= 1
            elif char == ',' and depth == 0:
                parts.append(text[start:i].strip())
                start = i + 1
    parts.append(text[start:].strip())
    return parts


def column_shapes(text):
    columns = {}
    for part in sql_parts(text):
        match = re.match(r'`?(\w+)`?\s+((?:BIGINT|INT|TINYINT|DECIMAL|VARCHAR|CHAR|DATE|DATETIME|JSON)(?:\([\d,]+\))?)(?=\s|$)',
                         part, re.I)
        if match:
            name, kind = match.groups()
            columns[name] = (kind.upper().replace('TINYINT(1)', 'TINYINT'), bool(re.search(r'\bNOT NULL\b', part, re.I)))
    return columns


def check_new_schema(ddl):
    init = (WORK / 'deploy/mysql/init/04-erp-schema.sql').read_text(encoding='utf-8-sig')
    original = {name: column_shapes(body) for name, body in re.findall(
        r'CREATE TABLE IF NOT EXISTS (\w+)\s*\(([\s\S]*?)\n\) ENGINE', init)}
    for name, body in re.findall(r'ALTER TABLE (\w+)\s+([\s\S]*?);', init):
        for part in sql_parts(body):
            if part.startswith('ADD COLUMN '):
                original[name].update(column_shapes(part[len('ADD COLUMN '):]))
    exercised = ('biz_purchase', 'biz_purchase_item', 'biz_purchase_order', 'biz_purchase_order_item',
                 'biz_purchase_return', 'biz_purchase_return_item', 'fin_payment')
    compared = 0
    for name in exercised:
        actual = column_shapes(ddl[name])
        for column, shape in actual.items():
            if original[name].get(column) != shape:
                raise AssertionError(f'fixture/init column mismatch: {name}.{column}: {shape} vs {original[name].get(column)}')
            compared += 1
        if name.startswith('biz_'):
            class_name = 'Erp' + ''.join(word.title() for word in name.removeprefix('biz_').split('_'))
            entity = (SOURCE_ROOTS[0] / f'org/erp/trade/domain/{class_name}.java').read_text(encoding='utf-8-sig')
            fields = re.findall(r'private\s+(?:Long|String|Integer|Date|BigDecimal)\s+(\w+)\s*;', entity)
            for field in fields:
                snake = re.sub(r'(?<!^)([A-Z])', r'_\1', field).lower()
                if snake not in actual:
                    raise AssertionError(f'entity column missing: {name}.{snake}')
        for column in ('id', 'tenant_id', 'del_flag', 'create_time', 'update_time'):
            if column not in actual:
                raise AssertionError(f'fixture audit column missing: {name}.{column}')
    return compared


def constructor_arity(text, name):
    tokens = java_tokens(text)
    for i in range(len(tokens) - 2):
        if tokens[i:i + 3] == ['new', name, '(']:
            depth, commas, j = 1, 0, i + 3
            while depth:
                if tokens[j] in ('(', '[', '{'):
                    depth += 1
                elif tokens[j] in (')', ']', '}'):
                    depth -= 1
                elif tokens[j] == ',' and depth == 1:
                    commas += 1
                j += 1
            return commas + 1
    raise AssertionError('explicit production constructor missing: ' + name)


def static_selftest():
    import ast
    checks = []

    def require(ok, message):
        if not ok:
            raise AssertionError(message)

    def denied(action, label):
        try:
            action()
        except (AssertionError, KeyError):
            checks.append(label)
            return
        raise AssertionError('negative static selftest unexpectedly accepted: ' + label)

    runner_text = Path(__file__).read_text(encoding='utf-8-sig')
    parsed = ast.parse(runner_text)
    function_text = {node.name: ast.get_source_segment(runner_text, node) for node in parsed.body if isinstance(node, ast.FunctionDef)}
    checks.append('runner_python_ast')
    for function, guards in {
        'jar_classpath': ("path.suffix.lower() != '.jar'", "'jianzao-', 'erp-'"),
        'main': ("'-sourcepath', outputs", 'round_audit.unchanged(prior | proof)', 'refund_input_proof(reports)',
                 "database.get('cleanup_verified') is True", 'paths.require_exec_round(os.environ)'),
    }.items():
        for guard in guards:
            require(guard in function_text[function], 'existing runner safety/provenance guard missing: ' + guard)
    checks.append('existing_runtime_jar_only_sourcepath_provenance_cleanup_and_exec_guards_retained')
    sources = collect_sources(lambda path: path.resolve(strict=True), require)
    require(all(p in sources for p in EXPLICIT_SERVICE_SOURCES), 'new production service source missing')
    require(HERE / 'PurchaseOpeningMySql.java' in sources, 'explicit helper source missing')
    checks.append('explicit_fresh_java_source_closure_without_target_paths')
    texts = {name: (HERE / name).read_text(encoding='utf-8-sig') for name in JAVA_SOURCES}
    for name, text in texts.items():
        balanced(java_tokens(text))
    checks.append('harness_java_lexical_delimiters_not_javac')
    denied(lambda: balanced(java_tokens('class Broken { void run( }')), 'reject_malformed_java_delimiters')
    ddl = fixture_ddl(texts['TradeReturnAuditDatabase.java'])
    require(len(ddl) == 25, 'expected 18 original plus 7 new owned tables')
    compared = check_new_schema(ddl)
    checks.append('seven_new_table_column_types_nullability_and_all_purchase_entity_fields_match_final_init')
    broken = dict(ddl)
    broken['biz_purchase_item'] = broken['biz_purchase_item'].replace('seq INT NOT NULL DEFAULT 0,', '')
    denied(lambda: check_new_schema(broken), 'reject_missing_final_init_purchase_seq')
    helper = texts['PurchaseOpeningMySql.java']
    for path in EXPLICIT_SERVICE_SOURCES:
        body = sources[path].decode('utf-8-sig')
        fields = re.findall(r'private final [\w<>]+ \w+;', body)
        require(constructor_arity(helper, path.stem) == len(fields), 'Lombok constructor argument count: ' + path.stem)
    checks.append('explicit_real_service_constructor_arities_match_production_final_fields')
    require('arAp.createBy' not in helper and not re.search(
        r'(?:UPDATE|INSERT INTO)\s+biz_purchase(?:_return)?\s', helper, re.I), 'manual trade confirmation SQL refused')
    for guard in ('ISOLATION_REPEATABLE_READ', 'status.isNewTransaction()', 'SHOW FULL PROCESSLIST', 'FOR UPDATE NOWAIT',
                  'mysql.getErrorCode() == MYSQL_LOCK_NOWAIT', 'distinct().count() == 4', 'samples == 2 && !contender.isDone()',
                  'staleView.run();', 'catch (ServiceException expected)', 'contender.get(15, TimeUnit.SECONDS)',
                  'eq(expected, db.snapshot()', 'eq(40902, error.getCode()', 'saveConfirmOpening(f)', 'confirm(kind, draft)',
                  'eq(before, db.snapshot()', 'checkSequenceDelta', 'checkConfirmed(reader'):
        require(guard in helper, 'missing real-service cutoff/race guard: ' + guard)
    require(len(REQUIRED_CASES) == 19 and 'test.purchaseOpening.runCases(cases)' in texts['TradeReturnMySql.java'],
            'expected 3 preserved plus 12 AP boundary plus 4 real-service RR cases')
    checks.append('required_19_cases_real_service_entry_matrix_and_non_timeout_race_guards_present')
    trade = texts['TradeReturnMySql.java']
    live_query = "SELECT * FROM biz_sale_return_item WHERE order_id=? AND del_flag='0'"
    require(live_query in trade, 'cutoff success must project live row after logical replacement')
    for assertion in ('eq(before, db.snapshot(), "full business snapshot changed', 'eq(originalStockRow, withdrawn',
                      'eq(beforeConfirm, db.snapshot()', 'eq(beforeWithdraw, db.snapshot()',
                      'eq(seq, sequence(A), "cutoff reached', 'eq(0, calls[0], "cutoff was not rejected',
                      "SELECT COUNT(*) FROM biz_sale_return_item WHERE order_id=? AND del_flag='1'"):
        require(assertion in trade, 'preserved/new rollback assertion missing: ' + assertion)
    checks.append('original_atomicity_assertions_preserved_live_line_and_history_both_asserted')
    # These are local static results, never a substitute for standalone javac/JDBC or a fresh published report.
    print(json.dumps({'passed': True, 'mode': 'python-static-only', 'checks': checks,
                      'required_case_count': len(REQUIRED_CASES), 'owned_fixture_tables': len(ddl),
                      'new_init_column_shapes_compared': compared, 'source_closure_count': len(sources),
                      'java_jvm_javac_maven_jdbc_started': False,
                      'source_sha256': {p.relative_to(WORK).as_posix(): sha(raw) for p, raw in sources.items()}},
                     ensure_ascii=False, indent=2))
    return 0


def main():
    try:
        paths.require_exec_round(os.environ)
        secrets = shared['contract']()
    except (Refused, paths.ScopeError) as error:
        print(f'REFUSED: {error}', file=sys.stderr)
        return 2
    info = {'command': COMMAND, 'started_at_utc': timestamp(), 'passed': False,
            'maven_run': False, 'stage': 'build-preflight', 'chain_suite': None}
    try:
        need(os.name == 'nt' and len(sys.argv) == 2, 'Windows Temurin 17 launcher required')
        home = paths.java_home(sys.argv[1])
        java, javac = home / 'bin/java.exe', home / 'bin/javac.exe'
        info['standalone_memory'] = paths.require_memory(1536)
        raw_cp, manifest, proof = latest_complete_classpath()
        backend = proof[round_audit.surefire.BACKEND_RECEIPT]
        proof.update(paths.java_inputs(home))
        info['backend_gate_sha256'] = backend
        jars, lombok, discarded = jar_classpath(raw_cp)
        lock = jar_lock(jars)
        sources = snapshot_sources()
        immutable_inputs = {path: sha(raw) for path, raw in sources.items()}
        immutable_inputs.update({path: digest(path) for path in (Path(__file__).absolute(),
                                HERE / 'run-trade-return-integration.sh', HERE / 'audit_round.py')})
        prior = {HERE / name: digest(HERE / name) for name in PRIOR}
        reports = round_audit.predecessors('trade-return-integration', backend_proof=backend)
        prior.update(reports)
        prior.update(refund_input_proof(reports))
        info['predecessor_sha256'] = round_audit.report_hashes(reports)
        info.update(surefire_snapshot=manifest, classpath_jar_count=len(jars), discarded_target_directories=discarded,
                    classpath_jar_lock_sha256=lock,
                    prior_finance_artifact_sha256={p.relative_to(paths.ROOT).as_posix(): h for p, h in prior.items()},
                    input_sha256={p.relative_to(WORK).as_posix(): sha256 for p, sha256 in immutable_inputs.items()})
        with tempfile.TemporaryDirectory(prefix='.trade-return-java-', dir=round_audit.scratch()) as directory:
            tmp = Path(directory)
            outputs = tmp / 'classes'
            snapshot = tmp / 'src'
            outputs.mkdir()
            staged_sources = []
            for path, content in sources.items():
                # Use distinct parent paths to avoid collisions, even for equal Java basenames.
                target = snapshot / path.relative_to(WORK)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                staged_sources.append(target)
            cp = os.pathsep.join(p.as_posix() for p in jars)
            info['stage'] = 'javac'
            compile_args = tmp / 'javac.args'
            shared['argfile'](compile_args, ['--release', '17', '-encoding', 'UTF-8',
                '-processorpath', lombok, '-processor', 'lombok.launch.AnnotationProcessorHider$AnnotationProcessor',
                '-classpath', cp, '-sourcepath', outputs, '-d', outputs, *staged_sources])
            compile_env = {k: v for k, v in os.environ.items() if not k.upper().startswith('ERP_AUDIT_')}
            result = shared['run'](javac, compile_args, env=compile_env, timeout=75)
            info['javac_exit_code'] = result.returncode
            if result.returncode:
                info['diagnostic'] = (result.stdout + result.stderr)[-7500:].replace(tmp.as_posix(), '<build>')
                raise Refused('javac failed; JVM business suite not run')
            need(jar_lock(jars) == lock and round_audit.unchanged(immutable_inputs)
                 and round_audit.unchanged(prior | proof),
                 'source or dependency lock changed during compile; business JVM not started')
            run_args, result_file = tmp / 'java.args', tmp / 'result.json'
            shared['argfile'](run_args, ['-Djava.net.useSystemProxies=false', '-Duser.timezone=UTC',
                '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-classpath',
                outputs.as_posix() + os.pathsep + cp, 'TradeReturnMySql', result_file])
            info['stage'] = 'java-trade-chain'
            result = shared['run'](java, run_args, env=os.environ, timeout=165)
            info['java_exit_code'] = result.returncode
            if not result_file.is_file():
                info['diagnostic'] = (result.stdout + result.stderr)[-6500:]
                raise Refused('no structured Java result (inspect stage and exit code)')
            info['chain_suite'] = json.loads(result_file.read_text(encoding='utf-8'))
            actual_cases = info['chain_suite'].get('cases', {})
            info['required_case_count'] = len(REQUIRED_CASES)
            info['required_cases_present'] = REQUIRED_CASES.issubset(actual_cases)
            database = info['chain_suite'].get('database', {})
            need(database.get('cleanup_verified') is True
                 and len(database.get('created_tables', [])) == len(database.get('dropped_tables', []))
                 and not database.get('cleanup_errors'),
                 'fixture cleanup or no-residue verification failed')
            info['lock_unchanged_through_execution'] = jar_lock(jars) == lock and all(
                digest(p) == h for p, h in immutable_inputs.items())
            info['prior_finance_artifacts_unchanged'] = round_audit.unchanged(prior | proof)
            need(info['lock_unchanged_through_execution'] and info['prior_finance_artifacts_unchanged'],
                 'evidence/input changed during run; not accepting results as pinned')
            need(info['required_cases_present'], 'required trade cutoff/race case missing; not accepting partial suite')
            info['passed'] = (result.returncode == 0 and info['chain_suite']['passed'] is True
                              and all(actual_cases[name].get('status') == 'passed' for name in REQUIRED_CASES))
            info['stage'] = 'complete' if info['passed'] else 'business-or-fixture-failed'
    except (Refused, OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as error:
        info['error'] = str(error) if isinstance(error, Refused) else type(error).__name__
    info['finished_at_utc'] = timestamp()
    info = shared['sanitize'](info, secrets)
    report = round_audit.publish('trade-return-integration', info)
    suite = info['chain_suite'] or {}
    for name, row in suite.get('cases', {}).items():
        if isinstance(row, dict) and row.get('status'):
            print(f'{row["status"].upper()}: {name}')
    db = suite.get('database', {})
    if db:
        print(f'Owned fixture cleanup: {db.get("cleanup_verified")}; created/dropped '
              f'{len(db.get("created_tables", []))}/{len(db.get("dropped_tables", []))}')
    print(f'{"PASS" if info["passed"] else "FAIL"}: {info["stage"]}; report: {report.name}')
    return 0 if info['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(static_selftest() if sys.argv[1:] == ['--static-selftest'] else main())
