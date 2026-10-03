"""Offline consistency checks for the final (task 8.2) state of the API and design
documents: status blocks, stale wording, verified route index, idempotency and
limitation sections, change log. Does not start services or run tests."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = {name: ROOT / name for name in (
    'API_DOCUMENTATION.md', 'docs/ARCHITECTURE.md', 'docs/STATE_TRANSITIONS.md',
    'docs/RECOVERY.md', 'docs/DATA_MODEL.md', 'docs/WORKFLOW_SEMANTICS.md')}
TESTS = ROOT / 'backend/src/test/java'
# Present-tense claims that became false once Phases 4-7 were done.
STALE = ('remain phase6', 'remains phase6', 'remains later tasks', 'remain later tasks', 'remain later console tasks',
         'will be implemented in later tasks', 'placeholders until', 'not implemented endpoints', 'remain pending',
         'run history remains later work', 'remains planned', 'remains outstanding', 'remain outstanding',
         'later binding', 'remains later work', 'remain their implementation tasks', 'remains implementation work')
IDEMPOTENCY = ('`{run_id}:{step_sequence}`', 'same key', 'new key', 'AI provider calls carry no key',
               'rejected at publish', 'Receiver requirement', 'Timeouts are ambiguous', 'without resending',
               'billed twice', 'not deduplicated', 'returns 409', 'never overwrites')
LIMITS = {'API_DOCUMENTATION.md': ('Single management token', 'No trigger deduplication', 'Exactly-once depends on the receiver',
                                   'Cancellation is cooperative', 'One active worker'),
          'docs/ARCHITECTURE.md': ('One active worker', 'Exactly-once depends on the receiver', 'billed twice',
                                   'Local, single-tenant deployment', 'Latent clock skew')}
TEMPLATE = ('\n## Required entry for each route\n', '\n## Documentation change log\n')


def slug(heading):
    return re.sub(r'[^a-z0-9 -]', '', heading.lower()).replace(' ', '-')


def section(text, name):
    assert '\n## ' + name + '\n' in text, 'Missing section: ' + name
    return text.split('\n## ' + name + '\n', 1)[1].split('\n## ', 1)[0]


def validate(texts, test_classes, report):
    for name, text in texts.items():
        head = '\n'.join(text.splitlines()[:6])
        assert '## Current status' in head and 'Final as of task 8.2 (2026-09-28)' in head, 'Missing status block: ' + name
        scan = text
        if name == 'API_DOCUMENTATION.md':  # the route template legitimately says "Planned / Not run"
            start, end = scan.index(TEMPLATE[0]), scan.index(TEMPLATE[1])
            scan = scan[:start] + scan[end:]
        lowered = scan.lower()
        for phrase in STALE:
            assert phrase not in lowered, f'Stale wording in {name}: {phrase}'
        for link in re.findall(r'\]\(([^)]+)\)', text):
            if re.match(r'https?://', link):
                continue
            target, _, anchor = link.partition('#')
            path = (DOCS[name].parent / target) if target else DOCS[name]
            assert path.exists(), f'Broken link in {name}: {link}'
            if anchor:
                assert anchor in {slug(h) for h in re.findall(r'^#+ (.+)$', path.read_text(), re.M)}, \
                    f'Broken anchor in {name}: {link}'
    api = texts['API_DOCUMENTATION.md']
    rows = re.findall(r'^\| (GET|POST|PUT) \| (/\S*) \| [^|]+ \| ([^|]+) \| ([^|]+) \|$', section(api, 'Route index'), re.M)
    assert len(rows) == 16, 'Route index must list 16 routes'
    for method, path, status, tests in rows:
        assert status.startswith('Verified'), f'Route not verified: {method} {path}'
        for cls in re.findall(r'\w+Test\b', tests):
            assert cls in test_classes, f'Unknown test class for {method} {path}: {cls}'
    section(api, 'Idempotency and duplicate requests')
    idem = section(texts['docs/RECOVERY.md'], 'Idempotency assumptions')
    for marker in IDEMPOTENCY:
        assert marker in idem, 'Missing idempotency statement: ' + marker
    for name, markers in LIMITS.items():
        limits = section(texts[name], 'Known limitations')
        assert 'VERIFICATION_REPORT.md' in limits, 'Limitations must link the verification report: ' + name
        for marker in markers:
            assert marker in limits, f'Missing limitation in {name}: {marker}'
    for marker in ('Exactly-once depends on the receiver', 'One active worker', 'billed twice', 'Latent clock skew'):
        assert marker in report, 'Limitation not in the canonical report: ' + marker
    log = section(api, 'Documentation change log')
    table = log[log.index('| Date |'):].rstrip('\n').split('\n')
    assert all(line.startswith('| ') for line in table), 'Change log is not one contiguous table'
    assert any(line.startswith('| 2026-09-28 | Task 8.2') for line in table), 'Change log lacks task 8.2 row'
    for placeholder in ('_pending_', 'TODO', 'TBD'):
        assert all(placeholder not in t for t in texts.values()), 'Placeholder left: ' + placeholder


def main():
    texts = {name: path.read_text() for name, path in DOCS.items()}
    test_classes = {p.stem for p in TESTS.rglob('*Test.java')}
    report = (ROOT / 'docs/VERIFICATION_REPORT.md').read_text()
    validate(texts, test_classes, report)
    print(f'PASS: {len(texts)} documents have a task 8.2 status block, no stale present-tense wording, links/anchors resolve')
    print(f'PASS: 16 routes verified with existing test classes; {len(IDEMPOTENCY)} idempotency statements; '
          'limitation sections link the report; change log is one table with the 8.2 row')
    mutations = [
        ('missing status block', 'docs/STATE_TRANSITIONS.md', 'Final as of task 8.2 (2026-09-28)', 'Final'),
        ('stale wording', 'docs/RECOVERY.md', 'No protocol change was needed.', 'Console data remain Phase6.'),
        ('stale wording in API doc', 'API_DOCUMENTATION.md', 'run history is served by `GET /runs` (task6.1).', 'run history remains later work.'),
        ('unverified route', 'API_DOCUMENTATION.md', '| Verified (task 3.8) |', '| Task3.8 |'),
        ('unknown test class', 'API_DOCUMENTATION.md', '| PublicationMySqlTest |', '| PublishMySqlTest |'),
        ('removed idempotency statement', 'docs/RECOVERY.md', '**Inbound triggers are not deduplicated.**', '**Inbound triggers.**'),
        ('removed limitation', 'API_DOCUMENTATION.md', '**Cancellation is cooperative.**', '**Cancellation.**'),
        ('broken change-log table', 'API_DOCUMENTATION.md', '\n| 2026-09-28 | Task6.1:', '\n\n| 2026-09-28 | Task6.1:'),
        ('broken link', 'docs/ARCHITECTURE.md', '(phase6-verification.txt)', '(phase6-results.txt)'),
        ('broken anchor', 'API_DOCUMENTATION.md', '(docs/RECOVERY.md#idempotency-assumptions)', '(docs/RECOVERY.md#idempotency)'),
    ]
    for label, name, old, new in mutations:
        assert old in texts[name], 'Absent mutation target: ' + label
        try:
            validate(dict(texts, **{name: texts[name].replace(old, new, 1)}), test_classes, report)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print(f'{2 + len(mutations)} checks passed. No services started; documents only.')


if __name__ == '__main__':
    main()
