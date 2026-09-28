#!/usr/bin/env python3
"""Inject failures through the supplied mocks' /admin/config and check each run's
retry, timeout and failure handling against docs/RECOVERY.md (task 7.5).

Prerequisites: API, one worker in RELAY_AI_MODE=mock-http pointed at --provider,
and the supplied mock world at --world, which must also be the worker's
MOCK_WORLD_URL and receive wf_slow_fulfillment's shipment (localhost:9210).
The world ledger is reset first, so use a disposable world. Both mocks are
restored to healthy settings on exit. Default engine settings expected:
3 attempts, 1 s/2 s backoff, 10 s HTTP and 30 s AI timeouts. Takes ~5 minutes.

    python3 scripts/verify_failure_modes.py --url http://localhost:8081 \
        --token-file <file> --world http://localhost:9210 --provider http://localhost:9311
"""
import argparse
import json
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import verify_seed_scenarios as v
from verify_seed_scenarios import Relay, call, effects, ledger, record, step, ts

HEALTHY = {'mode': 'ok', 'fail_rate': 0, 'latency_ms': 0}
TERMINAL = v.TERMINAL


def configure(base, **settings):
    code, body = call('POST', base.rstrip('/') + '/admin/config', {**HEALTHY, **settings})
    assert code == 200, f'mock config failed: {code} {body}'


def executed(entries, run_id):
    return [e for e in effects(entries, run_id) if not e['replayed'] and 200 <= e['status'] < 300]


def attempts(s):
    return (s or {}).get('attempts') or []


def codes(s):
    return [(a.get('error') or {}).get('code') for a in attempts(s)]


def gaps(s):
    a = attempts(s)
    return [round((ts(a[i + 1]['started_at']) - ts(a[i]['started_at'])).total_seconds(), 1) for i in range(len(a) - 1)]


def expense(relay, secret, label):
    code, body = relay.hook('wf_expense_approval', secret,
                            {'employee_email': 'dev2@example.com', 'amount_usd': 40, 'description': f'Team lunch ({label})'})
    return v.accepted(label, code, body)


def exhausted(label, run, node, code, count=3):
    s = step(run, node)
    record(f'{label}: run failed retry_exhausted at {node}',
           run.get('status') == 'failed' and (run.get('error') or {}).get('code') == 'retry_exhausted',
           f"{run.get('status')} {run.get('error')}")
    record(f'{label}: {count} failed attempts, each {code}',
           [a['status'] for a in attempts(s)] == ['failed'] * count and codes(s) == [code] * count,
           f'{[a["status"] for a in attempts(s)]} {codes(s)}')
    return s


def outage_then_restore(relay, world, payloads):
    print('\n[W1] World outage mid-run, then restore (wf_slow_fulfillment)')
    run_id = v.accepted('W1', *relay.api('POST', '/workflows/wf_slow_fulfillment/trigger', {'input': payloads['pay_201']['body']}))
    if not run_id:
        return
    deadline = time.time() + 30
    while time.time() < deadline and (step(relay.poll(run_id, TERMINAL, 1), 'pack_delay') or {}).get('status') != 'waiting':
        time.sleep(0.2)
    configure(world, mode='down')
    restored = False
    deadline = time.time() + 60
    while time.time() < deadline:
        ship = step(relay.api('GET', f'/runs/{run_id}')[1], 'create_shipment')
        if ship and any(a['status'] == 'failed' for a in attempts(ship)):
            configure(world)
            restored = True
            break
        time.sleep(0.2)
    record('W1: world restored after the first shipment failure', restored)
    run = relay.poll(run_id, TERMINAL, 60)
    ship = step(run, 'create_shipment')
    first = attempts(ship)[0] if attempts(ship) else {}
    record('W1: first attempt failed http_503 and scheduled a 1000 ms retry',
           first.get('status') == 'failed' and (first.get('error') or {}).get('code') == 'http_503'
           and (first.get('error') or {}).get('cause') == 'transport_retry' and (first.get('error') or {}).get('delay_ms') == 1000,
           json.dumps(first.get('error')))
    record('W1: a later attempt succeeded; run succeeded',
           run.get('status') == 'succeeded' and attempts(ship)[-1]['status'] == 'succeeded'
           and attempts(ship)[-1]['cause'] == 'transport_retry', f"{run.get('status')} {[(a['status'], a['cause']) for a in attempts(ship)]}")
    shipments = executed(ledger(world), run_id)
    record('W1: exactly one shipment executed, one key for the step',
           sum(e['action'] == 'shipment.create' for e in shipments) == 1 and len(shipments) == 3
           and {e['idempotency_key'] for e in effects(ledger(world), run_id, 'shipment.create')} == {ship['idempotency_key']},
           f'{len(shipments)} executed effects')


def exhaustion(relay, world, secret):
    print('\n[W2] World down throughout: retry exhaustion')
    configure(world, mode='down')
    try:
        run_id = expense(relay, secret, 'W2')
        run = relay.poll(run_id, TERMINAL, 60) if run_id else {}
    finally:
        configure(world)
    s = exhausted('W2', run, 'auto_ok', 'http_503')
    record('W2: backoff 1000 then 2000 ms, observed gaps >= planned',
           [(a.get('error') or {}).get('delay_ms') for a in attempts(s)][:2] == [1000, 2000]
           and len(gaps(s)) == 2 and gaps(s)[0] >= 1.0 and gaps(s)[1] >= 2.0, f'gaps {gaps(s)} s')
    record('W2: no side effect recorded', run_id is not None and not effects(ledger(world), run_id))


def flaky(relay, world, secret, runs=10):
    print(f'\n[W3] Flaky world: fail_rate 0.3 over {runs} runs')
    configure(world, fail_rate=0.3)
    try:
        ids = [expense(relay, secret, f'W3-{i}') for i in range(runs)]
        with ThreadPoolExecutor(runs) as pool:
            finished = list(pool.map(lambda r: relay.poll(r, TERMINAL, 60), [i for i in ids if i]))
    finally:
        configure(world)
    entries = ledger(world)
    succeeded = [r for r in finished if r.get('status') == 'succeeded']
    failed = [r for r in finished if r.get('status') == 'failed']
    retried = sum(1 for r in succeeded if len(attempts(step(r, 'auto_ok'))) > 1)
    record('W3: every run finished (succeeded or retry_exhausted)', len(succeeded) + len(failed) == runs,
           f'{len(succeeded)} succeeded ({retried} after retries), {len(failed)} exhausted')
    record('W3: succeeded runs executed exactly one email; earlier attempts failed http_500',
           all(len(executed(entries, r['run_id'])) == 1 and codes(step(r, 'auto_ok'))[:-1] == ['http_500'] * (len(attempts(step(r, 'auto_ok'))) - 1)
               for r in succeeded))
    record('W3: exhausted runs made 3 http_500 attempts and no effect',
           all((r.get('error') or {}).get('code') == 'retry_exhausted' and codes(step(r, 'auto_ok')) == ['http_500'] * 3
               and not effects(entries, r['run_id']) for r in failed), f'{len(failed)} exhausted')


def slow_within_timeout(relay, world, secret):
    print('\n[W4] Slow world within the 10 s timeout: latency 3000 ms')
    configure(world, latency_ms=3000)
    try:
        run_id = expense(relay, secret, 'W4')
        run = relay.poll(run_id, TERMINAL, 60) if run_id else {}
    finally:
        configure(world)
    s = step(run, 'auto_ok')
    record('W4: succeeded in one attempt lasting >= 3 s',
           run.get('status') == 'succeeded' and len(attempts(s)) == 1 and (attempts(s)[0].get('duration_ms') or 0) >= 3000,
           f"{run.get('status')} {[a.get('duration_ms') for a in attempts(s)]} ms")


def slow_beyond_timeout(relay, world, secret):
    print('\n[W5] Slow world beyond the timeout: latency 12000 ms (> 10 s)')
    configure(world, latency_ms=12000)
    try:
        run_id = expense(relay, secret, 'W5')
        run = relay.poll(run_id, TERMINAL, 90) if run_id else {}
    finally:
        configure(world)
    s = exhausted('W5', run, 'auto_ok', 'http_timeout')
    record('W5: each attempt bounded by the 10 s timeout (steps do not hang)',
           all(9000 <= (a.get('duration_ms') or 0) <= 11500 for a in attempts(s)), f"{[a.get('duration_ms') for a in attempts(s)]} ms")
    time.sleep(15)  # let the world finish requests the client abandoned
    entries = effects(ledger(world), run_id)
    done, replays = [e for e in entries if not e['replayed']], [e for e in entries if e['replayed']]
    record('W5: world executed the abandoned request at most once; resends replayed',
           len(done) <= 1 and all(e['idempotency_key'] == s['idempotency_key'] for e in entries),
           f'executed {len(done)}, replayed {len(replays)} (run failed although the effect happened: unknown-effect evidence)')


def provider_failure(relay, world, provider, secret, payloads, label, settings, code, timeout_s=60):
    print(f'\n[{label}] Provider {settings} -> {code}')
    configure(provider, **settings)
    try:
        code_, body = relay.hook('wf_support_triage', secret, payloads['pay_003']['body'])
        run_id = v.accepted(label, code_, body)
        run = relay.poll(run_id, TERMINAL, timeout_s) if run_id else {}
    finally:
        configure(provider)
    s = exhausted(label, run, 'classify', code)
    record(f'{label}: no downstream step, no effect',
           [x['node_id'] for x in run.get('steps', [])] == ['classify'] and run_id and not effects(ledger(world), run_id))
    return s


def malformed_output(relay, world, provider, secret, payloads):
    print('\n[P4] Provider replies in prose: schema enforcement')
    configure(provider)
    run_id = v.accepted('P4', *relay.hook('wf_support_triage', secret, payloads['pay_003']['body']))
    run = relay.poll(run_id, TERMINAL, 60) if run_id else {}
    s = step(run, 'classify')
    record('P4: initial then schema_repair attempt, both invalid_ai_json',
           [(a['status'], a['cause']) for a in attempts(s)] == [('failed', 'initial'), ('failed', 'schema_repair')]
           and codes(s) == ['invalid_ai_json'] * 2 and s.get('ai_repair_count') == 1,
           f"{[(a['status'], a['cause']) for a in attempts(s)]} {codes(s)} repairs={s and s.get('ai_repair_count')}")
    record('P4: run failed invalid_ai_json; no output passed downstream, no effect',
           run.get('status') == 'failed' and (run.get('error') or {}).get('code') == 'invalid_ai_json'
           and s.get('output') is None and len(run.get('steps', [])) == 1 and not effects(ledger(world), run_id),
           f"{run.get('status')} {run.get('error')}")
    record('P4: provider/model and usage recorded on both attempts',
           all(a.get('provider') and a.get('model') and a.get('tokens_prompt') is not None for a in attempts(s)),
           f"{[(a.get('model'), a.get('tokens_prompt'), a.get('tokens_completion')) for a in attempts(s)]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--url', required=True)
    parser.add_argument('--token-file', required=True)
    parser.add_argument('--world', required=True, help='disposable mock world (reset first); worker MOCK_WORLD_URL')
    parser.add_argument('--provider', required=True, help='supplied mock provider used by the worker')
    args = parser.parse_args()

    relay = Relay(args.url, Path(args.token_file).read_text().strip())
    seeds = {w['id']: w for w in json.loads((v.PACK / 'seed_workflows.json').read_text())['workflows']}
    payloads = {p['id']: p for p in map(json.loads, (v.PACK / 'sample_payloads.jsonl').read_text().splitlines()) if p}
    expense_secret = seeds['wf_expense_approval']['trigger']['secret']
    triage_secret = seeds['wf_support_triage']['trigger']['secret']
    call('POST', args.world.rstrip('/') + '/admin/reset', {})
    try:
        outage_then_restore(relay, args.world, payloads)
        exhaustion(relay, args.world, expense_secret)
        flaky(relay, args.world, expense_secret)
        slow_within_timeout(relay, args.world, expense_secret)
        slow_beyond_timeout(relay, args.world, expense_secret)
        provider_failure(relay, args.world, args.provider, triage_secret, payloads, 'P1', {'mode': 'down'}, 'http_503')
        provider_failure(relay, args.world, args.provider, triage_secret, payloads, 'P2', {'mode': 'rate_limited'}, 'http_429')
        p3 = provider_failure(relay, args.world, args.provider, triage_secret, payloads, 'P3', {'latency_ms': 35000}, 'http_timeout', 180)
        record('P3: each provider attempt bounded by the 30 s AI timeout',
               all(29000 <= (a.get('duration_ms') or 0) <= 32000 for a in attempts(p3)), f"{[a.get('duration_ms') for a in attempts(p3)]} ms")
        malformed_output(relay, args.world, args.provider, triage_secret, payloads)
    finally:
        configure(args.world)
        configure(args.provider)

    print('\n[Ledger]')
    check = subprocess.run([sys.executable, str(v.PACK.parent / 'scripts/duplication_check.py'), '--url', args.world],
                           capture_output=True, text=True)
    record('duplication_check.py over the whole ledger exits 0 with no keyless WARN',
           check.returncode == 0 and 'WARN' not in check.stdout, check.stdout.splitlines()[0] if check.stdout else check.stderr[:120])

    fails = sum(1 for r in v.RESULTS if r[0] == 'FAIL')
    print(f'\n{len(v.RESULTS) - fails} passed, {fails} failed')
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    main()
