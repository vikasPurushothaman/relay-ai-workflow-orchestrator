"""Offline data-model checks; these do not execute SQL or prove recovery."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/DATA_MODEL.md'


def validate(text):
    for link in re.findall(r'\]\(([^)]+)\)', text):
        assert (DOC.parent / link).is_file(), 'Broken link: ' + link
    sections = {}
    for block in text.split('\n## ')[1:]:
        name, _, body = block.partition('\n')
        sections[name] = body
    expected = {
        'workflows': ['draft_definition', 'published_definition?', 'revision'],
        'runs': ['definition_snapshot', 'input', 'steps_executed', 'next_step_sequence', 'ai_tokens_used?'],
        'steps': ['run_id, sequence', 'resolved_input?', 'dispatch_request?', 'output?', 'idempotency_key?', 'resume_at?', 'ai_repair_count'],
        'step_attempts': ['run_id, step_sequence, attempt_no', 'claim_generation', 'provider?, model?', 'tokens_prompt?, tokens_completion?'],
        'approvals': ['run_id, step_sequence', 'decided_by?, decided_at?', 'closed_at?, close_reason?'],
        'queue_jobs': ['run_id', 'step_sequence?', 'available_at', 'lease_owner?, lease_until?', 'claim_generation'],
    }
    for table, fields in expected.items():
        assert table in sections, 'Missing table: ' + table
        for field in fields:
            assert '| ' + field + ' |' in sections[table], 'Missing field: ' + table + '.' + field
    source = (ROOT / 'docs/source-review/pack/docs/API_CONTRACT.md').read_text()
    status_line = re.search(r'The run status set: (.+)', source).group(1)
    states = re.findall(r'`(\w+)`', status_line)
    run_row = re.search(r'^\| status \| status \| (.+?) \|$', sections['runs'], re.M).group(1)
    assert set(run_row.rstrip('.').split(', ')) == set(states), 'Run status mismatch'
    constraints = sections['Constraints and indexes']
    for ident in [f'C{i:02}' for i in range(1, 7)] + [f'I{i:02}' for i in range(1, 8)]:
        assert len(re.findall(r'^\| ' + ident + r' \|', constraints, re.M)) == 1, 'Missing/duplicate constraint/index ' + ident
    for table, rule in [('steps', 'UNIQUE (idempotency_key)'), ('approvals', 'UNIQUE (run_id, step_sequence)'),
                        ('queue_jobs', '(status, available_at, run_id)'), ('queue_jobs', '(status, lease_until, run_id)')]:
        assert any('| ' + table + ' |' in line and rule in line for line in constraints.splitlines()), 'Missing invariant: ' + rule
    req = (ROOT / 'docs/REQUIREMENTS.md').read_text()
    known_v = set(re.findall(r'^\| (V\d+) \|', req, re.M))
    assert set(re.findall(r'\bV\d{2,}\b', text)) <= known_v, 'Unknown verification reference'
    plan = (ROOT / 'PROJECT_PLAN.md').read_text()
    known_tasks = set(re.findall(r'\*\*(\d+\.\d+)\*\*', plan))
    for row in re.findall(r'^\| T\d+ .+$', text, re.M):
        assert set(re.findall(r'\b\d+\.\d+\b', row)) <= known_tasks, 'Unknown future task'
    assert len(re.findall(r'^\| T\d+ \|', text, re.M)) == 8, 'Missing future test case'
    for marker in ['Schema and JPA mappings implemented', 'D01 resolved on 2026-09-28 (preserve supplied graph and enforce approval gates)', 'never push or publish',
                   'Network calls occur outside database transactions', 'same-run FK relationships',
                   'Unknown duration/usage is NULL', 'Controllers never serialize JPA entities',
                   'No uniqueness on `(run_id, node_id)`', '{run_id}:{sequence}']:
        assert marker in text, 'Missing boundary: ' + marker


def main():
    text = DOC.read_text()
    validate(text)
    print('PASS: six tables, required fields, links, fixed statuses, constraints/indexes and future test references')
    mutations = [
        ('missing snapshot', '| definition_snapshot |', '| omitted |'),
        ('missing key uniqueness', 'UNIQUE (idempotency_key)', 'INDEX (idempotency_key)'),
        ('missing lease index', '(status, lease_until, run_id)', '(lease_until)'),
        ('broken source link', '(source-review/pack/docs/DATA_MODEL.md)', '(missing.md)'),
        ('wrong run status', 'queued, running, waiting_approval, succeeded, failed, cancelled.', 'queued, running, paused, succeeded, failed, cancelled.'),
        ('missing same-step approval uniqueness', 'UNIQUE (run_id, step_sequence)', 'INDEX (run_id, step_sequence)'),
        ('unknown test owner', '3.8, 3.10; V03', '99.99; V03'),
    ]
    for label, old, new in mutations:
        assert old in text, 'Mutation target absent: ' + label
        try:
            validate(text.replace(old, new))
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print('8 checks passed. No runtime tests run.')


if __name__ == '__main__':
    main()
