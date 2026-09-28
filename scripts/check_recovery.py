"""Offline recovery-design checks. Does not execute SQL or simulate a worker."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/RECOVERY.md'


def validate(text, model):
    for link in re.findall(r'\]\(([^)]+)\)', text):
        if link.startswith('https://'):
            assert link in {
                'https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html',
                'https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks-handling.html'}, 'Unexpected external source'
        else:
            assert (DOC.parent / link).is_file(), 'Broken link: ' + link
    for heading in ('Guarantees and limits', 'Configuration and durable policy', 'Lock order and transaction boundaries',
                    'Indexed claim and ownership SQL', 'Prepare, dispatch and completion', 'Retry and repair policy',
                    'Recovery decision table', 'Ambiguous commits, outages and shutdown', 'Receiver assumptions and drill', 'Verification roadmap'):
        assert '## ' + heading in text, 'Missing section: ' + heading
    for prefix, count in [('B', 7), ('C', 10), ('K', 12)]:
        assert re.findall(r'^\| (' + prefix + r'\d+)(?: [^|]+)? \|', text, re.M) == [f'{prefix}{i:02}' for i in range(1, count+1)], 'Missing/duplicate ' + prefix + ' case'
    sql = '\n'.join(re.findall(r'```sql\n(.*?)```', text, re.S))
    for guard in ("status = 'leased'", 'lease_owner = :lease_owner', 'claim_generation = :claim_generation',
                  'target_node_id = :target_node_id', 'step_sequence <=> :step_sequence', 'lease_until > :db_now',
                  'run_id = :run_id', "available_at <= UTC_TIMESTAMP(6)", 'lease_until <= UTC_TIMESTAMP(6)'):
        assert guard in sql, 'Missing SQL guard: ' + guard
    for marker in ('attempt.claim_generation = :claim_generation', '(run_id, step_sequence, attempt_no)',
                   '**workflow → run → job → step → attempt → approval**', '**do not resend**',
                   'M + ai_repair_count', 'never for repair or restart', 'No network call inside a database transaction',
                   'D01 resolved on 2026-09-28 (preserve supplied graph and enforce approval gates)', 'never push or publish', 'No unconditional exactly-once guarantee',
                   'All runtime tests below are **not run**', 'No API routes changed', 'unknown, not rollback'):
        assert marker in text, 'Missing protocol boundary: ' + marker
    for field in ('execution_policy', 'ai_repair_request?', 'next_attempt_cause?'):
        assert '| ' + field + ' |' in model, 'Missing schema field: ' + field
    for index in ('(status, available_at, run_id)', '(status, lease_until, run_id)'):
        assert index in model and index in text, 'Missing queue index: ' + index
    config = dict((k, int(v)) for k, v in re.findall(r'^\| (RELAY_\w+) \| (\d+) \|', text, re.M))
    assert len(config) == 9, 'Configuration coverage'
    assert all(0 < v <= 2147483647 for v in config.values()), 'Invalid default range'
    assert config['RELAY_JOB_LEASE_MS'] > config['RELAY_JOB_RENEW_MS'] + max(config['RELAY_HTTP_TIMEOUT_MS'], config['RELAY_AI_TIMEOUT_MS']) + 2*config['RELAY_DB_TX_TIMEOUT_MS'], 'Unsafe lease default'
    assert 1 <= config['RELAY_RETRY_MAX_ATTEMPTS'] <= 100
    assert config['RELAY_RETRY_BASE_MS'] <= config['RELAY_RETRY_MAX_MS']
    delays = [min(config['RELAY_RETRY_MAX_MS'], config['RELAY_RETRY_BASE_MS'] * 2**(k-1)) for k in range(1, config['RELAY_RETRY_MAX_ATTEMPTS'])]
    assert delays == [1000, 2000], 'Documented delay examples drifted'
    assert config['RELAY_RETRY_MAX_ATTEMPTS'] + 1 == 4, 'Documented AI maximum drifted'
    tasks = set(re.findall(r'\*\*(\d+\.\d+)\*\*', (ROOT/'PROJECT_PLAN.md').read_text()))
    refs = set(re.findall(r'^\| (V\d+) \|', (ROOT/'docs/REQUIREMENTS.md').read_text(), re.M))
    for row in re.findall(r'^\| K\d+ .+$', text, re.M):
        assert set(re.findall(r'\b\d+\.\d+\b', row)) <= tasks, 'Unknown task reference'
        assert set(re.findall(r'\bV\d+\b', row)) <= refs, 'Unknown verification reference'


def main():
    text, model = DOC.read_text(), (ROOT/'docs/DATA_MODEL.md').read_text()
    validate(text, model)
    print('PASS: seven atomic boundaries, ten recovery cases, twelve future tests, SQL fences, schema/indexes and links')
    print('PASS: nine configuration defaults, lease headroom, two backoff delays and maximum four AI attempts agree')
    mutations = [
        ('missing generation fence', 'AND claim_generation = :claim_generation', 'AND 1 = 1'),
        ('missing attempt fence', 'attempt.claim_generation = :claim_generation', 'attempt unchecked'),
        ('missing cancel rule', '**do not resend**', 'resend'),
        ('missing atomic boundary', '| B04 outcome |', '| B99 outcome |'),
        ('missing queue index', '(status, lease_until, run_id)', '(lease_until)'),
        ('unsafe lease', '| RELAY_JOB_LEASE_MS | 60000 |', '| RELAY_JOB_LEASE_MS | 10000 |'),
        ('broken link', '(DATA_MODEL.md)', '(missing.md)'),
        ('unknown task', 'Tasks 4.2, 3.10;', 'Tasks 99.99;'),
    ]
    for label, old, new in mutations:
        assert old in text, 'Absent mutation target: ' + label
        try:
            validate(text.replace(old, new), model)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    try:
        validate(text, model.replace('| next_attempt_cause? |', '| omitted? |'))
    except AssertionError:
        print('PASS: rejects missing durable retry intent')
    else:
        raise AssertionError('Undetected schema drift')
    print('11 checks passed. No MySQL, concurrency or crash-drill tests run.')


if __name__ == '__main__':
    main()
