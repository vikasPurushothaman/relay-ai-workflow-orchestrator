"""Offline task 1.2 document checks; does not verify application behavior."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/REQUIREMENTS.md'
PACK = ROOT / 'docs/source-review/pack'


def validate(document):
    def require(ok, label):
        if not ok:
            raise AssertionError(label)

    plan = (ROOT / 'PROJECT_PLAN.md').read_text()
    task_ids = set(re.findall(r'\*\*(\d+\.\d+)\*\*', plan))
    links = re.findall(r'\]\(([^)]+)\)', document)
    require(all((DOC.parent / link).exists() for link in links), 'source/document links resolve')
    rows = re.findall(r'^\| (R\d+) \| (.*?) \| (.*?) \| (.*?) \|$', document, re.M)
    require([row[0] for row in rows] == [f'R{i:02}' for i in range(1, 23)], '22 requirements mapped')
    cases = set(re.findall(r'^\| (V\d+) \|', document, re.M))
    require(cases == {f'V{i:02}' for i in range(1, 24)}, '23 verification cases defined')
    for rid, source, implementation, verification in rows:
        require(any(s in source for s in ('PDF', 'PS ', 'API ', 'IG ')), rid + ' source attribution')
        for cell in (implementation, verification):
            refs = re.findall(r'\b\d+\.\d+\b', cell)
            require(bool(refs) and set(refs) <= task_ids, rid + ' valid task references')
        require(bool(re.findall(r'V\d+', verification)) and
                set(re.findall(r'V\d+', verification)) <= cases, rid + ' verification case')
    contract = (PACK / 'docs/API_CONTRACT.md').read_text()
    fixed = contract.split('## Fixed Contract')[1].split('## Personas')[0]
    routes = re.findall(r'`((?:GET|POST) /[^`]+)`', fixed)
    require(len(routes) == 8 and all(f'`{route}`' in document for route in routes), 'eight fixed routes')
    statuses = re.search(r'3\. The run status set: (.+)', fixed).group(1)
    require('Run statuses: ' + statuses in document, 'exact run status set')
    catalog = json.loads((PACK / 'data/node_catalog.json').read_text())
    for node in catalog['nodes']:
        row = re.search(r'^\| `' + node['type'] + r'` \| (.*?) \| (.*) \| (.*?) \|$', document, re.M)
        require(row is not None, 'node ' + node['type'])
        params = row.group(1)
        for name, spec in node['params'].items():
            require(name + ('' if spec['required'] else '?') + ':' + spec['type'] in params,
                    node['type'] + ' parameter ' + name)
            require(all(value in params for value in spec.get('enum', [])), node['type'] + ' enum')
        output = node['output']
        require((json.dumps(output, separators=(',', ':')) if isinstance(output, dict) else output)
                in row.group(2), node['type'] + ' output')
    seeds = json.loads((PACK / 'data/seed_workflows.json').read_text())['workflows']
    payloads = [json.loads(line) for line in (PACK / 'data/sample_payloads.jsonl').read_text().splitlines()]
    require(all(w['id'] in document for w in seeds), 'four seed IDs')
    require(all(p['id'] in document for p in payloads), 'eight payload IDs')
    for marker in ('D01', 'regardless of classification', 'schedule/cron execution',
                   'Optional limit fields are preserved, not enforced', 'never push or publish',
                   'Application tests and drills are **not run**', 'X-Relay-Secret',
                   'Idempotency-Key', '"error":{"message":', 'invalid_node_type',
                   'trigger.config', 'limits.max_steps', 'limits.timeout_seconds', 'limits.max_ai_tokens'):
        require(marker in document, 'contract/scope marker: ' + marker)
    require('Workflow draft CRUD, publication, manual/webhook triggers, approval decisions, cancellation, run list/detail (task6.1) and redacted run trace payloads (task6.2) are implemented; the read-and-operate console consumes them (tasks6.3–6.12).' in
            (ROOT / 'API_DOCUMENTATION.md').read_text(), 'API status honest')


def main():
    document = DOC.read_text()
    validate(document)
    print('PASS: requirements matrix, source links, roadmap tasks, verification cases, contracts, fixtures and scope')
    mutations = {
        'missing fixed route': ('`GET /workflows`', '`GET /removed`'),
        'invalid task reference': ('| 2.8, 3.5, 3.9 |', '| 99.99 |'),
        'missing node': ('| `delay` |', '| `removed` |'),
        'wrong parameter required flag': ('seconds:number', 'seconds?:number'),
        'wrong output shape': ('"notification_id":"string"', '"notification_id":"number"'),
        'missing payload': ('pay_003', 'removed_payload'),
        'missing seed': ('wf_expense_approval', 'removed_seed'),
        'broken link': ('(source-review/brief.txt)', '(missing-source.txt)'),
        'lost ambiguity': ('regardless of classification', 'resolved'),
        'lost status': ('Run statuses: `queued`', 'Run statuses: `unknown`'),
        'missing requirement': ('| R22 |', '| R99 |'),
    }
    for label, (old, new) in mutations.items():
        assert old in document, label + ': mutation target exists'
        try:
            validate(document.replace(old, new))
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print('12 checks passed (1 document validation + 11 negative mutations). No application tests run.')


if __name__ == '__main__':
    main()
