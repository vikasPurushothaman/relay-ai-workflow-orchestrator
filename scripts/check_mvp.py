"""Offline reference and coverage checks for the MVP scope document."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'docs/MVP.md'
PACK = ROOT / 'docs/source-review/pack'


def validate(document):
    requirements = (ROOT / 'docs/REQUIREMENTS.md').read_text()
    for prefix in ('R', 'V'):
        expected = set(re.findall(r'^\| (' + prefix + r'\d+) \|', requirements, re.M))
        actual = set(re.findall(r'\b' + prefix + r'\d+\b', document))
        assert actual == expected, f'{prefix} references missing or unknown: {actual ^ expected}'
    for target in re.findall(r'\]\(([^)]+)\)', document):
        assert (DOC.parent / target).is_file(), 'Broken link: ' + target
    catalog = json.loads((PACK / 'data/node_catalog.json').read_text())
    for node in catalog['nodes']:
        assert '`' + node['type'] + '`' in document, 'Missing node: ' + node['type']
    seeds = json.loads((PACK / 'data/seed_workflows.json').read_text())['workflows']
    payloads = [json.loads(line) for line in (PACK / 'data/sample_payloads.jsonl').read_text().splitlines()]
    for item in seeds + payloads:
        assert item['id'] in document, 'Missing fixture: ' + item['id']
    for marker in ('Scope definition only', 'not implemented or runtime-verified',
                   '**Application-ready:**', '**Submission-ready:**',
                   'never push or publish', 'manual user task',
                   'D01 — injection-pause ambiguity', 'D02', 'D03', 'D04',
                   'Java, Spring Boot, Spring Data JPA and MySQL',
                   '## Excluded scope', 'schedule/cron execution',
                   'without promising enforcement', 'one repair attempt',
                   'X-Relay-Secret', 'Idempotency-Key', 'terminal cancellation returns 409'):
        assert marker.lower() in document.lower(), 'Missing boundary: ' + marker
    print('PASS: all R/V references, local links, seven nodes, fixtures and scope boundaries')


def main():
    document = DOC.read_text()
    validate(document)
    mutations = {
        'omitted requirement': ('R16', 'R99'),
        'unknown verification case': ('V17', 'V99'),
        'broken source link': ('(REQUIREMENTS.md)', '(MISSING.md)'),
        'missing node': ('`approval`', '`missing`'),
        'lost ambiguity': ('D01 — injection-pause ambiguity', 'Resolved'),
    }
    for label, (old, new) in mutations.items():
        assert old in document, 'Mutation target missing: ' + old
        try:
            validate(document.replace(old, new))
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print('6 checks passed: document consistency plus 5 invalid-copy cases. No runtime verification.')


if __name__ == '__main__':
    main()
