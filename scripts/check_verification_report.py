"""Offline consistency checks for docs/VERIFICATION_REPORT.md against the saved
Phase 7 evidence. Does not start services or re-run any verification."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/VERIFICATION_REPORT.md'
EVIDENCE = [ROOT / 'docs/phase7-verification.txt', ROOT / 'docs/duplication-check-results.txt']

SECTIONS = ('Summary', 'Environment', 'Automated tests (7.7)', 'Contract and scenarios (7.1, 7.2)',
            'Crash and recovery evidence (7.3, 7.4)', 'Failure handling (7.5)', 'Safety controls (7.6)',
            'Fresh startup and seeding (7.8)', 'Failures found during verification', 'Real-provider usage',
            'Known limitations', 'Not verified or out of scope', 'Reproducing')
# Report phrase -> text that must appear in the evidence for that claim.
CLAIMS = {
    '28 passed, 0 warnings, 0 failed': '28 passed, 0 warnings, 0 failed',
    '49 passed, 0 failed, 0 AI-quality mismatches': '49 passed, 0 failed, 0 AI-quality mismatches',
    '48 passed, 0 failed': '48 passed, 0 failed',
    '224 tests, 0 failures, 0 errors, 0 skipped': '224 tests, 0 failures, 0 errors, 0 skipped',
    '32 tests, 0 failures, 0 errors, 0 skipped': '32 tests (real MySQL via Testcontainers',
    '58/58': 'tests 58, pass 58, fail 0',
    '362 tests, 0 failures, 0 skipped': '362 tests, 0 failures, 0 skipped',
    '15 tests, OK': 'Ran 15, OK',
    'executed 3, replays absorbed 1, exit 0': 'replays absorbed: 1',
    '7/7 characterization tests': 'Ran 7 tests, OK',
    '1613 reported tokens': '1613 tokens',
    '`created=4, preserved=0`': 'created=4, preserved=0',
    '`created=0, preserved=4`': 'created=0, preserved=4',
    'stopped the run at exactly 12/12': 'check_order, 12/12',
}
LIMITS = ('Exactly-once depends on the receiver', 'Timeouts are ambiguous', 'billed twice', 'One active worker',
          'single samples', 'Payload-controlled recipients', 'replies in prose', 'Latent clock skew',
          'shorter than 8 characters', 'Chromium only', 'Pack licensing')


def slug(heading):
    return re.sub(r'[^a-z0-9 -]', '', heading.lower()).replace(' ', '-')


def validate(text, evidence, plan):
    for section in SECTIONS:
        assert '\n## ' + section + '\n' in text, 'Missing section: ' + section
    anchors = {slug(h) for h in re.findall(r'^#+ (.+)$', text, re.M)}
    for link in re.findall(r'\]\(([^)]+)\)', text):
        target, _, anchor = link.partition('#')
        if target:
            assert (DOC.parent / target).exists(), 'Broken link: ' + link
        else:
            assert anchor in anchors, 'Broken anchor: ' + link
    for run_id in set(re.findall(r'run_[0-9a-f]{32}', text)):
        assert run_id in evidence, 'Run ID not in evidence: ' + run_id
    for ref in set(re.findall(r'\b(?:shp|ref)_[0-9a-f]{10}\b', text)):
        assert ref in evidence, 'Effect ID not in evidence: ' + ref
    for pair in set(re.findall(r'\b\d+\+\d+\b', text)):
        assert pair + ' ' in evidence or pair + ')' in evidence, 'Token usage not in evidence: ' + pair
    for count in set(re.findall(r'\b(\d+) passed\b', text)):
        assert count + ' passed' in evidence, 'Pass count not in evidence: ' + count
    for claim, source in CLAIMS.items():
        assert claim in text, 'Missing claim: ' + claim
        assert source in evidence, 'Claim unsupported by evidence: ' + claim
    summary = text.split('\n## Summary\n', 1)[1].split('\n## ', 1)[0]
    rows = re.findall(r'^\| (7\.\d) \|', summary, re.M)
    assert rows == [f'7.{i}' for i in range(1, 9)], 'Summary must list 7.1-7.8 once each'
    for item in rows:
        assert re.search(r'^- \[x\] \*\*' + re.escape(item) + r'\*\*', plan, re.M), 'Plan item not complete: ' + item
    limits = text.split('\n## Known limitations\n', 1)[1].split('\n## ', 1)[0]
    for marker in LIMITS:
        assert marker in limits, 'Missing limitation: ' + marker
    assert 'must never push or publish' in text, 'Missing never-push statement'
    for placeholder in ('_pending_', 'TODO', 'TBD', 'XXX'):
        assert placeholder not in text, 'Placeholder left: ' + placeholder


def main():
    text = DOC.read_text()
    evidence = '\n'.join(p.read_text() for p in EVIDENCE)
    plan = (ROOT / 'PROJECT_PLAN.md').read_text()
    validate(text, evidence, plan)
    print(f'PASS: {len(SECTIONS)} sections, links/anchors resolve, run/effect IDs, token counts and pass counts found in evidence')
    print(f'PASS: {len(CLAIMS)} key claims supported, 7.1-7.8 listed and complete in the plan, {len(LIMITS)} limitations present')
    mutations = [
        ('broken link', '(duplication-check-results.txt)', '(missing-results.txt)'),
        ('broken anchor', '(#known-limitations)', '(#known-limits)'),
        ('invented run ID', 'run_0fe5eebd699e4317bdd5e4883633f8f2', 'run_0fe5eebd699e4317bdd5e4883633f8f3'),
        ('changed pass count', '49 passed, 0 failed, 0 AI-quality mismatches', '50 passed, 0 failed, 0 AI-quality mismatches'),
        ('changed token usage', '185+404', '185+405'),
        ('missing section', '\n## Real-provider usage\n', '\n## Provider notes\n'),
        ('removed limitation', 'Latent clock skew', 'Clock notes'),
        ('placeholder', '## Summary\n', '## Summary\n\nTODO\n'),
        ('missing summary row', '| 7.5 |', '| 7.x |'),
    ]
    for label, old, new in mutations:
        assert old in text, 'Absent mutation target: ' + label
        try:
            validate(text.replace(old, new, 1), evidence, plan)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    try:
        validate(text, evidence.replace('1613 tokens', '1600 tokens'), plan)
    except AssertionError:
        print('PASS: rejects claim unsupported by evidence')
    else:
        raise AssertionError('Undetected evidence drift')
    print(f'{2 + len(mutations) + 1} checks passed. No services started; results come from saved evidence.')


if __name__ == '__main__':
    main()
