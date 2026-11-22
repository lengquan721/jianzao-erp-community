#!/usr/bin/env python3
"""Build a bounded standalone test from current Java sources; no Maven, app config, or secrets in argv."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from xml.etree import ElementTree as ET

HERE = Path(__file__).absolute().parent
sys.path.insert(0, str(HERE))
try:
    import audit_round as round_audit
finally:
    sys.path.pop(0)
paths = round_audit.paths
WORK = paths.WORK
FIN = WORK / 'erp-modules/erp-fin'
MASTER = WORK / 'erp-modules/erp-master'
TRADE = WORK / 'erp-modules/erp-trade'
COMMON = WORK / 'jianzao-backend/jianzao-common'
M2 = (Path.home() / '.m2/repository').resolve()
COMMAND = ('python.exe -B ' + (paths.ENVIRONMENT / 'isolated_mysql.py').relative_to(paths.ROOT).as_posix()
           + ' exec -- bash ' + (HERE / 'run-refund-business.sh').relative_to(paths.ROOT).as_posix())
TOOL_OPTIONS = ('-Djava.io.tmpdir=C:/Windows/Temp -Xms32m -Xmx384m -XX:MaxMetaspaceSize=256m '
                '-XX:ReservedCodeCacheSize=96m -XX:+UseSerialGC')


class Refused(RuntimeError):
    pass


def need(condition, message):
    if not condition:
        raise Refused(message)


def contract():
    # Fail before resolving classpaths, compiling, or touching the DB; no fallback or caller URL.
    need(os.getenv('ERP_AUDIT_REAL_MYSQL') == '1', 'explicit opt-in missing; use isolated_mysql.py exec')
    def get(key):
        value = os.getenv('ERP_AUDIT_MYSQL_' + key)
        need(value and value.strip(), 'missing exec variable: ERP_AUDIT_MYSQL_' + key)
        return value
    need(get('HOST') == '127.0.0.1' and get('PORT') == '43306', 'literal endpoint refused')
    schema = get('DATABASE')
    need(re.fullmatch(r'erp_audit_[a-z0-9][a-z0-9_]{0,44}', schema), 'audit schema refused')
    expected = (f'jdbc:mysql://127.0.0.1:43306/{schema}?useUnicode=true&characterEncoding=utf8'
                '&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true')
    need(get('JDBC_URL') == expected, 'complete JDBC URL refused')
    need(get('DRIVER') == 'com.mysql.cj.jdbc.Driver', 'JDBC driver refused')
    user, password = get('USERNAME'), get('PASSWORD')
    need(re.fullmatch(r'erp_audit_[a-f0-9]{10}', user) and re.fullmatch(r'[a-f0-9]{64}', password),
         'dedicated exec app credentials refused')
    need(not any(k.lower() == 'proxy' or k.lower().endswith('_proxy') for k in os.environ),
         'runner must clear all proxy variables')
    need(os.getenv('JAVA_TOOL_OPTIONS') == TOOL_OPTIONS and not any(k in os.environ for k in
         ('CLASSPATH', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS')),
         'only the approved JVM options are allowed; use the Bash runner')
    return ((password, '[REDACTED]'), (user, '[REDACTED]'), (schema, 'erp_audit_[REDACTED]'))


def checked_cp_path(raw):
    path = Path(raw)
    need(path.is_absolute(), 'non-absolute entry in Surefire classpath')
    if path.suffix.lower() == '.jar':
        return paths.scoped(M2, path, kind='file')
    need(path.parts[-2:] in (('target', 'classes'), ('target', 'test-classes')),
         'untrusted exploded classpath item')
    return paths.scoped(WORK, path, kind='dir')


def approved_driver():
    return paths.scoped(M2, 'com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar', kind='file')


def classpath():
    raw_cp, manifest, proof = round_audit.surefire.consume('erp-fin')
    items, lombok = [], []
    for raw in filter(None, raw_cp.split(os.pathsep)):
        path = checked_cp_path(raw)
        # Bind every ERP class used by this harness to the explicit source closure, not reactor binaries.
        if (path.name == 'test-classes' or (path.is_dir() and path.is_relative_to(WORK / 'erp-modules'))
                or path.name.startswith(('junit-', 'mockito-', 'surefire-', 'mysql-connector-j-', 'jianzao-', 'erp-'))):
            continue
        items.append(path)
        if path.name.startswith('lombok-'):
            lombok.append(path)
    need(len(lombok) == 1, 'need exactly one existing Lombok processor')
    return [approved_driver(), *dict.fromkeys(items)], lombok[0], manifest, proof


def source_paths():
    groups = {
        FIN: ('org/erp/fin/', '''service/IFinReceiptService service/IFinArApService service/IFinPaymentService
            service/IFinOpeningService service/IFinOpeningGuardService
            service/impl/FinReceiptServiceImpl service/impl/FinArApServiceImpl service/impl/FinPaymentServiceImpl
            service/impl/FinOpeningServiceImpl service/impl/FinOpeningGuardServiceImpl
            mapper/FinReceiptMapper mapper/FinReceiptAllocMapper mapper/FinArApMapper mapper/FinAccountMapper
            mapper/FinPaymentMapper mapper/FinPaymentAllocMapper mapper/FinOpeningMapper mapper/FinSourceNoMapper
            domain/FinReceipt domain/FinReceiptAlloc domain/FinArAp domain/FinAccount domain/FinOpening
            domain/FinPayment domain/FinPaymentAlloc domain/FinSaleReturnRefundSource domain/FinSourceNo
            domain/bo/FinReceiptBo domain/bo/FinPaymentBo domain/bo/FinOpeningBo
            domain/vo/FinReceiptVo domain/vo/FinReceiptAllocVo domain/vo/FinPaymentVo domain/vo/FinPaymentAllocVo
            domain/vo/FinOpeningVo domain/vo/FinOpeningConfirmedAmountVo domain/vo/FinArApVo
            domain/vo/FinArApSourceVo domain/vo/FinArApSummaryVo
            domain/vo/FinArApSummaryPageVo domain/vo/FinAccountVo domain/vo/FinAccountFlowVo domain/FinAccountMovement'''),
        MASTER: ('org/erp/', '''common/enums/StatusText common/security/ConfirmPermission
            common/sequence/SequenceService master/mapper/ErpCustomerMapper master/mapper/ErpSupplierMapper
            master/mapper/MasterOptionSql master/domain/ErpCustomer master/domain/ErpSupplier
            master/domain/bo/MasterOptionQuery master/domain/vo/ErpCustomerVo master/domain/vo/ErpSupplierVo'''),
        TRADE: ('org/erp/trade/', 'mapper/ErpSaleReturnMapper domain/ErpSaleReturn domain/vo/ErpSaleReturnVo'),
        COMMON / 'jianzao-common-tenant': ('org/dromara/common/tenant/',
            'handle/PlusTenantLineHandler helper/TenantHelper core/TenantEntity properties/TenantProperties'),
        COMMON / 'jianzao-common-core': ('org/dromara/common/core/', 'utils/SpringUtils'),
        COMMON / 'jianzao-common-mybatis': ('org/dromara/common/mybatis/',
            'handler/InjectionMetaObjectHandler core/domain/BaseEntity core/mapper/BaseMapperPlus'),
        COMMON / 'jianzao-common-satoken': ('org/dromara/common/satoken/',
            'utils/LoginHelper core/service/SaPermissionImpl'),
    }
    production = [group / 'src/main/java' / (prefix + name + '.java')
                  for group, (prefix, names) in groups.items() for name in names.split()]
    return [paths.scoped(WORK, p, kind='file') for p in
            [*production, HERE / 'RefundAuditDatabase.java', HERE / 'RefundBusinessMySql.java', HERE / 'FinOpeningMySql.java']]


def source_inputs(sources):
    return [*sources, HERE / 'run_refund_business.py', HERE / 'run-refund-business.sh', HERE / 'audit_round.py']


def fingerprints(paths):
    return {p.relative_to(WORK).as_posix(): round_audit.checked_digest(p) for p in paths}


def argfile(path, args):
    args = [str(arg).replace('\\', '/') for arg in args]
    need(all(not any(c in arg for c in '\r\n"\0') for arg in args), 'invalid launch argument')
    path.write_text(''.join(f'"{arg}"\n' for arg in args), encoding='utf-8')


def run(exe, args, *, env, timeout):
    return subprocess.run([str(exe), '@' + str(args)], stdin=subprocess.DEVNULL, capture_output=True,
                          text=True, encoding='utf-8', errors='replace', env=env, timeout=timeout)


def sanitize(value, secrets):
    if isinstance(value, str):
        for secret, placeholder in secrets:
            value = value.replace(secret, placeholder)
        return value
    if isinstance(value, list):
        return [sanitize(item, secrets) for item in value]
    if isinstance(value, dict):
        return {key: sanitize(item, secrets) for key, item in value.items()}
    return value


def main():
    try:
        paths.require_exec_round(os.environ)
        secrets = contract()
    except (Refused, paths.ScopeError) as error:
        print(f'REFUSED: {error}', file=sys.stderr)
        return 2

    evidence = {'command': COMMAND, 'started_at_utc': datetime.now(timezone.utc).isoformat(),
                'passed': False, 'stage': 'build-preflight', 'business_suite': None}
    try:
        need(os.name == 'nt' and len(sys.argv) == 2, 'Windows Temurin 17 launcher required')
        java_home = paths.java_home(sys.argv[1])
        java, javac = (java_home / 'bin/java.exe', java_home / 'bin/javac.exe')
        evidence['standalone_memory'] = paths.require_memory(1536)
        cp, processor, surefire_report, proof = classpath()
        backend = proof[round_audit.surefire.BACKEND_RECEIPT]
        proof.update(paths.java_inputs(java_home))
        prior = round_audit.predecessors('refund-business', backend_proof=backend)
        evidence['predecessor_sha256'] = round_audit.report_hashes(prior)
        proof.update(prior)
        proof.update({p: round_audit.checked_digest(p) for p in cp if p.is_file()})
        sources = source_paths()
        inputs = source_inputs(sources)
        evidence.update(surefire_classpath_from=surefire_report, classpath_entries=len(cp),
                        source_sha256=fingerprints(inputs), maven_run=False, backend_gate_sha256=backend)
        with tempfile.TemporaryDirectory(prefix='.refund-java-', dir=round_audit.scratch()) as temp:
            temp = Path(temp)
            compile_args, run_args = temp / 'javac.args', temp / 'java.args'
            java_json = temp / 'result.json'
            outputs = temp / 'classes'
            outputs.mkdir()
            staged_sources = []
            for source in sources:
                relative = source.relative_to(WORK)
                raw = source.read_bytes()
                need(hashlib.sha256(raw).hexdigest() == evidence['source_sha256'][relative.as_posix()],
                     'source changed before staging the compile snapshot')
                staged = temp / 'src' / relative
                staged.parent.mkdir(parents=True, exist_ok=True)
                staged.write_bytes(raw)
                staged_sources.append(staged)
            cp = os.pathsep.join(p.as_posix() for p in cp)
            evidence['stage'] = 'javac'
            argfile(compile_args, ['--release', '17', '-encoding', 'UTF-8',
                '-processorpath', processor, '-processor', 'lombok.launch.AnnotationProcessorHider$AnnotationProcessor',
                '-classpath', cp, '-sourcepath', outputs, '-d', outputs, *staged_sources])
            # The compiler and annotation processor are never given DB credentials.
            compile_env = {k: v for k, v in os.environ.items() if not k.upper().startswith('ERP_AUDIT_')}
            compiled = run(javac, compile_args, env=compile_env, timeout=75)
            evidence['javac_exit_code'] = compiled.returncode
            if compiled.returncode:
                evidence['diagnostic'] = (compiled.stdout + compiled.stderr)[-6000:]
                raise Refused('javac failed; no Java business tests or Java JDBC ran')
            need(fingerprints(inputs) == evidence['source_sha256'] and round_audit.unchanged(proof),
                 'source, Java, classpath or predecessor changed during compilation; not running')
            evidence['stage'] = 'java-business-suite'
            argfile(run_args, ['-Djava.net.useSystemProxies=false', '-Duser.timezone=UTC',
                '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-classpath',
                outputs.as_posix() + os.pathsep + cp, 'RefundBusinessMySql', java_json])
            result = run(java, run_args, env=os.environ, timeout=195)
            evidence['java_exit_code'] = result.returncode
            if not java_json.is_file():
                evidence['diagnostic'] = (result.stdout + result.stderr)[-5000:]
                raise Refused('Java emitted no structured business result')
            evidence['business_suite'] = json.loads(java_json.read_text(encoding='utf-8'))
            evidence['source_unchanged_through_run'] = fingerprints(inputs) == evidence['source_sha256']
            evidence['classpath_and_predecessor_unchanged'] = round_audit.unchanged(proof)
            need(evidence['source_unchanged_through_run'] and evidence['classpath_and_predecessor_unchanged'],
                 'source, classpath, Java or predecessor changed during run; result not accepted')
            evidence['passed'] = result.returncode == 0 and evidence['business_suite'].get('passed') is True
            evidence['stage'] = 'complete' if evidence['passed'] else 'business-or-fixture-failed'
    except (Refused, OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as error:
        evidence['error'] = str(error) if isinstance(error, Refused) else type(error).__name__
    evidence['finished_at_utc'] = datetime.now(timezone.utc).isoformat()
    evidence = sanitize(evidence, secrets)
    report = round_audit.publish('refund-business', evidence)
    suite = evidence['business_suite'] or {}
    for name, case in suite.get('cases', {}).items():
        print(f'{case["status"].upper()}: {name}')
    cleanup = suite.get('database', {})
    if cleanup:
        print(f'Cleanup verified: {cleanup.get("cleanup_verified")}; created/dropped '
              f'{len(cleanup.get("created_tables", []))}/{len(cleanup.get("dropped_tables", []))}')
    print(f'{"PASS" if evidence["passed"] else "FAIL"}: {evidence["stage"]}; report: {report.name}')
    return 0 if evidence['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
