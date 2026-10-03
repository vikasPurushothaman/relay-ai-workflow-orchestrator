"""Offline consistency checks for docs/DEMO.md against the seeds, pack payloads, route
index, helper script, Java error codes and Phase 7 evidence. Starts no services."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/DEMO.md'
# Brief's required video items (and pack extras) -> scene heading that must cover each.
SCENES = {'trigger': 'Scene 2 — Trigger and AI branching', 'AI branching': 'Scene 2 — Trigger and AI branching',
          'prompt injection': 'Scene 3 — Prompt injection', 'approval gate': 'Scene 4 — Approval gate',
          'crash and resume': 'Scene 5 — Worker crash and resume', 'step cap': 'Scene 6 — Step cap',
          'smoke test': 'Scene 7 — Supplied smoke test', 'console': 'Scene 1 — The console'}
# Recorded result quoted in the demo -> text that must be in the evidence.
CLAIMS = {'28 passed, 0 warnings, 0 failed': '28 passed, 0 warnings, 0 failed',
          '`complaint` → `notify_support`, succeeded, 1 chat.message': 'pay_001 -> complaint/notify_support, succeeded, 1 chat.message',
          'paused at `refund_gate` despite the "already approved" claim': 'refund_gate despite the "already approved" claim',
          'exactly 1 email; repeat 409': 'succeeded via approved_notice, exactly 1 email',
          '3 executed, 0 replays': 'executed: 3, replays absorbed: 0',
          'failed `max_steps` at 12/12': '12/12',
          'nothing to the attacker address': 'nothing addressed to audit@evil-example.com'}
# Words in backticks that are neither identifiers from the sources nor code: statuses and fields.
VOCAB = {'running', 'succeeded', 'failed', 'cancelled', 'delivered', 'refunded', 'initial', 'schema_repair',
         'chat.message', 'email.send', 'order.refund', 'shipment.create', 'customer_email', 'refund_request',
         'sample_payloads.jsonl', 'ai', 'condition', 'approval', 'order_action'}


def load():
    seeds = json.loads((ROOT / 'backend/src/main/resources/catalog/seed_workflows.json').read_text())['workflows']
    payloads = {}
    for line in (ROOT / 'docs/source-review/pack/data/sample_payloads.jsonl').read_text().splitlines():
        if line.strip():
            item = json.loads(line)
            payloads[item['id']] = item['workflow']
    java = '\n'.join(p.read_text() for p in (ROOT / 'backend/src/main/java').rglob('*.java'))
    api = (ROOT / 'API_DOCUMENTATION.md').read_text().split('\n## Route index\n', 1)[1].split('\n## ', 1)[0]
    routes = [re.sub(r'\\\{[^}]+\\\}', '[^/]+', re.escape(path)) for path in re.findall(r'^\| (?:GET|POST|PUT) \| (/\S*) \|', api, re.M)]
    fields = set()
    for w in seeds:
        for n in w['nodes']:
            fields |= set(n.get('params', {}).get('output_schema', {}).get('properties', {}))
    return dict(workflows={w['id'] for w in seeds}, nodes={n['id'] for w in seeds for n in w['nodes']},
                secrets={w['trigger']['secret'] for w in seeds if w['trigger'].get('secret')},
                payloads=payloads, codes=set(re.findall(r'"([a-z]+(?:_[a-z0-9]+)+)"', java)), routes=routes, fields=fields,
                helper=set(re.findall(r"add_parser\('([a-z-]+)'\)", (ROOT / 'scripts/demo.py').read_text())),
                orders=set(re.findall(r'"(ord_\d+)": \{', (ROOT / 'docs/source-review/pack/scripts/mock_world.py').read_text())),
                files={p.name for d in ('scripts', 'docs/source-review/pack/scripts') for p in (ROOT / d).glob('*.py')},
                evidence=(ROOT / 'docs/phase7-verification.txt').read_text() + (ROOT / 'docs/duplication-check-results.txt').read_text())


def validate(text, src):
    for item, heading in SCENES.items():
        assert '\n## ' + heading in text, f'Missing scene for {item}: {heading}'
    for wf in re.findall(r'\bwf_[a-z_]+', text):
        assert wf in src['workflows'], 'Unknown workflow: ' + wf
    for pay in re.findall(r'\bpay_[a-z0-9_]+', text):
        assert pay in src['payloads'], 'Unknown payload: ' + pay
    for block in re.findall(r'```sh\n(.*?)```', text, re.S):
        for command in re.split(r'\n(?! )', block):
            target = re.search(r'/(?:hooks|workflows)/(wf_[a-z_]+)', command)
            for pay in re.findall(r'demo\.py payload (pay_[a-z0-9_]+)', command):
                if target:
                    assert src['payloads'][pay] == target.group(1), f'{pay} belongs to {src["payloads"][pay]}, not {target.group(1)}'
            for sub in re.findall(r'scripts/demo\.py ([a-z-]+)', command):
                assert sub in src['helper'], 'Unknown demo.py subcommand: ' + sub
            for script in re.findall(r'python3 ((?:scripts|docs/\S+)/[a-z_]+\.py)', command):
                assert (ROOT / script).is_file(), 'Missing script: ' + script
    for path in re.findall(r'"\$API(/[^"?]*)', text):  # sh blocks and inline commands alike
        assert any(re.fullmatch(r, path) for r in src['routes']), 'Route not in API index: ' + path
    known = src['workflows'] | src['nodes'] | set(src['payloads']) | src['codes'] | src['fields'] | src['orders'] | src['files'] | VOCAB
    for token in re.findall(r'`([a-z][a-z0-9_.]*)`', text):
        if '_' in token or '.' in token or token in src['nodes']:
            assert token in known or re.fullmatch(r'http_\d{3}', token), 'Unknown identifier in backticks: ' + token
    for claim, source in CLAIMS.items():
        assert claim in text, 'Missing recorded result: ' + claim
        assert source in src['evidence'], 'Recorded result not in evidence: ' + claim
    for value in src['secrets']:
        assert value not in text, 'Literal webhook secret in the demo script'
    for pattern in (r'\b[0-9a-f]{32,}\b', r'sk-[A-Za-z0-9-]{12,}'):
        assert not re.search(pattern, text), 'Credential-like value: ' + pattern
    for link in re.findall(r'\]\(([^)]+)\)', text):
        if not link.startswith('http'):
            assert (DOC.parent / link.partition('#')[0]).exists(), 'Broken link: ' + link


def main():
    text, src = DOC.read_text(), load()
    validate(text, src)
    print(f'PASS: {len(set(SCENES.values()))} scenes cover the {len(SCENES)} required items; workflow, payload and node IDs exist; '
          'each payload is sent to its own workflow; routes are in the API index; scripts and subcommands exist')
    print(f'PASS: {len(CLAIMS)} recorded results found in Phase 7 evidence; no webhook secret or credential-like value; links resolve')
    secret = sorted(src['secrets'])[0]
    mutations = [
        ('missing scene', '\n## Scene 6 — Step cap', '\n## Scene 6 — Loops'),
        ('payload sent to wrong workflow', 'hooks/wf_expense_approval" -H', 'hooks/wf_support_triage" -H'),
        ('unknown payload', 'payload pay_inject_002)', 'payload pay_inject_003)'),
        ('unknown node', '`finance_gate`', '`finance_gates`'),
        ('unknown order', '`ord_2002` is still `delivered`', '`ord_2009` is still `delivered`'),
        ('route not in index', '"$API/approvals/<id>/approve"', '"$API/approvals/<id>/accept"'),
        ('unknown subcommand', 'demo.py worker-pid', 'demo.py worker'),
        ('literal secret', 'export TRIAGE_SECRET="$(python3 scripts/demo.py secret wf_support_triage)"', 'export TRIAGE_SECRET=' + secret),
        ('unsupported recorded result', '28 passed, 0 warnings, 0 failed', '29 passed, 0 warnings, 0 failed'),
        ('broken link', '(CONSOLE_DEMO.md)', '(CONSOLE.md.bak)'),
    ]
    for label, old, new in mutations:
        assert old in text, 'Absent mutation target: ' + label
        try:
            validate(text.replace(old, new, 1), src)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print(f'{2 + len(mutations)} checks passed. No services started.')


if __name__ == '__main__':
    main()
