"""Validate state design tables and mutations, not application behavior."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/STATE_TRANSITIONS.md'


def section(text, heading):
    return text.split('## ' + heading + '\n', 1)[1].split('\n## ', 1)[0]


def validate(text, model):
    for link in re.findall(r'\]\(([^)]+)\)', text):
        assert (DOC.parent / link).is_file(), 'Broken link: ' + link
    vocabulary = {}
    for line in section(text, 'State vocabulary').splitlines():
        if line.startswith('| ') and not line.startswith(('| Entity', '| ---')):
            entity, states, terminal = [v.strip() for v in line.strip('|').split('|')]
            vocabulary[entity] = (set(states.split(', ')), set() if terminal == 'none' else set(terminal.split(', ')))
    expected = {'Run', 'Step', 'Attempt', 'Job', 'Approval'}
    assert set(vocabulary) == expected, 'Entity vocabulary mismatch'
    api = (ROOT / 'docs/source-review/pack/docs/API_CONTRACT.md').read_text()
    fixed = set(re.findall(r'`(\w+)`', re.search(r'The run status set: (.+)', api).group(1)))
    assert vocabulary['Run'][0] == fixed, 'Fixed run status mismatch'
    required = {
        'Run': {'accept', 'start', 'cancel_queued', 'continue', 'wait_delay', 'wait_retry', 'resume_work', 'request_approval', 'finish', 'fail', 'request_cancel', 'settle_cancel', 'approve_continue', 'approve_finish', 'reject', 'cancel_wait'},
        'Step': {'allocate', 'complete', 'fail', 'yield', 'retry_due', 'delay_due', 'decide', 'recover', 'cancel', 'exhaust'},
        'Attempt': {'prepare', 'valid_result', 'invalid_or_error', 'abandoned'},
        'Job': {'enqueue', 'claim', 'renew', 'reclaim', 'schedule', 'pause_or_finish', 'cancel', 'cancel_recovered', 'approve'},
        'Approval': {'request', 'approve', 'reject', 'cancel_close'},
    }
    table_names = {'Run': 'runs', 'Step': 'steps', 'Attempt': 'step_attempts', 'Job': 'queue_jobs', 'Approval': 'approvals'}
    for entity, (states, terminal) in vocabulary.items():
        assert terminal <= states, 'Unknown terminal state'
        rows = []
        for line in section(text, entity + ' transitions').splitlines():
            if line.startswith('| ') and not line.startswith(('| From', '| ---')):
                rows.append([c.strip() for c in line.strip('|').split('|')])
        events, destinations = set(), set()
        for src, event, dst, guard in rows:
            assert src == 'new' or src in states, 'Unknown source state'
            assert dst in states, 'Unknown target state'
            assert src not in terminal, 'Terminal state reopened'
            assert guard, 'Missing guard/effect'
            events.add(event)
            destinations.add(dst)
        assert required[entity] <= events, 'Missing required event: ' + entity
        assert destinations == states, 'Unreachable documented state: ' + entity
        model_section = section(model, table_names[entity])
        status_row = re.search(r'^\| status \| status \| (.+?) \|$', model_section, re.M).group(1)
        schema_states = set(status_row.split(';', 1)[0].rstrip('.').split(', '))
        assert schema_states == states, 'Schema status drift: ' + entity
    for marker in ('| wait_reason? |', 'closed requires closure time/reason', 'running/uncertain attempts keep both NULL'):
        assert marker in model, 'Missing schema invariant: ' + marker
    for marker in ('retry exhaustion', 'first committed transition', 'No new attempt may start',
                   'without resending the request', 'D01 resolved on 2026-09-28 (preserve supplied graph and enforce approval gates)', 'never push or publish',
                   'Network calls happen outside database transactions', 'uncertain', 'ai_repair_count = 1'):
        assert marker in text, 'Missing boundary: ' + marker
    plan = (ROOT / 'PROJECT_PLAN.md').read_text()
    tasks = set(re.findall(r'\*\*(\d+\.\d+)\*\*', plan))
    req = (ROOT / 'docs/REQUIREMENTS.md').read_text()
    verifications = set(re.findall(r'^\| (V\d+) \|', req, re.M))
    rows = re.findall(r'^\| S\d+ .+$', text, re.M)
    assert len(rows) == 12, 'Missing scenario'
    for row in rows:
        assert set(re.findall(r'\b\d+\.\d+\b', row)) <= tasks, 'Unknown task reference'
        assert set(re.findall(r'\bV\d+\b', row)) <= verifications, 'Unknown verification reference'


def main():
    text, model = DOC.read_text(), (ROOT / 'docs/DATA_MODEL.md').read_text()
    validate(text, model)
    print('PASS: five state sets, transition guards/membership/terminal absorption, schema, links and 12 scenarios')
    mutations = [
        ('terminal reopening', '| running | continue | running |', '| succeeded | continue | running |'),
        ('unknown state', '| running | wait_delay | running |', '| running | wait_delay | sleeping |'),
        ('missing exhaustion event', '| waiting | exhaust | failed |', '| waiting | omitted | failed |'),
        ('missing cancellation event', '| running | settle_cancel | cancelled |', '| running | omitted | cancelled |'),
        ('missing recovery event', '| leased | reclaim | leased |', '| leased | omitted | leased |'),
        ('broken source link', '(source-review/pack/docs/API_CONTRACT.md)', '(missing.md)'),
        ('unknown test owner', 'Tasks 4.1, 4.2;', 'Tasks 99.99;'),
    ]
    for label, old, new in mutations:
        assert old in text
        try:
            validate(text.replace(old, new), model)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    old = 'pending, approved, rejected, closed;'
    assert old in model
    try:
        validate(text, model.replace(old, 'pending, approved, rejected;'))
    except AssertionError:
        print('PASS: rejects schema status drift')
    else:
        raise AssertionError('Undetected schema drift')
    print('9 checks passed. No runtime tests run.')


if __name__ == '__main__':
    main()
