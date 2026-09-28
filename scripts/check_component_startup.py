#!/usr/bin/env python3
"""Packaged Java startup against a disposable real MySQL; no user DB writes."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from init_mysql_secrets import initialize

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT/'backend/build/libs/relay-backend-0.1.0.jar'


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def health(port, path):
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health/{path}', timeout=8) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)
    except (OSError, TimeoutError):
        return None, None


def await_health(port, expected, process):
    deadline=time.monotonic()+75
    while time.monotonic()<deadline:
        assert process.poll() is None, 'API exited; inspect backend/build/component-check logs'
        if health(port,'readiness')[0]==expected: return
        time.sleep(.5)
    raise AssertionError('Readiness did not reach '+str(expected))


def main(domain=False, browser=False):
    assert JAR.exists(), 'Build backend bootJar first'
    project='relay-integration-'+uuid.uuid4().hex[:10]
    logs=ROOT/'backend/build/component-check'
    logs.mkdir(parents=True,exist_ok=True)
    env={k:v for k,v in os.environ.items() if not k.startswith(('RELAY_', 'SPRING_', 'COMPOSE_', 'SERVER_', 'MANAGEMENT_'))}
    with tempfile.TemporaryDirectory(prefix='relay-integration-') as temporary:
        directory=Path(temporary)
        initialize(directory/'secrets')
        env.update(RELAY_MYSQL_SECRET_DIR=str(directory/'secrets'),RELAY_MYSQL_PORT=str(free_port()))
        compose=['docker','compose','--env-file','/dev/null','-f',str(ROOT/'compose.yaml'),'-p',project]
        def db(*args):
            result=subprocess.run(compose+list(args),env=env,text=True,capture_output=True,timeout=240)
            assert result.returncode==0, result.stderr
            return result.stdout
        def sql(statement):
            result=subprocess.run(compose+['exec','-T','mysql','sh','-c',
                'MYSQL_PWD="$(cat /run/secrets/mysql_password)" exec mysql --protocol=TCP -h 127.0.0.1 -u relay -D relay -N -B'],
                env=env,input=statement,text=True,capture_output=True,timeout=20)
            assert result.returncode==0,result.stderr
            return result.stdout.strip()
        migrations=directory/'migrations'
        migrations.mkdir()
        v1=migrations/'V2__startup_probe.sql'
        migration='CREATE TABLE startup_probe (id INT PRIMARY KEY); INSERT INTO startup_probe VALUES (1);\n'
        app_env=env|{'RELAY_LOAD_SEEDS':'false','RELAY_DB_URL':f"jdbc:mysql://127.0.0.1:{env['RELAY_MYSQL_PORT']}/relay",'RELAY_DB_USER':'relay',
            'RELAY_DB_PASSWORD':(directory/'secrets/password').read_text().strip(), 'RELAY_DEMO_TOKEN':secrets.token_hex(16)}
        browser_port=free_port() if browser else None
        if browser:
            app_env['RELAY_ALLOWED_ORIGINS']=f'http://127.0.0.1:{browser_port}'
        @contextmanager
        def application(mode,label,password=None):
            port=free_port()
            runtime=app_env|{'RELAY_MODE':mode,'RELAY_API_PORT':str(port)}
            if password is not None: runtime['RELAY_DB_PASSWORD']=password
            logpath=logs/(label+'.log')
            with logpath.open('w') as log:
                process=subprocess.Popen(['java','-jar',str(JAR),'--spring.flyway.locations='+('classpath:db/migration' if domain else 'classpath:db/migration,filesystem:'+str(migrations))],env=runtime,stdout=log,stderr=subprocess.STDOUT)
                try: yield process,port,logpath
                finally:
                    if process.poll() is None:
                        process.terminate()
                        process.wait(timeout=25)
        def rejected(mode,label,password=None):
            with application(mode,label,password) as (process,_,log):
                assert process.wait(timeout=60)!=0,label+' unexpectedly started'
                assert 'Started RelayApplication' not in log.read_text()
                assert app_env['RELAY_DB_PASSWORD'] not in log.read_text()
                print('PASS: '+label+' rejects startup',flush=True)
        try:
            db('up','-d','--wait','--wait-timeout','180')
            rejected('worker','empty-schema')
            if not domain: v1.write_text(migration)
            with application('api','api') as (api,port,_):
                await_health(port,200,api)
                assert health(port,'liveness')==(200,{'status':'UP'})
                if browser:
                    subprocess.run(['node',str(ROOT/'frontend/tests/browser-cors.mjs'),str(port),str(browser_port)],
                        env=app_env,check=True,timeout=60)
                if domain:
                    from schema_assertions import verify_schema
                    verify_schema(sql, compose, env)
                else:
                    assert sql('SELECT COUNT(*) FROM startup_probe;')=='1'
                assert sql('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;')==('1' if domain else '2')
                print('PASS: packaged API connects via JPA/ConnectorJ; Flyway executes SQL once; readiness/liveness UP',flush=True)
                with application('worker','worker') as (worker,worker_port,log):
                    deadline=time.monotonic()+60
                    while 'Started RelayApplication' not in log.read_text() and time.monotonic()<deadline:
                        assert worker.poll() is None,'Worker failed; inspect log'
                        time.sleep(.25)
                    assert 'Started RelayApplication' in log.read_text()
                    assert worker.poll() is None
                    assert 'Tomcat' not in log.read_text()
                    assert health(worker_port,'liveness')[0] is None
                    assert sql('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;')==('1' if domain else '2')
                    print('PASS: worker starts and stays alive without HTTP or schema mutation',flush=True)
                db('stop','mysql')
                await_health(port,503,api)
                assert health(port,'liveness')==(200,{'status':'UP'})
                db('up','-d','--wait','--wait-timeout','180')
                await_health(port,200,api)
                print('PASS: database outage readiness503/liveness200, recovery readiness200',flush=True)
            with application('api','api-repeat') as (api,port,_):
                await_health(port,200,api)
                assert sql('SELECT COUNT(*) FROM '+('workflows' if domain else 'startup_probe')+';')==('3' if domain else '1')
                assert sql('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;')==('1' if domain else '2')
            print('PASS: API restart does not reapply migration',flush=True)
            if domain:
                rejected('api','wrong-credentials','incorrect-local-test')
                return
            v2=migrations/'V3__pending_probe.sql'
            v2.write_text('CREATE TABLE pending_probe (id INT);\n')
            rejected('worker','pending-migration')
            assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='relay' AND table_name='pending_probe';")=='0'
            v2.unlink()
            v1.write_text(migration+'-- changed checksum\n')
            # Flyway ignores comments for SQL checksum: alter the actual statement.
            v1.write_text(migration.replace('VALUES (1)','VALUES (2)'))
            rejected('worker','checksum-mismatch')
            v1.write_text(migration)
            sql("UPDATE flyway_schema_history SET version='99' WHERE version='2';")
            rejected('worker','future-schema')
            sql("UPDATE flyway_schema_history SET version='2' WHERE version='99';")
            rejected('api','wrong-credentials','incorrect-local-test')
        finally:
            db('down','--volumes','--remove-orphans')
            print('PASS: owned Java processes and disposable MySQL resources cleaned up: '+project,flush=True)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--domain', action='store_true', help='Use packaged domain migrations and run schema assertions')
    parser.add_argument('--browser', action='store_true', help='Verify real Chromium CORS against the packaged API')
    args=parser.parse_args()
    main(args.domain, args.browser)
