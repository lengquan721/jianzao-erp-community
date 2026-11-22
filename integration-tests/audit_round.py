"""Standalone acceptance bridge to its adjacent private round, not used by standalone CI."""
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

HERE = Path(__file__).absolute().parent
OUTER = HERE.parents[1] / 'toolset/audit-tools/erp-remediation'
if (HERE.name != 'integration-tests' or HERE.parent.name != 'work' or HERE.resolve() != HERE
        or OUTER.resolve() != OUTER or not (OUTER / 'round_paths.py').is_file()):
    raise ValueError('standalone acceptance requires its adjacent integration toolset')
sys.path.insert(0, str(OUTER))
try:
    import round_paths as paths
    import audit_surefire as surefire
finally:
    sys.path.pop(0)
if (paths.WORK != HERE.parent or Path(paths.__file__).absolute() != OUTER / 'round_paths.py'
        or Path(surefire.__file__).absolute() != OUTER / 'audit_surefire.py'):
    raise ValueError('standalone launcher/resolver round mismatch')

REPORTS = paths.REPORTS / 'standalone'
TEMP = paths.RUNTIME / 'standalone'
STAGES = ('refund-account-migration', 'refund-business', 'trade-return-integration', 'stock-query', 'stock-hotspot')


def scratch():
    path = paths.scoped(paths.RUNTIME, TEMP)
    path.mkdir(parents=True, exist_ok=True)
    expected = paths.scoped(paths.ENVIRONMENT, 'isolated_mysql.py', kind='file')
    sys.path.insert(0, str(paths.ENVIRONMENT))
    try:
        import isolated_mysql
    finally:
        sys.path.pop(0)
    if Path(isolated_mysql.__file__).absolute() != expected:
        raise paths.ScopeError('unexpected isolated_mysql origin')
    isolated_mysql.private_directory(path)
    return path


def checked_digest(path):
    # Use the original lexical path, not a canonicalized alias.
    return paths.sha256(paths.scoped(path.parent, path, kind='file'))


def unchanged(snapshot):
    try:
        return all(checked_digest(path) == checksum for path, checksum in snapshot.items())
    except (OSError, paths.ScopeError):
        return False


def report_hashes(snapshot):
    return {p.name: h for p, h in snapshot.items() if p.parent == REPORTS and p.name.endswith('-latest.json')}


def predecessors(stage, *, live=True, backend_proof=None):
    """A live suite needs successful new-round predecessors; offline only protects any existing reports."""
    predecessors = {}
    for name in STAGES[:STAGES.index(stage)]:
        report = paths.scoped(REPORTS, name + '-latest.json')
        if not live and not report.exists():
            continue
        report = paths.scoped(REPORTS, report, kind='file')
        raw = report.read_bytes()
        row = json.loads(raw)
        if (row.get('round_id') != paths.ROUND_ID or row.get('upstream') != paths.UPSTREAM
                or row.get('verification_suite') != name or row.get('production_validation') is not False):
            raise paths.ScopeError(f'{name}: predecessor belongs to another suite/round')
        if live and (row.get('passed') is not True or row.get('offline_build_only') is not False):
            raise paths.ScopeError(f'{name}: passing live predecessor required')
        if live and row.get('predecessor_sha256') != report_hashes(predecessors):
            raise paths.ScopeError(f'{name}: predecessor sequence is stale or incomplete')
        if live and backend_proof is not None and name != STAGES[0] and row.get('backend_gate_sha256') != backend_proof:
            raise paths.ScopeError(f'{name}: predecessor belongs to another backend gate run')
        import hashlib
        predecessors[report] = hashlib.sha256(raw).hexdigest()
    return predecessors


def publish(stage, evidence, *, offline=False):
    if stage not in STAGES:
        raise paths.ScopeError('unknown standalone report suite')
    target = paths.scoped(paths.REPORTS, REPORTS)
    target.mkdir(exist_ok=True)
    name = stage + ('-offline' if offline else '')
    report = paths.scoped(target, name + '-latest.json')
    temp = paths.scoped(target, name + '-latest.json.tmp')
    result = {**evidence, 'round_id': paths.ROUND_ID, 'upstream': paths.UPSTREAM,
              'verification_suite': stage, 'offline_build_only': offline,
              'candidate_applied': False, 'production_validation': False}
    if report.exists():
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        archive = paths.scoped(target, f'{name}-attempt-{stamp}.json')
        if archive.exists():
            raise paths.ScopeError('report archive already exists')
        report.replace(archive)
        result['previous_report_archived_as'] = archive.name
    with temp.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    temp.replace(report)
    return report
