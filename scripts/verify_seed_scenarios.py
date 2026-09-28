#!/usr/bin/env python3
"""Drive the four supplied seed workflows end to end and check outcomes (task 7.2).

Uses the pack's own sample payloads and webhook secrets, then checks each run's
status, branch, trace and mock-world ledger effects. Ledger entries are matched
to runs by the engine's idempotency key (`run_id:sequence`). Stdlib only.

Prerequisites: API, one worker and the supplied mock world running with seeds
loaded. Triage needs a real AI provider on the worker (the supplied mock replies
in prose); pass --skip-ai to leave it out. The effects world is reset first, so
point --world at a disposable mock world, never a shared one.

    python3 scripts/verify_seed_scenarios.py --url http://localhost:8081 \
        --token-file <file> --world http://localhost:9310 \
        [--shipment-world http://localhost:9210] [--skip-ai]
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

PACK = Path(__file__).resolve().parents[1] / 'docs/source-review/pack/data'
TERMINAL = {'succeeded', 'failed', 'cancelled'}
RESULTS = []


def record(name, ok, detail='', tag=None):
    tag = tag or ('PASS' if ok else 'FAIL')
    RESULTS.append((tag, name, detail))
    print(f'  {tag:10} {name}' + (f'  ({detail})' if detail else ''), flush=True)


def call(method, url, body=None, headers=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    hdrs = {'Content-Type': 'application/json'} if data is not None else {}
    hdrs.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors='replace')
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {'_raw': raw}


class Relay:
    def __init__(self, url, token):
        self.url, self.auth = url.rstrip('/'), {'Authorization': f'Bearer {token}'}

    def api(self, method, path, body=None):
        return call(method, self.url + path, body, self.auth)

    def hook(self, workflow_id, secret, body):
        return call('POST', f'{self.url}/hooks/{workflow_id}', body, {'X-Relay-Secret': secret})

    def poll(self, run_id, until, timeout_s):
        deadline, run = time.time() + timeout_s, {}
        while time.time() < deadline:
            code, body = self.api('GET', f'/runs/{run_id}')
            if code == 200:
                run = body
                if run.get('status') in until:
                    return run
            time.sleep(1)
        return run

    def approval_for(self, run_id):
        _, body = self.api('GET', '/approvals?status=pending')
        items = body if isinstance(body, list) else body.get('approvals', [])
        return next((a for a in items if a.get('run_id') == run_id), None)


def ledger(world):
    return call('GET', world.rstrip('/') + '/admin/ledger', timeout=5)[1]['entries']


def effects(entries, run_id, action=None):
    return [e for e in entries if str(e.get('idempotency_key') or '').startswith(run_id + ':')
            and (action is None or e['action'] == action)]


def step(run, node_id):
    return next((s for s in run.get('steps', []) if s['node_id'] == node_id), None)


def path(run):
    return [s['node_id'] for s in run.get('steps', [])]


def ts(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def accepted(label, code, body):
    run_id = body.get('run_id') if isinstance(body, dict) else None
    record(f'{label}: accepted 202 with run_id', code == 202 and bool(run_id), f'got {code}')
    return run_id


def check_ai_step(label, run, expected_category):
    classify = step(run, 'classify')
    if not classify or classify['status'] != 'succeeded':
        record(f'{label}: classify succeeded', False,
               f"status={classify and classify['status']} error={classify and classify.get('error')}")
        return None
    out = classify.get('output') or {}
    record(f'{label}: classify output is schema-shaped',
           set(out) == {'category', 'priority', 'summary'}
           and out['category'] in ('refund_request', 'complaint', 'question')
           and out['priority'] in ('low', 'medium', 'high') and len(out['summary']) <= 300,
           json.dumps(out)[:160])
    last = (classify.get('attempts') or [{}])[-1]
    record(f'{label}: AI attempt has provider/model/usage',
           bool(last.get('provider') and last.get('model'))
           and classify.get('tokens_prompt') is not None and classify.get('tokens_completion') is not None,
           f"{last.get('provider')}/{last.get('model')} tokens={classify.get('tokens_prompt')}+"
           f"{classify.get('tokens_completion')} repairs={classify.get('ai_repair_count')}")
    category = out.get('category')
    record(f'{label}: classification matches pack expectation', category == expected_category,
           f'expected {expected_category}, got {category}',
           tag=None if category == expected_category else 'AI-QUALITY')
    want = 'refund_gate' if category == 'refund_request' else 'notify_support'
    route = step(run, 'route')
    record(f'{label}: engine branch follows the recorded category',
           route is not None and route.get('selected_next_node_id') == want,
           f"route -> {route and route.get('selected_next_node_id')}, expected {want}")
    return category


def triage(relay, world, payloads, secret):
    print('\n[AI triage] wf_support_triage (real provider)')
    for pid in ('pay_001', 'pay_003'):
        p = payloads[pid]
        run_id = accepted(pid, *relay.hook('wf_support_triage', secret, p['body']))
        if not run_id:
            continue
        run = relay.poll(run_id, TERMINAL | {'waiting_approval'}, 180)
        category = check_ai_step(pid, run, p['expected']['category'])
        if category is None:
            continue
        if category == 'refund_request':
            record(f'{pid}: refund classification pauses for approval',
                   run['status'] == 'waiting_approval', f"status {run['status']}")
            relay.api('POST', f"/runs/{run_id}/cancel")  # leave no pending work behind
            continue
        entries = ledger(world)
        record(f'{pid}: succeeded via notify_support', run['status'] == 'succeeded'
               and path(run) == ['classify', 'route', 'notify_support'], f"{run['status']} {path(run)}")
        record(f'{pid}: exactly one chat.message, no refund',
               len(effects(entries, run_id, 'chat.message')) == 1
               and not effects(entries, run_id, 'order.refund')
               and len(effects(entries, run_id)) == 1, f'{len(effects(entries, run_id))} effects')

    p = payloads['pay_002']
    run_id = accepted('pay_002', *relay.hook('wf_support_triage', secret, p['body']))
    if not run_id:
        return
    run = relay.poll(run_id, TERMINAL | {'waiting_approval'}, 180)
    category = check_ai_step('pay_002', run, 'refund_request')
    if category != 'refund_request':
        record('pay_002: refund path not reachable with this classification', False,
               f'category {category}; approval/refund checks not run')
        return
    order = p['body']['order_id']
    record('pay_002: pauses waiting_approval at refund_gate',
           run['status'] == 'waiting_approval' and run.get('current_node_id') == 'refund_gate',
           f"{run['status']} at {run.get('current_node_id')}")
    record('pay_002: no refund or email before approval', not effects(ledger(world), run_id))
    approval = relay.approval_for(run_id)
    record('pay_002: pending approval listed with rendered message',
           approval is not None and order in (approval.get('message') or ''),
           (approval or {}).get('message', 'missing')[:120])
    if not approval:
        return
    code, body = relay.api('POST', f"/approvals/{approval['id']}/approve")
    record('pay_002: approve returns 200', code == 200, f'{code} {body}')
    run = relay.poll(run_id, TERMINAL, 60)
    entries = ledger(world)
    refunds = effects(entries, run_id, 'order.refund')
    emails = effects(entries, run_id, 'email.send')
    record('pay_002: resumes to succeeded via issue_refund -> notify_customer',
           run['status'] == 'succeeded'
           and path(run) == ['classify', 'route', 'refund_gate', 'issue_refund', 'notify_customer'],
           f"{run['status']} {path(run)}")
    record('pay_002: exactly one refund and one customer email',
           len(refunds) == 1 and len(emails) == 1 and len(effects(entries, run_id)) == 2
           and refunds[0]['payload'].get('order_id') == order,
           f'refunds={len(refunds)} emails={len(emails)}')
    gate = step(run, 'refund_gate') or {}
    record('pay_002: approval evidence recorded on the gate step',
           (gate.get('output') or {}).get('decision') == 'approved'
           and (gate.get('approval') or {}).get('decided_by'), json.dumps(gate.get('output')))
    ref = ((step(run, 'issue_refund') or {}).get('output') or {}).get('reference_id')
    record('pay_002: customer email carries the refund reference',
           bool(ref) and ref in json.dumps(emails[0]['payload'] if emails else {}), f'reference {ref}')


def expense(relay, world, payloads, secret):
    print('\n[Expense approval] wf_expense_approval')
    small = payloads['pay_102']['body']
    run_id = accepted('pay_102', *relay.hook('wf_expense_approval', secret, small))
    if run_id:
        run = relay.poll(run_id, TERMINAL | {'waiting_approval'}, 60)
        emails = effects(ledger(world), run_id, 'email.send')
        record('pay_102: auto_ok branch, succeeded, no approval',
               run['status'] == 'succeeded' and path(run) == ['is_large', 'auto_ok']
               and relay.approval_for(run_id) is None, f"{run['status']} {path(run)}")
        record('pay_102: exactly one auto-approved email to the employee',
               len(emails) == 1 and small['employee_email'] in json.dumps(emails[0]['payload']),
               f'{len(emails)} emails')

    large = payloads['pay_101']['body']
    for decision in ('reject', 'approve'):
        label = f'pay_101/{decision}'
        run_id = accepted(label, *relay.hook('wf_expense_approval', secret, large))
        if not run_id:
            continue
        run = relay.poll(run_id, TERMINAL | {'waiting_approval'}, 60)
        record(f'{label}: pauses waiting_approval at finance_gate',
               run['status'] == 'waiting_approval' and run.get('current_node_id') == 'finance_gate',
               f"{run['status']} at {run.get('current_node_id')}")
        approval = relay.approval_for(run_id)
        if not approval:
            record(f'{label}: pending approval listed', False)
            continue
        code, body = relay.api('POST', f"/approvals/{approval['id']}/{decision}")
        record(f'{label}: decision returns 200', code == 200, f'{code} {body}')
        code, _ = relay.api('POST', f"/approvals/{approval['id']}/{decision}")
        record(f'{label}: repeated decision conflicts 409', code == 409, f'got {code}')
        run = relay.poll(run_id, TERMINAL, 60)
        emails = effects(ledger(world), run_id, 'email.send')
        if decision == 'reject':
            record(f'{label}: run cancelled with approval_rejected, no email',
                   run['status'] == 'cancelled' and run.get('cancellation_reason') == 'approval_rejected'
                   and step(run, 'approved_notice') is None and not emails,
                   f"{run['status']} {run.get('cancellation_reason')} emails={len(emails)}")
        else:
            record(f'{label}: resumes to succeeded with exactly one approved email',
                   run['status'] == 'succeeded' and path(run) == ['is_large', 'finance_gate', 'approved_notice']
                   and len(emails) == 1, f"{run['status']} {path(run)} emails={len(emails)}")


def slow_fulfillment(relay, world, shipment_world, payloads):
    print('\n[Slow fulfillment] wf_slow_fulfillment (20 s durable delay)')
    body = payloads['pay_201']['body']
    run_id = accepted('pay_201', *relay.api('POST', '/workflows/wf_slow_fulfillment/trigger', {'input': body}))
    if not run_id:
        return
    run = relay.poll(run_id, TERMINAL, 120)
    record('pay_201: succeeded through all four nodes', run.get('status') == 'succeeded'
           and path(run) == ['confirm', 'pack_delay', 'create_shipment', 'shipped_notice'],
           f"{run.get('status')} {path(run)}")
    delay, ship = step(run, 'pack_delay'), step(run, 'create_shipment')
    if delay and ship:
        waited = (ts(ship['started_at']) - ts(delay['started_at'])).total_seconds()
        record('pay_201: delay held for at least 20 s before the shipment', waited >= 20.0, f'{waited:.1f} s')
    entries, ship_entries = ledger(world), effects(ledger(shipment_world), run_id, 'shipment.create')
    emails = effects(entries, run_id, 'email.send')
    record('pay_201: exactly two emails (received, shipped) and one shipment',
           len(emails) == 2 and len(ship_entries) == 1, f'emails={len(emails)} shipments={len(ship_entries)}')
    shipment_id = (((ship or {}).get('output') or {}).get('body') or {}).get('shipment_id')
    record('pay_201: shipped email carries the tracking ID',
           bool(shipment_id) and any(shipment_id in json.dumps(e['payload']) for e in emails),
           f'shipment {shipment_id}')


def runaway(relay, world):
    print('\n[Runaway loop] wf_runaway')
    run_id = accepted('wf_runaway', *relay.api('POST', '/workflows/wf_runaway/trigger', {'input': {}}))
    if not run_id:
        return
    run = relay.poll(run_id, TERMINAL, 120)
    record('wf_runaway: failed by the step cap at exactly 12 of 12',
           run.get('status') == 'failed' and (run.get('error') or {}).get('code') == 'max_steps'
           and run.get('steps_executed') == 12 == run.get('max_steps') and len(run.get('steps', [])) == 12,
           f"{run.get('status')} {run.get('error')} {run.get('steps_executed')}/{run.get('max_steps')}")
    record('wf_runaway: never reached the done notification',
           step(run, 'done') is None and not effects(ledger(world), run_id))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--url', required=True)
    parser.add_argument('--token-file', required=True, help='file holding the management token')
    parser.add_argument('--world', required=True, help='disposable mock world for effects (reset first)')
    parser.add_argument('--shipment-world', default='http://localhost:9210',
                        help='world receiving the seed-hard-coded shipment POST (read only here)')
    parser.add_argument('--skip-ai', action='store_true', help='skip wf_support_triage')
    args = parser.parse_args()

    relay = Relay(args.url, Path(args.token_file).read_text().strip())
    seeds = {w['id']: w for w in json.loads((PACK / 'seed_workflows.json').read_text())['workflows']}
    payloads = {p['id']: p for p in map(json.loads, (PACK / 'sample_payloads.jsonl').read_text().splitlines()) if p}
    call('POST', args.world.rstrip('/') + '/admin/reset', {})

    if not args.skip_ai:
        triage(relay, args.world, payloads, seeds['wf_support_triage']['trigger']['secret'])
    expense(relay, args.world, payloads, seeds['wf_expense_approval']['trigger']['secret'])
    slow_fulfillment(relay, args.world, args.shipment_world, payloads)
    runaway(relay, args.world)

    keys = [e['idempotency_key'] for e in ledger(args.world)]
    print('\n[Ledger]')
    record('every effect keyed and every key distinct', all(keys) and len(set(keys)) == len(keys),
           f'{len(keys)} entries')

    counts = {t: sum(1 for r in RESULTS if r[0] == t) for t in ('PASS', 'FAIL', 'AI-QUALITY')}
    print(f"\n{counts['PASS']} passed, {counts['FAIL']} failed, {counts['AI-QUALITY']} AI-quality mismatches")
    sys.exit(1 if counts['FAIL'] else 0)


if __name__ == '__main__':
    main()
