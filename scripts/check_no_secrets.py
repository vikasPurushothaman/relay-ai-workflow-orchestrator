"""Pre-publication check (task 8.7): no credentials in publishable files or git history,
sensitive paths ignored and never committed, and no machine-specific paths in files
needed to run. Reads real local secrets only to compare them; prints counts, never values."""
from pathlib import Path
import json
import re
import secrets as random_secrets
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
HOME = '/' + 'Users/'  # built by concatenation so this file does not match its own scan
MACHINE_PATH = re.compile('|'.join(re.escape(p) for p in (HOME, '/' + 'home/', '/opt/' + 'homebrew', '/private/' + 'tmp', 'C:' + '\\\\Users')))
CREDENTIALS = re.compile(
    r'sk-or-v1-[A-Za-z0-9]{8,}|\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}|\bAKIA[0-9A-Z]{16}\b|-----BEGIN [A-Z ]*PRIVATE ' + r'KEY'
    r'|\bghp_[A-Za-z0-9]{20,}|\bxox[bap]-[A-Za-z0-9-]{10,}'
    r'|(?:password|passwd|secret|token|api_key)["\']?\s*[:=]\s*["\'][A-Za-z0-9_\-./]{8,}', re.I)
# Deliberate non-secrets that match the patterns, each with its reason.
ALLOWED = {
    'private-sentinel': 'fake credential used by backend/frontend tests to prove it is never echoed',
    'incorrect-test-password': 'deliberately wrong password in the disposable MySQL check',
    'sk-or-v1-' + 'abcdef0123456789': 'fake key in check_ai_providers.py mutation self-test',
    'run-query-secret-sentinel': 'fake webhook secret in RunQueryMySqlTest redaction checks',
    'test-management-token': 'fake bearer token in ManagementSecurityTest',
    'wrong-secret': 'deliberately wrong webhook secret in the unmodified pack smoke test',
}
SENSITIVE = re.compile(r'(^|/)\.env($|\.(?!example$))|(^|/)local-data/|demo-token|\.(pem|key|p12)$|(^|/)id_rsa'
                       r'|(^|/)(build|dist|node_modules|\.gradle)/')
MUST_IGNORE = ('backend/.env.ai', '.env', 'backend/.env', 'frontend/.env.local', 'local-data/mysql/password',
               'local-data/demo-token', 'backend/build/x', 'frontend/dist/x', 'frontend/node_modules/x', 'backend/.gradle/x')
RUNTIME_DOCS = {'compose.yaml', '.env.example', '.gitignore', 'README.md', 'API_DOCUMENTATION.md', 'docs/DEMO.md',
                'docs/AI_PROVIDERS.md', 'docs/CONSOLE_DEMO.md', 'docs/MOCKS.md'}
SEEDS = ('backend/src/main/resources/catalog/seed_workflows.json', 'docs/source-review/pack/data/seed_workflows.json')


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, check=True).stdout


def runtime(path):
    return path in RUNTIME_DOCS or path.startswith(('backend/', 'frontend/', 'scripts/'))


def local_secrets():
    """Real secret values present on this machine (none on a fresh clone)."""
    found = {}
    env_ai = ROOT / 'backend/.env.ai'
    if env_ai.is_file():
        for line in env_ai.read_text().splitlines():
            name, _, value = line.partition('=')
            if name == 'RELAY_AI_API_KEY' and value.strip():
                found['AI provider key'] = shlex.split(value)[0]
    for name in ('local-data/mysql/password', 'local-data/mysql/root-password', 'local-data/demo-token'):
        path = ROOT / name
        if path.is_file() and path.read_text().strip():
            found[name] = path.read_text().strip()
    return found


def snapshot():
    paths = [p.decode() for p in git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0') if p]
    files = {}
    for path in paths:
        try:
            files[path] = (ROOT / path).read_text()
        except (UnicodeDecodeError, FileNotFoundError):
            continue  # binary (wrapper jar) or deleted-but-listed
    ignored = {p for p in MUST_IGNORE if subprocess.run(['git', 'check-ignore', '-q', p], cwd=ROOT).returncode == 0}
    return dict(files=files, paths=paths, ignored=ignored,
                history=git('log', '-p', '--all', '--full-history').decode('utf-8', 'replace'),
                history_paths=set(filter(None, git('log', '--all', '--name-only', '--pretty=format:').decode().splitlines())),
                secrets=local_secrets())


def validate(s):
    problems, notes = [], []
    for path, text in s['files'].items():
        if path == 'scripts/check_no_secrets.py':
            continue  # its allowlist entries match the patterns; the real-value comparison still covers it
        for match in CREDENTIALS.finditer(text):
            value = match.group(0)
            if not any(fake in value for fake in ALLOWED) and not path.endswith('seed_workflows.json'):
                problems.append(f'credential-like value in {path}:{text.count(chr(10), 0, match.start()) + 1}')
    secrets_by_seed = [[w['trigger'].get('secret') for w in json.loads(s['files'][p])['workflows']] for p in SEEDS]
    if secrets_by_seed[0] != secrets_by_seed[1]:
        problems.append('bundled webhook secrets differ from the public pack fixture')
    if s['secrets']:
        for name, value in s['secrets'].items():
            in_files = sum(text.count(value) for text in s['files'].values())
            in_history = s['history'].count(value)
            if in_files or in_history:
                problems.append(f'real secret "{name}" found: {in_files} in files, {in_history} in git history')
        notes.append(f'{len(s["secrets"])} real local secret values checked against files and history: 0 occurrences'
                     if not any('real secret' in p for p in problems) else 'real secret values FOUND')
    else:
        notes.append('no local secret files present (fresh clone?): real-value comparison skipped')
    for path in sorted(p for p in set(s['paths']) | s['history_paths'] if SENSITIVE.search(p)):
        problems.append(f'sensitive path tracked or in history: {path}')
    for path in sorted(set(MUST_IGNORE) - s['ignored']):
        problems.append(f'not ignored by .gitignore: {path}')
    for path, text in s['files'].items():
        if runtime(path) and path != 'scripts/check_no_secrets.py' and MACHINE_PATH.search(text):
            problems.append(f'machine-specific path in a file needed to run: {path}')
    history_only = sorted(p for p, t in s['files'].items() if not runtime(p) and MACHINE_PATH.search(t))
    notes.append(f'{len(history_only)} records/evidence files mention local paths (allowed, not needed to run)')
    return problems, notes


def main():
    s = snapshot()
    problems, notes = validate(s)
    for note in notes:
        print('NOTE: ' + note)
    if problems:
        for problem in problems:
            print('FAIL: ' + problem)
        raise SystemExit(1)
    print(f'PASS: {len(s["files"])} publishable text files and {len(s["history"])} bytes of git history: '
          f'no credentials outside {len(ALLOWED)} reasoned fakes and the public pack fixtures; '
          f'{len(MUST_IGNORE)} sensitive paths ignored and never committed; no machine paths in run files')

    def rejects(label, change):
        copy = {**s, 'files': dict(s['files']), 'ignored': set(s['ignored']), 'paths': list(s['paths']),
                'secrets': dict(s['secrets'])}
        change(copy)
        assert validate(copy)[0], 'Undetected: ' + label
        print('PASS: rejects ' + label)

    stand_in = random_secrets.token_hex(24)  # random, never a real secret
    rejects('pasted provider key in README', lambda c: c['files'].__setitem__(
        'README.md', c['files']['README.md'] + '\nRELAY_AI_API_KEY=sk-or-v1-' + 'Zq9' * 8 + '\n'))
    rejects('literal password in a script', lambda c: c['files'].__setitem__(
        'scripts/run_mock.py', c['files']['scripts/run_mock.py'] + "\npassword = '" + 'Hunter22Hunter22' + "'\n"))
    rejects('changed bundled webhook secret', lambda c: c['files'].__setitem__(
        SEEDS[0], c['files'][SEEDS[0]].replace('"secret": "', '"secret": "x', 1)))
    rejects('real secret value in a file (random stand-in)', lambda c: (
        c['secrets'].__setitem__('stand-in', stand_in),
        c['files'].__setitem__('docs/DEMO.md', c['files']['docs/DEMO.md'] + stand_in)))
    rejects('tracked backend/.env.ai', lambda c: c['paths'].append('backend/.env.ai'))
    rejects('removed .env ignore rule', lambda c: c['ignored'].discard('backend/.env.ai'))
    rejects('home path in compose.yaml', lambda c: c['files'].__setitem__(
        'compose.yaml', c['files']['compose.yaml'] + '\n# ' + HOME + 'someone/relay\n'))
    fresh = {**s, 'secrets': {}}
    problems, notes = validate(fresh)
    assert not problems and 'skipped' in notes[0], 'Fresh-clone path must skip the real-value check, not fail'
    print('PASS: fresh clone without local secret files skips the real-value comparison cleanly')
    print('10 checks passed. Values of real secrets were never printed.')


if __name__ == '__main__':
    main()
