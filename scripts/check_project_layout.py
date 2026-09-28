#!/usr/bin/env python3
"""Offline layout/config checks; no application or dotenv code is executed."""
from pathlib import Path
import re
import subprocess
import tempfile
import os

ROOT = Path(__file__).resolve().parents[1]


def parse(text):
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        match = re.fullmatch(r'([A-Z][A-Z0-9_]*)=([^\s`$;]*)', line)
        assert match, 'Example must contain simple literal assignments'
        key, value = match.groups()
        assert key not in values, f'Duplicate key: {key}'
        values[key] = value
    return values


def validate(backend, frontend):
    b, f = parse(backend), parse(frontend)
    arch = (ROOT/'docs/ARCHITECTURE.md').read_text()
    table = arch.split('| Setting/group |')[1].split('\n\n')[0]
    keys = set(re.findall(r'\b(?:RELAY_|MOCK_|VITE_)[A-Z_]+\b', table))
    assert set(b) | set(f) == keys, 'Keys must match architecture'
    assert set(f) == {'VITE_RELAY_API_BASE_URL'}, 'Frontend exposes only public URL'
    assert not set(b) & set(f), 'Distinct consumers'
    for key in ('RELAY_DB_PASSWORD', 'RELAY_DEMO_TOKEN', 'RELAY_AI_API_KEY', 'RELAY_MODE', 'RELAY_AI_MODEL'):
        assert b[key] == '', f'{key} must be explicitly configured locally'
    assert b['RELAY_AI_MODE'] == 'mock-http', 'No real-provider default'
    assert b['RELAY_DB_URL'] == 'jdbc:mysql://localhost:3306/relay'
    assert b['RELAY_ALLOWED_ORIGINS'] == 'http://localhost:5173'
    assert b['MOCK_WORLD_URL'] == b['RELAY_HTTP_ALLOWED_ORIGINS'] == 'http://localhost:9210'
    assert b['RELAY_AI_BASE_URL'] == 'http://localhost:9001'
    assert f['VITE_RELAY_API_BASE_URL'] == 'http://localhost:8080'
    recovery = (ROOT/'docs/RECOVERY.md').read_text()
    defaults = re.findall(r'^\| (RELAY_[A-Z_]+) \| (\d+) \|', recovery, re.M)
    assert len(defaults) == 9
    for key, expected in defaults:
        assert b[key] == expected, f'Default differs: {key}'
    n = {key: int(value) for key,value in defaults}
    assert n['RELAY_JOB_LEASE_MS'] > n['RELAY_JOB_RENEW_MS'] + max(n['RELAY_HTTP_TIMEOUT_MS'], n['RELAY_AI_TIMEOUT_MS']) + 2*n['RELAY_DB_TX_TIMEOUT_MS']


def check_ignore():
    ignored = ['backend/.env.ai', '.env', '.env.local', '.env.production', 'backend/.env',
               'frontend/.env.local', 'backend/nested/.env.test', '.env.example.bak',
               'backend/.gradle/cache', 'backend/build/classes/App.class',
               'frontend/node_modules/react/index.js', 'frontend/dist/index.html',
               'frontend/coverage/result.json', 'frontend/cache.tsbuildinfo',
               'scripts/__pycache__/test.pyc', '.venv/bin/python', '.idea/workspace.xml',
               '.DS_Store', 'docs/.DS_Store', 'tmp/probe.txt', 'local-data/dump.sql', 'worker.log']
    retained = ['.env.example', 'backend/.env.example', 'frontend/.env.example',
                'backend/gradlew', 'backend/gradlew.bat', 'backend/gradle/wrapper/gradle-wrapper.jar',
                'backend/gradle/wrapper/gradle-wrapper.properties', 'backend/gradle.lockfile',
                'backend/build.gradle', 'backend/settings.gradle',
                'frontend/package-lock.json', 'frontend/src/main.tsx',
                'backend/src/main/java/relay/App.java', 'backend/src/main/resources/db/migration/V1__init.sql',
                'docs/project-layout-verification.txt', 'docs/version-sources/baseline.json',
                'scripts/check_project_layout.py', 'AGENTS.md', 'PROJECT_PLAN.md']
    with tempfile.TemporaryDirectory(prefix='relay-ignore-') as temp:
        path = Path(temp)
        (path/'.gitignore').write_text((ROOT/'.gitignore').read_text())
        # Isolate Git configuration and repository environment from the user's project.
        env = {k:v for k,v in os.environ.items() if not k.startswith('GIT_')}
        env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        subprocess.run(['git', 'init', '-q', temp], check=True, env=env, capture_output=True)
        for file in ignored + retained:
            result = subprocess.run(['git', '-c', 'core.excludesFile='+os.devnull,
                                     '-C', temp, 'check-ignore', '--no-index', '-q', file], env=env)
            assert result.returncode == (0 if file in ignored else 1), file
    print(f'PASS: {len(ignored)} ignored and {len(retained)} retained paths via git check-ignore')


if __name__ == '__main__':
    for name in ('backend','frontend','docs','scripts'):
        assert (ROOT/name).is_dir(), name
    b=(ROOT/'backend/.env.example').read_text()
    f=(ROOT/'frontend/.env.example').read_text()
    validate(b,f)
    print('PASS: 4 directories; architecture keys, blank secrets, mock URLs and 9 recovery defaults')
    changes=[(b.replace('RELAY_DB_PASSWORD=', 'RELAY_DB_PASSWORD=unsafe-example'),f),
             (b,f+'VITE_TOKEN=unsafe-example\n'),
             (b.replace('RELAY_MODE=\n',''),f),
             (b.replace('RELAY_JOB_LEASE_MS=60000','RELAY_JOB_LEASE_MS=10000'),f),
             (b+'RELAY_MODE=api\n',f)]
    for bad_b,bad_f in changes:
        try:
            validate(bad_b,bad_f)
        except AssertionError:
            pass
        else:
            raise AssertionError('Invalid template accepted')
    print('PASS: 5 invalid template mutations rejected')
    check_ignore()
    setup=(ROOT/'docs/SETUP.md').read_text()
    for link in re.findall(r'\]\(([^)]+)\)',setup):
        if not link.startswith('https:'):
            assert (ROOT/'docs'/link.split('#')[0]).exists(),link
    assert 'No automatic dotenv loader is implemented.' in setup
    print('PASS: setup links and implementation boundary')
