#!/usr/bin/env python3
"""Read-only tool inventory plus temporary Java compilation checks (task 2.1).
Run from a fresh configured shell. Missing Gradle/server is reported, not required
until the wrapper and local-service tasks. No packages are installed.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile


def run(args, required=True):
    print('$ ' + ' '.join(args), flush=True)
    try:
        result = subprocess.run(args, text=True, capture_output=True, timeout=30)
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        print(f'UNAVAILABLE: {exc}')
        if required:
            raise
        return None
    print(result.stdout + result.stderr, end='')
    print(f'exit={result.returncode}')
    if required:
        assert result.returncode == 0, args
    return result


for tool in ('java', 'javac', 'gradle', 'node', 'npm', 'pnpm', 'docker', 'python3'):
    print(f'{tool}: {shutil.which(tool) or "NOT FOUND"}')
for args in (['java', '-version'], ['javac', '-version'], ['node', '--version'],
             ['npm', '--version'], ['python3', '--version']):
    run(args)
for args in (['gradle', '-version'], ['pnpm', '--version'], ['docker', '--version'],
             ['docker', 'compose', 'version'],
             ['docker', 'info', '--format', '{{.ServerVersion}}']):
    run(args, required=False)
with tempfile.TemporaryDirectory(prefix='relay-java-') as temp:
    source = Path(temp) / 'HelloRelay.java'
    source.write_text('public class HelloRelay { public static void main(String[] args) { System.out.println("Relay Java OK"); } }\n')
    run(['javac', str(source)])
    result = run(['java', '-cp', temp, 'HelloRelay'])
    assert result.stdout.strip() == 'Relay Java OK'
    source.write_text('public class HelloRelay { invalid java }\n')
    result = run(['javac', str(source)], required=False)
    assert result is not None and result.returncode != 0
    print('PASS: valid Java compiles/runs; invalid source rejected')
print('PASS: core toolchain checks; review optional/unavailable results above')
