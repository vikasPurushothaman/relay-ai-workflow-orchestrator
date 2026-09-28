#!/usr/bin/env python3
"""Verify pinned mocks using owned ephemeral processes; never reset user's mocks."""
from contextlib import contextmanager
import json
from pathlib import Path
import shutil
import select
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from run_mock import PACK, ROOT, verify_pack


@contextmanager
def running(role):
    with tempfile.TemporaryFile(mode='w+') as log:
        process = subprocess.Popen([sys.executable, '-u', str(ROOT/'scripts/run_mock.py'), role, '--port', '0'],
                                   stdout=subprocess.PIPE, stderr=log, text=True)
        try:
            assert select.select([process.stdout], [], [], 10)[0], 'Mock startup timed out'
            line = process.stdout.readline().strip()
            if not line:
                log.seek(0)
                raise AssertionError('Mock startup failed: ' + log.read())
            assert line.startswith('Relay mock '), line
            yield line.split(': ', 1)[1]
        finally:
            process.terminate()
            process.wait(timeout=10)
            process.stdout.close()


def request(base, path, body=None, headers=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(base+path, data=data, headers={'Content-Type':'application/json', **(headers or {})})
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response), response.headers


def main():
    verify_pack()
    with tempfile.TemporaryDirectory() as temporary:
        copy = Path(temporary)/'pack'
        shutil.copytree(PACK, copy)
        (copy/'README.md').write_text('changed')
        try:
            verify_pack(copy)
            raise AssertionError('modified source accepted')
        except ValueError:
            pass
    subprocess.run([sys.executable, str(PACK/'scripts/validate_pack.py')], check=True)
    for script in ('smoke_test.py', 'duplication_check.py'):
        subprocess.run([sys.executable, str(PACK/'scripts'/script), '--help'], check=True, stdout=subprocess.DEVNULL)
    for args in [('unknown',), ('world','--port','-1'), ('provider','--port','65536'), ('world','--port','bad')]:
        assert subprocess.run([sys.executable,str(ROOT/'scripts/run_mock.py'),*args],capture_output=True).returncode != 0
    print('PASS: pinned sources, drift rejection, fixtures validator, supplied checker CLIs and invalid launch arguments', flush=True)
    with running('world') as world:
        assert request(world,'/health')[0] == 200
        port=world.rsplit(':',1)[1]
        assert subprocess.run([sys.executable,str(ROOT/'scripts/run_mock.py'),'world','--port',port],capture_output=True,timeout=5).returncode != 0
        payload={'to':'test@example.com','message':'fixture'}
        first=request(world,'/email/send',payload,{'Idempotency-Key':'test-email'})
        repeat=request(world,'/email/send',payload,{'Idempotency-Key':'test-email'})
        assert first[0] == 200 and first[1] == repeat[1] and repeat[2]['x-mockworld-replayed']=='true'
        assert request(world,'/chat/message',{'channel':'test','message':'fixture'})[0]==200
        assert request(world,'/shipments',{'order_id':'ord_2001'})[0]==201
        assert request(world,'/orders/ord_2001/replacement',{})[0]==201
        for amount in (0,-1,5000,'bad'):
            assert request(world,'/orders/ord_2002/refund',{'amount_usd':amount})[0]==400
        assert request(world,'/orders/ord_2002/refund',{'amount_usd':45.5})[0]==200
        assert request(world,'/orders/ord_2002/refund',{})[0]==409
        for path,body,status in [('/email/send',{},400),('/chat/message',{},400),('/shipments',{},400),('/shipments',{'order_id':'missing'},404),('/orders/missing/refund',{},404),('/orders/missing/replacement',{},404)]:
            assert request(world,path,body)[0]==status
        assert request(world,'/orders/ord_2001')[1]['status']=='processing'
        assert request(world,'/missing')[0]==404
        assert request(world,'/email/send',raw=b'{')[0]==400
        assert request(world,'/admin/ledger?since=bad')[0]==400
        entries=request(world,'/admin/ledger')[1]['entries']
        assert request(world,'/admin/ledger?since='+str(entries[-1]['seq']))[1]['count']==0
        request(world,'/admin/config',{'mode':'down'})
        assert request(world,'/orders/ord_2001')[0]==503
        assert request(world,'/health')[0]==200
        request(world,'/admin/reset',{})
        assert request(world,'/admin/ledger')[1]['count']==0
        assert request(world,'/orders/ord_2002')[1]['status']=='delivered'
        for _ in range(2): request(world,'/email/send',payload,{'Idempotency-Key':'drill'})
        command=[sys.executable,str(PACK/'scripts/duplication_check.py'),'--url',world]
        assert subprocess.run(command,capture_output=True).returncode==0
        request(world,'/email/send',payload)
        assert subprocess.run(command,capture_output=True).returncode==1
        print('PASS: world effects/replay, refund boundaries/conflict, errors, failure/reset recovery, ledger and duplicate detection; occupied port rejected',flush=True)
    with running('world') as world:
        assert request(world,'/admin/ledger')[1]['count']==0
        print('PASS: new world process starts with empty in-memory ledger',flush=True)
    with running('provider') as provider:
        assert request(provider,'/health')[1]['name']=='alpha'
        payload={'model':'alpha-small','messages':[{'role':'user','content':'hello'}]}
        auth={'Authorization':'Bearer local-mock-only'}
        assert request(provider,'/v1/chat/completions',payload)[0]==401
        assert request(provider,'/v1/chat/completions',dict(payload,model='missing'),auth)[0]==404
        assert request(provider,'/v1/chat/completions',dict(payload,messages=[]),auth)[0]==400
        assert request(provider,'/v1/chat/completions',headers=auth,raw=b'{')[0]==400
        status,result,_=request(provider,'/v1/chat/completions',payload,auth)
        assert status==200 and result['usage']['total_tokens']>0
        try:
            json.loads(result['choices'][0]['message']['content'])
            raise AssertionError('expected supplied prose')
        except json.JSONDecodeError: pass
        for mode,code in [('down',503),('rate_limited',429),('ok',200)]:
            request(provider,'/admin/config',{'mode':mode})
            response=request(provider,'/v1/chat/completions',payload,auth)
            assert response[0]==code
            if code==429: assert response[2]['Retry-After']=='5'
        assert request(provider,'/missing')[0]==404
        print('PASS: provider health/auth/model/body/usage/prose, injected503/429 and recovery',flush=True)
    print('PASS: all owned mock processes stopped; no user service or ledger touched')


if __name__=='__main__':
    main()
