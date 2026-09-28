"""Offline console design checks, not browser or API tests."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/CONSOLE.md'
PACK = ROOT / 'docs/source-review/pack'


def validate(text):
    for link in re.findall(r'\]\(([^)]+)\)', text):
        assert (DOC.parent / link).is_file(), 'Broken link: ' + link
    screens = re.findall(r'^\| (U\d+) \| ([^|]+) \|', text, re.M)
    assert [s[0] for s in screens] == [f'U{i:02}' for i in range(1, 6)], 'Five screens required'
    assert [s[1].strip() for s in screens] == [
        '/console/workflows', '/console/workflows/:workflowId', '/console/runs', '/console/runs/:runId', '/console/approvals'], 'Browser paths'
    api = (PACK / 'docs/API_CONTRACT.md').read_text()
    fixed_states = set(re.findall(r'`(\w+)`', re.search(r'The run status set: (.+)', api).group(1)))
    status_section = text.split('| Run status |')[1].split('## Run trace')[0]
    states = set(re.findall(r'^\| (\w+) \|', status_section, re.M))
    assert states == fixed_states, 'Six fixed run statuses'
    dependencies = text.split('## API dependencies and projections')[1].split('## Refresh')[0]
    for fixed in ('GET /workflows', 'GET /runs/{runId}', 'GET /approvals?status=pending', 'POST /approvals/{approvalId}/approve'):
        assert '| ' + fixed + ' |' in dependencies, 'Missing fixed dependency: ' + fixed
    for flexible in ('Workflow detail read', 'Run list read', 'Approval rejection command', 'Run cancellation command'):
        assert '| ' + flexible + ' |' in dependencies, 'Missing flexible operation'
    trace = text.split('| Trace field |')[1].split('## Approval inbox')[0]
    for field in ('sequence', 'node_id, node_type', 'status, wait_reason', 'resolved_input', 'output', 'attempts',
                  'timestamps, duration_ms', 'error', 'tokens_prompt, tokens_completion', 'selected_next_node_id',
                  'resume_at, retry_due_at', 'approval', 'idempotency_key'):
        assert '| ' + field + ' |' in trace, 'Missing trace field: ' + field
    for node in json.loads((PACK/'data/node_catalog.json').read_text())['nodes']:
        assert node['type'] in text, 'Missing node presentation'
    for w in json.loads((PACK/'data/seed_workflows.json').read_text())['workflows']:
        assert w['id'] in text, 'Missing fixture walkthrough'
    for marker in ('**Design baseline;', '**Keep the demo token in memory only.**', '**Never automatically retry a mutation.**',
                   'disappearance alone does not mean approved', 'late response for an old run/filter/token',
                   'Creation, editing, publishing and manual triggering remain API-driven',
                   'All browser/integration tests below are **not run**', 'D01 explicitly open',
                   '320 CSS-pixel', '200% zoom', 'No domain API routes implemented', 'never push or publish'):
        assert marker in text, 'Missing boundary: ' + marker
    plan = (ROOT/'PROJECT_PLAN.md').read_text()
    tasks = set(re.findall(r'\*\*(\d+\.\d+)\*\*', plan))
    refs = set(re.findall(r'^\| (V\d+) \|', (ROOT/'docs/REQUIREMENTS.md').read_text(), re.M))
    cases = re.findall(r'^\| (Q\d+) \| (.+) \| (.+) \|$', text, re.M)
    assert [row[0] for row in cases] == [f'Q{i:02}' for i in range(1, 13)], 'Missing test case'
    for _, _, outcome in cases:
        assert set(re.findall(r'\b\d+\.\d+\b', outcome)) <= tasks, 'Unknown implementation task'
        assert set(re.findall(r'\bV\d+\b', outcome)) <= refs, 'Unknown verification reference'
    assert all('- [x] **1.' + str(i) + '**' in plan for i in range(1, 9)), 'Earlier Phase 1 tasks incomplete'
    if '- [x] **1.9**' in plan:
        unfinished = re.search(r'^- \[ \] \*\*(\d+\.\d+)\*\*', plan, re.M)
        assert '- [x] Complete Phase 1:' in plan and unfinished is not None, 'Phase exit incomplete'
        assert '**Next item: ' + unfinished.group(1) + ' ' in plan, 'Next item inconsistent'
    assert 'Workflow draft CRUD, publication, manual/webhook triggers, approval decisions, cancellation, run list/detail (task6.1) and redacted run trace payloads (task6.2) are implemented; the read-and-operate console consumes them (tasks6.3–6.12).' in (ROOT/'API_DOCUMENTATION.md').read_text(), 'API status drift'


def main():
    text = DOC.read_text()
    validate(text)
    print('PASS: five screens, API dependencies, six statuses, thirteen trace fields, fixtures, twelve test cases and Phase 1 consistency')
    mutations = [
        ('missing screen', '| U02 |', '| U99 |'),
        ('missing status', '| queued |', '| unknown |'),
        ('missing trace field', '| resolved_input |', '| omitted |'),
        ('missing conflict reconciliation', 'disappearance alone does not mean approved', 'disappearance means approved'),
        ('broken source link', '(source-review/brief.txt)', '(missing.md)'),
        ('missing fixed API dependency', '| GET /runs/{runId} |', '| GET /removed |'),
        ('lost token boundary', '**Keep the demo token in memory only.**', 'Persist token.'),
        ('unknown task', 'Tasks 3.4, 6.3;', 'Tasks 99.99;'),
    ]
    for label, old, new in mutations:
        assert old in text
        try:
            validate(text.replace(old, new))
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print('9 checks passed. No browser, accessibility or runtime API tests run.')


if __name__ == '__main__':
    main()
