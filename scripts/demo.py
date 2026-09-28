#!/usr/bin/env python3
"""Demo helper (task 8.5): sample payloads, fixture webhook secrets, run and ledger
summaries, and the worker PID. Standard library only; calls existing routes only."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PAYLOADS = ROOT / 'docs/source-review/pack/data/sample_payloads.jsonl'
SEEDS = ROOT / 'backend/src/main/resources/catalog/seed_workflows.json'


class DemoError(Exception):
    pass


def payload(payload_id):
    for line in PAYLOADS.read_text().splitlines():
        if line.strip():
            item = json.loads(line)
            if item['id'] == payload_id:
                return json.dumps(item['body'], separators=(',', ':'))
    raise DemoError('Unknown payload ID: ' + payload_id)


def secret(workflow_id):
    for workflow in json.loads(SEEDS.read_text())['workflows']:
        if workflow['id'] == workflow_id:
            if workflow['trigger'].get('type') != 'webhook':
                raise DemoError(workflow_id + ' is not a webhook workflow')
            return workflow['trigger']['secret']
    raise DemoError('Unknown workflow ID: ' + workflow_id)


def get_json(url, token=None):
    request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token} if token else {})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise DemoError(f'HTTP {error.code} from {url}')
    except urllib.error.URLError as error:
        raise DemoError(f'Cannot reach {url}: {error.reason}')


def run(run_id, api, token):
    if not token:
        raise DemoError('Export RELAY_DEMO_TOKEN first: export RELAY_DEMO_TOKEN="$(cat local-data/demo-token)"')
    d = get_json(f'{api.rstrip("/")}/runs/{run_id}', token)
    error = d.get('error') or {}
    lines = [f"{d['run_id']}  {d['workflow_id']}  {d['status']}"
             + (f"  error={error.get('code')}@{error.get('node_id')}" if error else '')]
    for s in d.get('steps', []):
        causes = ','.join(a.get('cause', '?') for a in s.get('attempts', []))
        lines.append(f"  {s['sequence']:>3} {s['node_id']:<18} {s['status']:<10} attempts={s.get('attempt_count')} [{causes}]"
                     + (f" key={s['idempotency_key']}" if s.get('idempotency_key') else ''))
    return '\n'.join(lines)


def ledger(world):
    entries = get_json(world.rstrip('/') + '/admin/ledger').get('entries', [])
    return '\n'.join(f"{e['seq']:>3} {e['action']:<16} {e.get('idempotency_key') or '-':<40} "
                     + ('REPLAYED' if e.get('replayed') else 'executed') for e in entries) or '(ledger empty)'


def worker_pid(api_port, ps_output=None, lsof_output=None):
    if ps_output is None:
        ps_output = subprocess.run(['ps', '-axo', 'pid=,command='], capture_output=True, text=True).stdout
    if lsof_output is None:
        lsof_output = subprocess.run(['lsof', '-nP', f'-iTCP:{api_port}', '-sTCP:LISTEN', '-t'],
                                     capture_output=True, text=True).stdout
    api = set(lsof_output.split())
    relay = [line.split(None, 1)[0] for line in ps_output.splitlines()
             if 'java' in line and 'relay-backend' in line and '.jar' in line]
    candidates = [pid for pid in relay if pid not in api]
    if len(candidates) != 1:
        raise DemoError(f'Expected exactly one Relay worker JVM, found {len(candidates)}')
    return candidates[0]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', default=os.environ.get('RELAY_API_URL', 'http://127.0.0.1:8080'))
    parser.add_argument('--world', default='http://127.0.0.1:9210')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('payload').add_argument('payload_id')
    sub.add_parser('secret').add_argument('workflow_id')
    sub.add_parser('run').add_argument('run_id')
    sub.add_parser('ledger')
    sub.add_parser('worker-pid').add_argument('--api-port', default='8080')
    args = parser.parse_args(argv)
    try:
        if args.command == 'payload':
            print(payload(args.payload_id))
        elif args.command == 'secret':
            print(secret(args.workflow_id))
        elif args.command == 'run':
            print(run(args.run_id, args.api, os.environ.get('RELAY_DEMO_TOKEN', '')))
        elif args.command == 'ledger':
            print(ledger(args.world))
        else:
            print(worker_pid(args.api_port))
    except DemoError as error:
        print('demo: ' + str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
