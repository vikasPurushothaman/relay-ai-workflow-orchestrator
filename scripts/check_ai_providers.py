"""Offline consistency checks for docs/AI_PROVIDERS.md against the provider adapter,
helper scripts, .gitignore and saved evidence. Makes no provider call."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/AI_PROVIDERS.md'
JAVA = ROOT / 'backend/src/main/java'
SECTIONS = ('At a glance', 'Mock provider (default)', 'Real provider', 'Credential handling',
            'Switching providers', 'Error codes', 'Limitations')
SECRETS = (r'sk-or-v1-[A-Za-z0-9]{8,}', r'\bsk-(?!or-v1-)[A-Za-z0-9_-]{16,}', r'\b[0-9a-f]{32,}\b')
CODE = r'(?:ai|invalid|http)_[a-z0-9_]+'


def section(text, name):
    return text.split('\n## ' + name + '\n', 1)[1].split('\n## ', 1)[0]


def read_sources():
    provider = (JAVA / 'com/relay/ai/ConfiguredAiProvider.java').read_text()
    transport = (JAVA / 'com/relay/engine/HttpTransport.java').read_text()
    java = '\n'.join(p.read_text() for p in JAVA.rglob('*.java'))
    modes = set(re.findall(r'mode\.equals\("([a-z-]+)"\)', provider)) | {re.search(r'"RELAY_AI_MODE","([a-z-]+)"', provider).group(1)}
    billing = re.search(r'Set\.of\(("credit_balance_exhausted".*?)\)\.contains\(code\)', transport).group(1)
    codes = set(re.findall(r'"(' + CODE + r')"', java)) | {'ai_' + c for c in re.findall(r'"([a-z_]+)"', billing)}
    evidence = (ROOT / 'docs/phase5-verification.txt').read_text()
    return dict(
        modes=modes, codes=codes, billing=set(re.findall(r'"([a-z_]+)"', billing)),
        endpoints=set(re.findall(r'"(https://[^"]+)"', provider)),
        mock_base=re.search(r'"RELAY_AI_BASE_URL","([^"]+)"', provider).group(1),
        mock_model=re.search(r'\?"([a-z-]+)":m', provider).group(1),
        gradle=(ROOT / 'backend/build.gradle').read_text(),
        gitignore=(ROOT / '.gitignore').read_text().splitlines(),
        openrouter_live='Real OpenRouter / openai/gpt-5-mini returned schema-valid' in evidence,
        openai_quota='credit_balance_exhausted' in evidence)


def validate(text, src):
    for name in SECTIONS:
        assert '\n## ' + name + '\n' in text, 'Missing section: ' + name
    glance = section(text, 'At a glance')
    rows = {m: row for m, row in re.findall(r'^\| `([a-z-]+)`[^|]*\|(.*)$', glance, re.M)}
    assert set(rows) == src['modes'], f'Modes {sorted(rows)} differ from code {sorted(src["modes"])}'
    for url in re.findall(r'https://[^\s`|]+', glance):
        assert url in src['endpoints'], 'Endpoint not in code: ' + url
    for url in src['endpoints']:
        assert url in glance, 'Fixed endpoint missing: ' + url
    assert src['mock_base'] in rows['mock-http'] and src['mock_model'] in rows['mock-http'], 'Mock defaults drifted'
    assert '`sk-or-v1-`' in rows['openrouter'], 'OpenRouter key prefix rule missing'
    # Verification status must match the evidence, not overclaim.
    assert 'Live-verified' in rows['openrouter'] and src['openrouter_live'], 'OpenRouter live status unsupported'
    assert 'live generation not verified' in rows['openai'] and src['openai_quota'], 'OpenAI live status must be stated as not verified'
    assert not re.search(r'openai[^\n]*live[- ]verified', rows['openai'], re.I), 'OpenAI must not be claimed live-verified'
    # Every quoted code exists in the source (billing codes are built as ai_ + provider code).
    for code in set(re.findall(r'`(' + CODE + r')`', text)):
        assert code in src['codes'] or re.fullmatch(r'http_\d{3}', code), 'Code not in source: ' + code
    for code in set(re.findall(r'`([a-z_]+)`', '\n'.join(rows.values()))) - src['modes']:
        assert code in src['billing'] or code in src['codes'], 'Provider code not in source: ' + code
    # Commands and helper options exist.
    for script in set(re.findall(r'python3 (scripts/[a-z_]+\.py)', text)):
        assert (ROOT / script).is_file(), 'Missing script: ' + script
    for provider in re.findall(r'--provider ([a-z]+)', text):
        assert provider in src['modes'], 'Unknown --provider value: ' + provider
    assert "tasks.register('liveAiTest'" in src['gradle'] and '`liveAiTest`' in text, 'Live probe task missing'
    # Credential rules and repository hygiene.
    creds = section(text, 'Credential handling')
    for marker in ('`backend/.env.ai`', 'ignored by git', '`git check-ignore -v backend/.env.ai`', '`VITE_*`', 'in chat'):
        assert marker in creds, 'Missing credential rule: ' + marker
    assert '.env.*' in src['gitignore'] and '!.env.example' in src['gitignore'], '.gitignore no longer covers .env files'
    for pattern in SECRETS:
        assert not re.search(pattern, text), 'Credential-like value: ' + pattern
    for link in re.findall(r'\]\(([^)]+)\)', text):
        if not link.startswith('http'):
            assert (DOC.parent / link.partition('#')[0]).exists(), 'Broken link: ' + link


def main():
    text = DOC.read_text()
    src = read_sources()
    validate(text, src)
    ignored = subprocess.run(['git', 'check-ignore', '-q', 'backend/.env.ai'], cwd=ROOT).returncode == 0
    assert ignored, 'backend/.env.ai is not ignored by git'
    print(f'PASS: {len(SECTIONS)} sections; modes {sorted(src["modes"])} and {len(src["endpoints"])} fixed endpoints match the adapter; '
          'mock defaults, key-prefix rule and verification status match code and evidence')
    print(f'PASS: every quoted code exists in source ({len(src["codes"])} known); scripts and liveAiTest exist; '
          'credential rules present; no key-like values; backend/.env.ai ignored by git')
    mutations = [
        ('wrong endpoint', 'https://openrouter.ai/api/v1/chat/completions', 'https://openrouter.ai/api/v2/chat'),
        ('unknown mode', '| `openai` |', '| `anthropic` |'),
        ('invented error code', '`ai_payment_required`', '`ai_payment_declined`'),
        ('nonexistent script', 'python3 scripts/verify_live_ai.py', 'python3 scripts/verify_ai_live.py'),
        ('pasted key-like value', 'starts with `sk-or-v1-`', 'starts with `sk-or-v1-` e.g. sk-or-v1-abcdef0123456789'),
        ('OpenAI overclaimed', 'live generation not verified', 'live-verified'),
        ('missing section', '\n## Switching providers\n', '\n## Changing providers\n'),
        ('broken link', '(MOCKS.md)', '(MOCK.md)'),
        ('missing credential rule', '`git check-ignore -v backend/.env.ai`', 'git status'),
    ]
    for label, old, new in mutations:
        assert old in text, 'Absent mutation target: ' + label
        try:
            validate(text.replace(old, new, 1), src)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print(f'{2 + len(mutations)} checks passed. No provider call made.')


if __name__ == '__main__':
    main()
