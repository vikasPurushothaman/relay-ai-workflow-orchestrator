"""Offline architecture document consistency checks, not runtime verification."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/ARCHITECTURE.md'
PACK = ROOT / 'docs/source-review/pack'


def validate(text):
    for link in re.findall(r'\]\(([^)]+)\)', text):
        assert (DOC.parent / link).is_file(), 'Broken link: ' + link
    req = (ROOT / 'docs/REQUIREMENTS.md').read_text()
    for prefix in ('R', 'V'):
        known = set(re.findall(r'^\| (' + prefix + r'\d+) \|', req, re.M))
        assert set(re.findall(r'\b' + prefix + r'\d+\b', text)) <= known, 'Unknown ' + prefix + ' reference'
    plan = (ROOT / 'PROJECT_PLAN.md').read_text()
    known_tasks = set(re.findall(r'\*\*(\d+\.\d+)\*\*', plan))
    for tasks in re.findall(r'\| ([\d., –]+); V', text):
        assert set(re.findall(r'\d+\.\d+', tasks)) <= known_tasks, 'Unknown task reference'
    catalog = json.loads((PACK / 'data/node_catalog.json').read_text())
    for node in catalog['nodes']:
        assert '| `' + node['type'] + '` |' in text, 'Missing handler: ' + node['type']
    fixed = (PACK / 'docs/API_CONTRACT.md').read_text().split('## Fixed Contract')[1].split('## Personas')[0]
    for route in re.findall(r'`((?:GET|POST) /[^`]+)`', fixed):
        assert '| `' + route + '` |' in text, 'Missing API owner: ' + route
    for section in ('Decisions and rationale', 'Runtime topology', 'Internal module boundaries',
                    'Persistence and execution flows', 'Configuration contract',
                    'External services and startup', 'Verification and remaining design work'):
        assert '## ' + section in text, 'Missing section: ' + section
    config = text.split('## Configuration contract')[1].split('## External services')[0]
    rows = re.findall(r'^\| ((?:RELAY_|MOCK_|VITE_).*?) \| (.*?) \| (.*?) \|$', config, re.M)
    assert len(rows) == 12 and all(owner.strip() and rules.strip() for _, owner, rules in rows), 'Configuration consumers/rules'
    for marker in ('not the execution architecture', 'API handlers never execute nodes', 'network calls happen outside database transactions',
                   'D01 resolved on 2026-09-28 (preserve supplied graph and enforce approval gates)', 'never push or publish', 'localhost:9210', 'one active worker process'):
        assert marker in text, 'Missing boundary: ' + marker


def main():
    text = DOC.read_text()
    validate(text)
    print('PASS: links, R/V/task references, seven handlers, eight API owners, configuration and boundaries')
    for label, old, new in (
        ('missing handler', '| `ai` |', '| `unknown` |'),
        ('broken link', '(MVP.md)', '(MISSING.md)'),
        ('unknown task', '4.14, 7.3, 7.4; V06', '99.99; V06'),
        ('missing route', '| `GET /workflows` |', '| `GET /missing` |'),
        ('missing config consumer', '| worker/provider adapter |', '|  |'),
    ):
        assert old in text
        try:
            validate(text.replace(old, new))
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print('6 checks passed. No runtime tests run.')


if __name__ == '__main__':
    main()
