"""Offline document/source consistency only; not a Relay runtime validator."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/WORKFLOW_SEMANTICS.md'
PACK = ROOT / 'docs/source-review/pack'
CATALOG = json.loads((PACK / 'data/node_catalog.json').read_text())['nodes']
SEEDS = json.loads((PACK / 'data/seed_workflows.json').read_text())['workflows']


def validate(text):
    for link in re.findall(r'\]\(([^)]+)\)', text):
        if link.startswith('https://'):
            assert link == 'https://json-schema.org/draft/2020-12/json-schema-validation', 'Unexpected external citation'
        else:
            assert (DOC.parent / link).is_file(), 'Broken link: ' + link
    for heading in ('Publication and acceptance', 'Validation stages', 'Graph execution', 'Templates and values',
                    'Catalog semantics', 'Step numbering and cap', 'Approval scope', 'Seed walkthroughs', 'Test roadmap and checks'):
        assert '## ' + heading in text, 'Missing section: ' + heading
    for node in CATALOG:
        row = re.search(r'^\| ' + node['type'] + r' \| ([^|]+) \| (.+) \|$', text, re.M)
        assert row, 'Missing catalog node: ' + node['type']
        expected = {k for k, v in node['params'].items() if v.get('templatable')}
        actual = set() if row[1].strip() == 'none' else set(row[1].strip().split(', '))
        assert expected == actual, 'Template flag mismatch: ' + node['type']
    for marker in ('**new triggers require status published**', '**Retries do not increment steps_executed.**',
                   '**A final successful node at the cap succeeds.**', 'greatest sequence strictly less',
                   'substituted text is never re-parsed', 'D01 resolved on 2026-09-28 (preserve supplied graph and enforce approval gates)', 'never push or publish',
                   'Draft 2020-12', 'No routes implemented', 'All runtime tests are **not run**'):
        assert marker in text, 'Missing semantic boundary: ' + marker
    cases = re.findall(r'^\| (W\d+) \| (.+) \| (.+) \|$', text, re.M)
    assert [row[0] for row in cases] == [f'W{i:02}' for i in range(1, 13)], 'Missing test case'
    assert re.findall(r'^\| (P\d+) \|', text, re.M) == [f'P{i:02}' for i in range(1, 11)], 'Missing validation rule'
    tasks = set(re.findall(r'\*\*(\d+\.\d+)\*\*', (ROOT / 'PROJECT_PLAN.md').read_text()))
    refs = set(re.findall(r'^\| (V\d+) \|', (ROOT / 'docs/REQUIREMENTS.md').read_text(), re.M))
    for _, inputs, outcome in cases:
        assert set(re.findall(r'\b\d+\.\d+\b', outcome)) <= tasks, 'Unknown future task'
        assert set(re.findall(r'\bV\d+\b', outcome)) <= refs, 'Unknown verification reference'
    walkthrough = text.split('## Seed walkthroughs')[1].split('## Test roadmap')[0]
    seen = set()
    for wid, path in re.findall(r'^\| (wf_\w+) \| [^|]+ \| (.+) \|$', walkthrough, re.M):
        workflow = next(w for w in SEEDS if w['id'] == wid)
        ids = path.split(' (', 1)[0].split(' → ')
        nodes = {n['id']: n for n in workflow['nodes']}
        assert ids[0] == workflow['entry'], 'Seed entry mismatch'
        for index, node_id in enumerate(ids):
            assert node_id in nodes, 'Unknown seed path node'
            if index + 1 < len(ids):
                node = nodes[node_id]
                assert ids[index+1] in [node.get(k) for k in ('next', 'on_true', 'on_false')], 'Seed edge mismatch'
        if wid != 'wf_runaway':
            assert nodes[ids[-1]].get('next', 'absent') is None, 'Path not terminal'
        seen.add(wid)
    assert seen == {w['id'] for w in SEEDS}, 'Missing seed walkthrough'


def check_seed_compatibility():
    catalog = {n['type']: n for n in CATALOG}
    placeholders = 0
    for w in SEEDS:
        encoded = json.dumps(w, ensure_ascii=False).encode()
        assert len(encoded) <= 1048576 and len(w['name']) <= 200 and len(w.get('description', '')) <= 4000
        nodes = {n['id']: n for n in w['nodes']}
        assert len(nodes) == len(w['nodes']) and w['entry'] in nodes
        assert 1 <= w['limits']['max_steps'] <= 2147483647
        for n in w['nodes']:
            assert re.fullmatch(r'[A-Za-z_][A-Za-z0-9_-]{0,127}', n['id'])
            spec = catalog[n['type']]
            assert set(n['params']) <= set(spec['params'])
            for key, pspec in spec['params'].items():
                assert not pspec['required'] or key in n['params']
                if key not in n['params']:
                    continue
                value = n['params'][key]
                types = {'string': str, 'object': dict, 'number': (int, float)}
                assert isinstance(value, types[pspec['type']]) and not isinstance(value, bool)
                assert 'enum' not in pspec or value in pspec['enum']
                tokens = re.findall(r'{{(.*?)}}', json.dumps(value))
                if tokens:
                    assert pspec.get('templatable'), 'Seed template in literal field'
                for token in tokens:
                    assert re.fullmatch(r'(trigger\.body|nodes\.[A-Za-z_][A-Za-z0-9_-]*\.output)(\.[A-Za-z0-9_-]+)*', token)
                    if token.startswith('nodes.'):
                        assert token.split('.')[1] in nodes
                    placeholders += 1
            edges = ('on_true', 'on_false') if n['type'] == 'condition' else ('next',)
            for edge in edges:
                assert edge in n and (n[edge] is None or n[edge] in nodes)
    runaway = next(w for w in SEEDS if w['id'] == 'wf_runaway')
    nodes = {n['id']: n for n in runaway['nodes']}
    current, visited = runaway['entry'], []
    # Walk declared false branch for a processing order; no nodes/HTTP are executed.
    for _ in range(runaway['limits']['max_steps']):
        visited.append(current)
        n = nodes[current]
        current = n['on_false'] if n['type'] == 'condition' else n['next']
    assert visited == ['check_order', 'is_shipped', 'hold'] * 4
    assert current == 'check_order' and len(visited) == 12
    print(f'PASS: four source seeds fit selected structural rules; {placeholders} template references; runaway graph has 12 visits before next admission')


def main():
    text = DOC.read_text()
    validate(text)
    print('PASS: links, sections, seven catalog template flags, ten validation rules, twelve test cases and seed paths')
    check_seed_compatibility()
    mutations = [
        ('missing node', '| ai | prompt |', '| omitted | prompt |'),
        ('wrong template flag', '| delay | none |', '| delay | seconds |'),
        ('seed path drift', 'confirm → pack_delay → create_shipment → shipped_notice', 'confirm → create_shipment → pack_delay → shipped_notice'),
        ('broken source link', '(source-review/brief.txt)', '(missing.md)'),
        ('missing cap rule', '**Retries do not increment steps_executed.**', 'Retries count again.'),
        ('unknown task', 'Tasks 3.6, 3.8, 3.10;', 'Tasks 99.99;'),
        ('lost template boundary', 'substituted text is never re-parsed', 'substituted text is re-parsed'),
    ]
    for label, old, new in mutations:
        assert old in text
        try:
            validate(text.replace(old, new))
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print('9 checks passed. No application, schema-validator or template-runtime tests run.')


if __name__ == '__main__':
    main()
