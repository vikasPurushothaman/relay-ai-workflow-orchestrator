"""Run every automated suite locally with one command (task 9.2).

Stages, in order: backend (unit tests + jar), mysql (Testcontainers real-MySQL suite),
frontend (unit tests, type check + build), browser (mocked Chromium suite), scripts
(scripts/test_*.py) and docs (document-only checks). Prerequisites are checked before
each stage; a missing one fails the stage with a hint instead of skipping it silently.
Only --skip/--only leave a stage out, and skipped stages are reported as not run.
Exit status 0 only when no stage failed. Local only: starts no app services, pushes nothing.

    python3 scripts/run_checks.py                 # everything (Docker must be running)
    python3 scripts/run_checks.py --skip mysql    # without Docker
    python3 scripts/run_checks.py --only docs     # document checks only
    python3 scripts/run_checks.py --install       # fresh clone: npm ci first
"""
from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BACKEND, FRONTEND, SCRIPTS = ROOT / 'backend', ROOT / 'frontend', ROOT / 'scripts'
LOGS = ROOT / 'tmp/checks'
STAGES = ('backend', 'mysql', 'frontend', 'browser', 'scripts', 'docs')
# Checks that build, start containers or services, or probe the toolchain; not document checks.
SERVICE_CHECKS = {'check_backend_smoke', 'check_component_startup', 'check_local_mocks', 'check_mysql_compose',
                  'check_persistence', 'check_toolchain'}
HINTS = {
    'backend': 'see the log; on a new machine run without --offline once so Gradle can download dependencies',
    'mysql': 'start Docker (Docker Desktop or the docker engine) and re-run, or pass --skip mysql',
    'frontend': 'cd frontend && nvm use && npm ci && cp -n .env.example .env.local (or pass --install)',
    'browser': 'cd frontend && npx playwright install chromium; the frontend stage must build dist/ first',
    'scripts': 'see the log for the failing unittest',
    'docs': 'run the failing scripts/check_*.py directly to see its assertion',
}


def execute(cmd, cwd, log):
    """Run one command, append its output to the log, return (exit status, output)."""
    with log.open('a') as out:
        out.write('$ ' + ' '.join(str(c) for c in cmd) + '   (in ' + str(Path(cwd).relative_to(ROOT) or '.') + ')\n')
        try:
            done = subprocess.run([str(c) for c in cmd], cwd=cwd, capture_output=True, text=True)
        except FileNotFoundError as error:
            out.write(str(error) + '\n')
            return 127, str(error)
        text = done.stdout + done.stderr
        out.write(text + '\n[exit ' + str(done.returncode) + ']\n')
        return done.returncode, text


def probe(cmd):
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return done.returncode, done.stdout + done.stderr


# --- result parsers: each returns (passed, failed) or None when nothing was found -------------

def junit_counts(directory):
    files = sorted(Path(directory).glob('TEST-*.xml'))
    if not files:
        return None
    passed = failed = 0
    for file in files:
        suite = ET.parse(file).getroot()
        tests, skipped = int(suite.get('tests', 0)), int(suite.get('skipped', 0))
        bad = int(suite.get('failures', 0)) + int(suite.get('errors', 0))
        passed += tests - skipped - bad
        failed += bad
    return passed, failed


def node_counts(text):
    passed, failed = re.findall(r'^[#ℹ] pass (\d+)$', text, re.M), re.findall(r'^[#ℹ] fail (\d+)$', text, re.M)
    return (int(passed[-1]), int(failed[-1]) if failed else 0) if passed else None


def playwright_counts(text):
    passed, failed = re.search(r'^\s*(\d+) passed\b', text, re.M), re.search(r'^\s*(\d+) failed\b', text, re.M)
    if not passed and not failed:
        return None
    return int(passed.group(1)) if passed else 0, int(failed.group(1)) if failed else 0


def unittest_counts(text):
    ran = re.search(r'^Ran (\d+) tests?', text, re.M)
    if not ran:
        return None
    total = int(ran.group(1))
    bad = sum(int(n) for n in re.findall(r'(?:failures|errors)=(\d+)', text.split(ran.group(0), 1)[1]))
    return total - bad, bad


# --- prerequisites: each returns None when satisfied, otherwise the reason ----------------------

def java_problem():
    java = Path(os.environ['JAVA_HOME']) / 'bin/java' if os.environ.get('JAVA_HOME') else 'java'
    found = probe([str(java), '-version'])
    if not found or found[0] != 0:
        return 'Java not found (set JAVA_HOME or put Java 21 on PATH)'
    version = re.search(r'version "(\d+)', found[1])
    return None if version and version.group(1) == '21' else 'Java 21 required, found: ' + found[1].splitlines()[0]


def node_problem():
    wanted = (FRONTEND / '.nvmrc').read_text().strip()
    found = probe(['node', '--version'])
    if not found or found[0] != 0:
        return 'Node.js not found (cd frontend && nvm use)'
    return None if found[1].strip() == 'v' + wanted else f'Node {wanted} required (frontend/.nvmrc), found {found[1].strip()}'


def docker_problem():
    found = probe(['docker', 'info', '--format', '{{.ServerVersion}}'])
    return None if found and found[0] == 0 else 'Docker engine not reachable (docker info failed)'


def modules_problem():
    return None if (FRONTEND / 'node_modules').is_dir() else 'frontend/node_modules missing (npm ci, or pass --install)'


def env_problem():
    return None if (FRONTEND / '.env.local').is_file() else 'frontend/.env.local missing (cp -n frontend/.env.example frontend/.env.local, or pass --install)'


def ensure_env():
    """--install: create frontend/.env.local from the example, as the README console step does; never overwrite."""
    target = FRONTEND / '.env.local'
    if not target.exists():
        shutil.copyfile(FRONTEND / '.env.example', target)
    return None


def dist_problem():
    return None if (FRONTEND / 'dist/index.html').is_file() else 'frontend/dist missing (run the frontend stage first)'


def doc_checks():
    return sorted(p.stem for p in SCRIPTS.glob('check_*.py') if p.stem not in SERVICE_CHECKS)


def script_tests():
    return sorted(p.stem for p in SCRIPTS.glob('test_*.py'))


# --- stages -------------------------------------------------------------------------------------

def gradle(args, task):
    cmd = ['./gradlew', '--gradle-user-home', '.gradle/user-home', '--no-daemon']
    return cmd + (['--offline'] if args.offline else []) + task


def stage_plan(name, args):
    """(prerequisite checks, steps). A step is (command, cwd, counter) or (command, cwd, None)."""
    if name == 'backend':
        return [java_problem], [(gradle(args, ['test', '--rerun', 'bootJar']), BACKEND,
                                 lambda _: junit_counts(BACKEND / 'build/test-results/test'))]
    if name == 'mysql':
        return [java_problem, docker_problem], [(gradle(args, ['testcontainersTest', '--rerun']), BACKEND,
                                                 lambda _: junit_counts(BACKEND / 'build/test-results/testcontainersTest'))]
    if name == 'frontend':
        install = [(['npm', 'ci'], FRONTEND, None)] if args.install else []
        return ([node_problem] + ([ensure_env] if args.install else [modules_problem, env_problem]),
                install + [(['npm', 'test'], FRONTEND, node_counts), (['npm', 'run', 'build'], FRONTEND, None)])
    if name == 'browser':
        return [node_problem, modules_problem, dist_problem], [(['npx', 'playwright', 'test'], FRONTEND, playwright_counts)]
    if name == 'scripts':
        return [], [([sys.executable, '-m', 'unittest', *script_tests()], SCRIPTS, unittest_counts)]
    return [], [([sys.executable, 'scripts/' + check + '.py'], ROOT, None) for check in doc_checks()]


def run_stage(name, args, executor):
    """Returns dict(status, passed, failed, reason, seconds)."""
    started = time.monotonic()
    log = LOGS / (name + '.log')
    log.write_text('')
    checks, steps = stage_plan(name, args)
    for check in checks:
        reason = check()
        if reason:
            log.write_text('Prerequisite missing: ' + reason + '\n')
            return dict(status='FAIL', passed=0, failed=0, reason=reason, seconds=0.0)
    if name in ('backend', 'mysql'):  # never count stale results from an earlier run
        shutil.rmtree(BACKEND / 'build/test-results' / ('test' if name == 'backend' else 'testcontainersTest'), ignore_errors=True)
    passed = failed = 0
    reasons = []
    for cmd, cwd, counter in steps:
        code, output = executor(cmd, cwd, log)
        label = ' '.join(str(c) for c in cmd[1:3]) if name == 'docs' else ' '.join(str(c) for c in cmd[:3])
        counts = counter(output) if counter else None
        if counter and counts is None:
            reasons.append(f'{label}: no test results found (exit {code})')
            continue
        if counts:
            passed, failed = passed + counts[0], failed + counts[1]
        elif name == 'docs' and code == 0:
            passed += 1
        if code != 0:
            failed += 0 if counts else 1
            reasons.append(f'{label}: exit {code}')
    status = 'FAIL' if reasons or failed else 'PASS'
    return dict(status=status, passed=passed, failed=failed, reason='; '.join(reasons), seconds=time.monotonic() - started)


def parse_args(argv):
    parser = argparse.ArgumentParser(description='Run every automated suite locally (task 9.2).')
    parser.add_argument('--only', action='append', choices=STAGES, help='run only these stages (repeatable)')
    parser.add_argument('--skip', action='append', choices=STAGES, default=[], help='leave a stage out (repeatable)')
    parser.add_argument('--offline', action='store_true', help='pass --offline to Gradle (dependencies already cached)')
    parser.add_argument('--install', action='store_true', help='run npm ci before the frontend stage (fresh clone)')
    return parser.parse_args(argv)


def main(argv=None, executor=execute, out=print):
    args = parse_args(argv)
    LOGS.mkdir(parents=True, exist_ok=True)
    selected = [s for s in STAGES if (not args.only or s in args.only)]
    results = {}
    for name in STAGES:
        if name not in selected or name in args.skip:
            results[name] = dict(status='SKIPPED', passed=0, failed=0, reason='not run (by request)', seconds=0.0)
            continue
        out(f'[{name}] running ...')
        results[name] = run_stage(name, args, executor)
        out(f'[{name}] {results[name]["status"]}')
    out('')
    out(f'{"Stage":<9} {"Status":<8} {"Passed":>6} {"Failed":>6} {"Time":>7}  Notes')
    for name, r in results.items():
        note = r['reason'] if r['status'] == 'SKIPPED' else (r['reason'] + ' — ' + HINTS[name] if r['status'] == 'FAIL' else '')
        log = '' if r['status'] == 'SKIPPED' else f'log: tmp/checks/{name}.log'
        out(f'{name:<9} {r["status"]:<8} {r["passed"]:>6} {r["failed"]:>6} {r["seconds"]:>6.0f}s  ' + '  '.join(p for p in (note, log) if p))
    ran = [r for r in results.values() if r['status'] != 'SKIPPED']
    failed = [n for n, r in results.items() if r['status'] == 'FAIL']
    tests = sum(r['passed'] for n, r in results.items() if n != 'docs' and r['status'] != 'SKIPPED')
    docs = results['docs']['passed'] if results['docs']['status'] != 'SKIPPED' else 0
    skipped = [n for n, r in results.items() if r['status'] == 'SKIPPED']
    out('')
    out(f'{len(ran)} stage(s) run, {len(failed)} failed; {tests} automated tests passed; {docs} document checks passed'
        + (f'; not run: {", ".join(skipped)}' if skipped else ''))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
