#!/usr/bin/env python3
"""Exercise MySQL lifecycle in a unique disposable Compose project only."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
from init_mysql_secrets import initialize

ROOT = Path(__file__).resolve().parents[1]


def main():
    project = 'relay-test-' + uuid.uuid4().hex[:12]
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(('COMPOSE_', 'RELAY_MYSQL_'))}
    with tempfile.TemporaryDirectory(prefix='relay-mysql-') as temporary:
        directory = Path(temporary)
        env.update(RELAY_MYSQL_SECRET_DIR=str(directory / 'secrets'), RELAY_MYSQL_PORT='0')
        base = ['docker', 'compose', '--env-file', '/dev/null', '-f', str(ROOT / 'compose.yaml'), '-p', project]

        def compose(*args, ok=True):
            result = subprocess.run(base + list(args), env=env, text=True, capture_output=True, timeout=240)
            if ok and result.returncode:
                raise RuntimeError('Compose ' + args[0] + ' failed: ' + result.stderr)
            return result

        def query(sql, wrong=False, ok=True):
            password = 'incorrect-test-password' if wrong else '$(cat /run/secrets/mysql_password)'
            return subprocess_query(sql, password, ok)

        def subprocess_query(sql, password, ok):
            result = subprocess.run(base + ['exec', '-T', 'mysql', 'sh', '-c',
                'MYSQL_PWD=' + password + ' exec mysql --protocol=TCP -h 127.0.0.1 -u relay -D relay -N -B --connect-timeout=3'],
                input=sql, env=env, text=True, capture_output=True, timeout=20)
            if ok and result.returncode:
                raise RuntimeError('SQL check failed: ' + result.stderr)
            return result

        try:
            config = json.loads(compose('config', '--format', 'json').stdout)
            service = config['services']['mysql']
            provenance = json.loads((ROOT / 'docs/mysql-image-provenance.json').read_text())
            assert service['image'] == provenance['image'] + '@' + provenance['indexDigest']
            assert service['ports'][0]['host_ip'] == '127.0.0.1'
            assert service['ports'][0]['target'] == 3306
            assert service['volumes'][0]['type'] == 'volume'
            assert service['environment']['MYSQL_USER'] == 'relay'
            assert 'MYSQL_PASSWORD' not in service['environment']
            assert len(service['secrets']) == 2
            assert 'SELECT 1' in service['healthcheck']['test'][1]
            assert compose('up', '-d', ok=False).returncode != 0
            print('PASS: pinned image, loopback port, named volume, file secrets, authenticated health; missing secrets rejected', flush=True)

            secrets_dir = directory / 'secrets'
            initialize(secrets_dir)
            original = (secrets_dir / 'password').read_bytes()
            initialize(secrets_dir)
            assert (secrets_dir / 'password').read_bytes() == original
            assert (secrets_dir / 'password').stat().st_mode & 0o777 == 0o600
            assert original != (secrets_dir / 'root-password').read_bytes()
            empty = directory / 'invalid'
            empty.mkdir()
            (empty / 'password').touch()
            try:
                initialize(empty)
                raise AssertionError('Empty existing password was accepted')
            except ValueError:
                pass
            print('PASS: secret creation is private, distinct, repeatable and rejects empty existing file', flush=True)

            compose('up', '-d', '--wait', '--wait-timeout', '180')
            assert query('SELECT VERSION(), DATABASE();').stdout.strip() == '8.4.11\trelay'
            assert query('SELECT 1;', wrong=True, ok=False).returncode != 0
            assert query('SELECT * FROM mysql.user;', ok=False).returncode != 0
            assert compose('port', 'mysql', '3306').stdout.startswith('127.0.0.1:')
            print('PASS: healthy MySQL 8.4.11, relay schema access, wrong password and system-table access rejected', flush=True)

            query('CREATE TABLE lifecycle_probe (id INT PRIMARY KEY); INSERT INTO lifecycle_probe VALUES (1);')
            compose('up', '-d', '--wait', '--wait-timeout', '180')
            assert query('SELECT COUNT(*) FROM lifecycle_probe;').stdout.strip() == '1'
            compose('down')
            compose('up', '-d', '--wait', '--wait-timeout', '180')
            assert query('SELECT COUNT(*) FROM lifecycle_probe;').stdout.strip() == '1'
            print('PASS: repeated up and down/up retain data in the named volume', flush=True)

            compose('down', '--volumes')
            compose('up', '-d', '--wait', '--wait-timeout', '180')
            assert query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='relay' AND table_name='lifecycle_probe';").stdout.strip() == '0'
            assert query('SELECT 1;').stdout.strip() == '1'
            print('PASS: explicit disposable-volume reset removes data and reinitializes schema/account', flush=True)
        finally:
            compose('down', '--volumes', '--remove-orphans')
            print('PASS: only owned disposable project resources removed: ' + project, flush=True)


if __name__ == '__main__':
    main()
