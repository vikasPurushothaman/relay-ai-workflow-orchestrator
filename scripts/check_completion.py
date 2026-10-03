"""Completion review (task 8.8): checklist, requirement coverage, task records, completion
log and open items in PROJECT_PLAN.md agree with docs/REQUIREMENTS.md and the
verification report. Documents only; starts nothing."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_PHASES = range(1, 9)  # Phases 1-8 must be complete for the application-ready milestone
STALE_BLOCKERS = ('engine is stopped', 'engine the stopped', 'remain route tasks', 'not started', 'will be implemented',
                  'is pending', 'awaits')


def section(text, heading):
    assert '\n## ' + heading + '\n' in text, 'Missing section: ' + heading
    return text.split('\n## ' + heading + '\n', 1)[1].split('\n## ', 1)[0]


def order(task):
    return tuple(int(p) for p in task.split('.'))


def validate(plan, requirements, report):
    done = set(re.findall(r'^- \[x\] \*\*(\d+\.\d+)\*\*', plan, re.M))
    todo = set(re.findall(r'^- \[ \] \*\*(\d+\.\d+)\*\*', plan, re.M))
    required_open = sorted((t for t in todo if order(t)[0] in REQUIRED_PHASES), key=order)
    assert not required_open, 'Required checklist items not complete: ' + ', '.join(required_open)
    first_open = min(todo, key=order) if todo else None
    assert first_open is None or f'**Next item: {first_open} ' in plan, 'Next item must be the first open item ' + str(first_open)
    # Requirements: every referenced task in phases 1-8 is done; statuses in the report match.
    matrix = section(requirements, 'Required-feature matrix')
    covered, status = set(), {}
    for rid, impl, ver in re.findall(r'^\| (R\d+) \| .*? \| (.*?) \| (.*?) \|$', matrix, re.M):
        tasks = set(re.findall(r'\b\d+\.\d+\b', impl + ' ' + ver.split(';')[0]))
        covered |= set(re.findall(r'V\d+', ver))
        unknown = tasks - done - todo
        assert not unknown, f'{rid} references unknown tasks {sorted(unknown)}'
        late = sorted((t for t in tasks - done if order(t)[0] in REQUIRED_PHASES), key=order)
        assert not late, f'{rid} has incomplete required tasks {late}'
        status[rid] = sorted(tasks - done, key=order)
    assert covered == {f'V{i:02}' for i in range(1, 24)}, 'Uncovered verification cases: ' + str(sorted({f'V{i:02}' for i in range(1, 24)} - covered))
    coverage = section(report, 'Requirement coverage')
    complete = [r for r, open_tasks in status.items() if not open_tasks]
    assert complete == [f'R{i:02}' for i in range(1, len(complete) + 1)], 'Complete requirements are not a leading range'
    assert f'R01–R{len(complete) - 1:02}' in coverage and f'| R{len(complete):02} ' in coverage, 'Report coverage rows do not match the complete range'
    for rid, open_tasks in status.items():
        if open_tasks == ['9.3']:
            assert re.search(r'\| ' + rid + r' .*Complete except the 9\.3 item', coverage), rid + ' must be reported complete except 9.3'
        elif open_tasks:
            assert re.search(r'\| ' + rid + r' .*Open, Phase 9', coverage), rid + ' must be reported open (Phase 9)'
    # Task records: all complete, none pending.
    records = re.split(r'\n(?=### )', section(plan, 'Task records'))
    for record in records[1:]:
        header = record.split('\n', 1)[0]
        assert re.search(r'complete', header, re.I) and 'in progress' not in header, 'Task record not complete: ' + header
        assert not re.search(r':\*\* _pending_', record), 'Pending field in: ' + header
    # Completion log: one contiguous table, oldest first, with rows for every Phase 7 and 8 item.
    log = section(plan, 'Completion log')
    body = log[log.index('| Date |'):].strip().split('\n')
    assert all(line.startswith('|') for line in body), 'Completion log is not one contiguous table'
    dates = [line.split('|')[1].strip() for line in body[2:]]
    assert dates == sorted(dates), 'Completion log rows are not oldest first'
    for item in [f'7.{i}' for i in range(1, 10)] + [f'8.{i}' for i in range(1, 9)]:
        assert any(re.search(r'\| Task ' + re.escape(item) + r'\b', line) for line in body), 'No completion-log row for ' + item
    assert plan.index('\n## Completion log\n') < plan.index('\n## Blockers and open decisions\n'), 'Blockers must follow the log'
    blockers = section(plan, 'Blockers and open decisions').lower()
    for phrase in STALE_BLOCKERS:
        assert phrase not in blockers, 'Stale blocker wording: ' + phrase
    assert 'application-ready milestone reached' in plan.lower(), 'Milestone statement missing'
    return len(done), len(status), len(records) - 1, len(body) - 2


def main():
    plan = (ROOT / 'PROJECT_PLAN.md').read_text()
    requirements = (ROOT / 'docs/REQUIREMENTS.md').read_text()
    report = (ROOT / 'docs/VERIFICATION_REPORT.md').read_text()
    done, reqs, records, rows = validate(plan, requirements, report)
    print(f'PASS: {done} checklist items done (phases 1-8 complete, next item is the first open one); '
          f'{reqs} requirements reviewed with statuses matching the report; all 23 V cases covered')
    print(f'PASS: {records} task records complete with no pending fields; completion log is one ordered table of {rows} rows '
          'with Phase 7/8 rows; blockers follow the log with no stale wording; milestone recorded')
    log_row = next(l for l in plan.split('\n') if l.startswith('| 2026-09-28 | Task 8.3 complete'))
    mutations = [
        ('unchecked required item', plan, lambda p: p.replace('- [x] **8.4**', '- [ ] **8.4**', 1), 0),
        ('requirement citing unknown task', requirements, lambda r: r.replace('| 2.8, 3.5, 3.9 |', '| 2.8, 3.5, 3.99 |', 1), 1),
        ('uncovered V case', requirements, lambda r: r.replace('3.10, 7.1, 7.2, 7.8; V01 |', '3.10, 7.1, 7.2, 7.8; V02 |', 1), 1),
        ('pending task record', plan, lambda p: p.replace('- **Limitations/blockers:** The live browser suite was not re-run', '- **Limitations/blockers:** _pending_', 1), 0),
        ('split completion log', plan, lambda p: p.replace(log_row, log_row + '\n', 1), 0),
        ('missing Phase 8 row', plan, lambda p: p.replace(log_row + '\n', '', 1), 0),
        ('stale blocker line', plan, lambda p: p.replace('\n## Blockers and open decisions\n', '\n## Blockers and open decisions\n\n- Docker CLI works but the engine is stopped.\n', 1), 0),
        ('report status mismatch', report, lambda r: r.replace('Complete except the 9.3 item', 'Complete', 1), 2),
    ]
    for label, source, change, which in mutations:
        changed = change(source)
        assert changed != source, 'Absent mutation target: ' + label
        docs = [plan, requirements, report]
        docs[which] = changed
        try:
            validate(*docs)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print(f'{2 + len(mutations)} checks passed. Documents only.')


if __name__ == '__main__':
    main()
