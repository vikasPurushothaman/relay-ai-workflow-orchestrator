#!/usr/bin/env python3
"""Run explicit JPA tests against disposable Compose MySQL, never the user's DB."""
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import uuid
from init_mysql_secrets import initialize
from check_component_startup import free_port, ROOT


def main(workflows=False, publication=False, seeds=False):
    test_task="seedTest" if seeds else "publicationTest" if publication else "workflowApiTest" if workflows else "mysqlTest"
    env={k:v for k,v in os.environ.items() if not k.startswith(('RELAY_','SPRING_','COMPOSE_','SERVER_','MANAGEMENT_'))}
    project='relay-jpa-'+uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix='relay-jpa-') as temp:
        directory=Path(temp)
        initialize(directory/'secrets')
        env.update(RELAY_MYSQL_SECRET_DIR=str(directory/'secrets'),RELAY_MYSQL_PORT=str(free_port()))
        compose=['docker','compose','--env-file','/dev/null','-f',str(ROOT/'compose.yaml'),'-p',project]
        def db(*args):
            result=subprocess.run(compose+list(args),env=env,capture_output=True,text=True,timeout=240)
            assert result.returncode==0,result.stderr
        try:
            db('up','-d','--wait','--wait-timeout','180')
            env.update(RELAY_MODE='api',RELAY_LOAD_SEEDS='false',RELAY_API_PORT=str(free_port()),
                       RELAY_DB_URL=f"jdbc:mysql://127.0.0.1:{env['RELAY_MYSQL_PORT']}/relay",RELAY_DB_USER='relay',
                       RELAY_DB_PASSWORD=(directory/'secrets/password').read_text().strip(),RELAY_DEMO_TOKEN=secrets.token_hex(16))
            if seeds: env.pop('RELAY_LOAD_SEEDS',None)
            result=subprocess.run(['./gradlew','--gradle-user-home','.gradle/user-home','--no-daemon','--offline',test_task],cwd=ROOT/'backend',env=env,timeout=240)
            assert result.returncode==0,'Integration tests failed; see backend/build/reports/tests/'+(test_task)
        finally:
            db('down','--volumes','--remove-orphans')
            print('Owned disposable MySQL project removed: '+project,flush=True)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workflows',action='store_true',help='Run real HTTP workflow API integration tests')
    parser.add_argument('--publication',action='store_true',help='Run publication/snapshot HTTP and concurrency integration tests')
    parser.add_argument('--seeds',action='store_true',help='Run repeatable startup seed integration tests')
    args=parser.parse_args()
    main(args.workflows,args.publication,args.seeds)
