#!/usr/bin/env python3
"""Launch the packaged scaffold, probe it, then stop only the process we started."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT/'backend/build/libs/relay-backend-0.1.0.jar'
assert JAR.is_file(), 'Run backend Gradle test bootJar first'
# Do not inherit a user's real database, profile, or provider configuration.
env = {k:v for k,v in os.environ.items() if not k.startswith(('SPRING_', 'RELAY_', 'MOCK_', 'MANAGEMENT_', 'SERVER_'))}
log_path = ROOT/'backend/build/scaffold-smoke.log'
with log_path.open('w') as log:
    args = ['java', '-jar', str(JAR), '--spring.profiles.active=scaffold', '--server.port=0']
    print('$ java -jar backend/build/libs/relay-backend-0.1.0.jar --spring.profiles.active=scaffold --server.port=0')
    proc = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 30
        port = None
        while time.monotonic() < deadline:
            assert proc.poll() is None, 'Server exited early; inspect backend/build/scaffold-smoke.log'
            content = log_path.read_text()
            match = re.search(r'Tomcat started on port (\d+)', content)
            if match and 'Started RelayApplication' in content:
                port = int(match.group(1))
                break
            time.sleep(0.1)
        assert port, 'Startup timed out'
        for path, expected in [('/actuator/health/liveness', 200), ('/actuator/health/readiness',503),
                               ('/actuator/health',503), ('/actuator/env',401)]:
            request = urllib.request.Request(f'http://127.0.0.1:{port}'+path, headers={'Accept':'application/json'})
            try:
                response = urllib.request.urlopen(request, timeout=5)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                body = response.read().decode()
                print(f'GET {path}: {response.status} {body}')
                assert response.status == expected
                assert not response.headers.get('Set-Cookie')
                if expected != 401:
                    payload = json.loads(body)
                    assert 'components' not in payload and 'details' not in payload
                    assert payload['status'] == ('UP' if expected==200 else 'OUT_OF_SERVICE')
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
print('PASS: packaged scaffold starts, liveness UP, readiness unavailable, health details hidden, other endpoint denied; process stopped')
# No profile or datasource: do not silently start a database-free application.
with (ROOT/'backend/build/default-startup.log').open('w') as log:
    args = ['java', '-jar', str(JAR), '--server.port=0']
    print('$ java -jar backend/build/libs/relay-backend-0.1.0.jar --server.port=0 (no profile/database)')
    result = subprocess.run(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=30)
assert result.returncode != 0, 'Default startup unexpectedly succeeded without database configuration'
content = (ROOT/'backend/build/default-startup.log').read_text()
assert 'RELAY_MODE must be exactly api or worker' in content, 'Unexpected startup failure; inspect default-startup.log'
print('PASS: default startup rejects missing RELAY_MODE before datasource/listener startup')

# Validate packaged bootstrap failures without attempting a real database connection.
for label, overrides, extra_args, marker in [
    ('invalid-mode', {'RELAY_MODE':'api,worker'}, [], 'RELAY_MODE must be exactly api or worker'),
    ('mixed-scaffold', {'RELAY_MODE':'worker'}, ['--spring.profiles.active=scaffold'], 'cannot be combined with scaffold'),
    ('missing-worker-db', {'RELAY_MODE':'worker'}, [], 'RELAY_DB_URL must be nonempty'),
    ('credential-url', {'RELAY_MODE':'api','RELAY_DB_URL':'jdbc:mysql://user:private-sentinel@localhost/relay'}, [], 'RELAY_DB_URL must be jdbc:mysql://'),
]:
    logfile = ROOT/'backend/build'/('launch-'+label+'.log')
    with logfile.open('w') as log:
        result = subprocess.run(['java','-jar',str(JAR),*extra_args], cwd=ROOT,
                                env={**env,**overrides}, stdout=log, stderr=subprocess.STDOUT, timeout=20)
    content = logfile.read_text()
    assert result.returncode != 0 and marker in content, label
    assert 'Tomcat started' not in content and 'private-sentinel' not in content, label
    print('PASS: packaged '+label+' rejected before HTTP/DB startup, without credential value leakage')
