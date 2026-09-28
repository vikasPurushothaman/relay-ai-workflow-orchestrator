"""Offline integrity/document checks for task 1.1; no application claims."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tarfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/CAPSTONE_PACK_REVIEW.md'
PACK = ROOT / 'docs/source-review/pack'
checks = 0


def check(condition, label):
    global checks
    if not condition:
        raise AssertionError(label)
    checks += 1
    print('PASS:', label)


def module(name):
    spec = importlib.util.spec_from_file_location(name, PACK / 'scripts' / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


provenance = json.loads((ROOT / 'docs/source-review/pack-provenance.json').read_text())
actual = {str(p.relative_to(PACK)): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in PACK.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.name != '.DS_Store'}
check(actual == provenance['files'] and len(actual) == 16, '16 pinned source files match manifest')
check(provenance['commit'] == 'fe30f4adc2e30ae3b6175ab363a20019f8944da1', 'commit recorded')
review = REVIEW.read_text()
links = re.findall(r'\]\(([^)]+)\)', review)
check(all((REVIEW.parent / link).exists() for link in links if not link.startswith('https:')), 'local review links resolve')
check(all(path in review for path in actual if path != '.gitignore'), 'every pack source referenced')
catalog = json.loads((PACK / 'data/node_catalog.json').read_text())
seeds = json.loads((PACK / 'data/seed_workflows.json').read_text())['workflows']
payloads = [json.loads(line) for line in (PACK / 'data/sample_payloads.jsonl').read_text().splitlines()]
check(len(catalog['nodes']) == 7 and len(catalog['triggers']) == 3, 'catalog counts match review')
check(len(seeds) == 4 and len(payloads) == 8, 'fixture counts match review')
check(all(w['id'] in review for w in seeds) and all(p['id'] in review for p in payloads), 'all seed and payload IDs covered')
check(all(n['type'] in review for n in catalog['nodes']), 'all node types covered')
triage = {n['id']: n for n in seeds[0]['nodes']}
check(triage['route']['params']['right'] == 'refund_request' and triage['route']['on_false'] == 'notify_support'
      and 'Injection expectation versus graph (open)' in review, 'injection graph ambiguity documented')
# Import only inspected standard-library utilities; avoid bytecode in the pinned snapshot.
import sys
sys.dont_write_bytecode = True
provider = module('mock_provider')
reply = provider.build_reply('alpha', 'alpha-small', [{'role': 'user', 'content': 'Classify this refund request as JSON'}])
try:
    json.loads(reply)
except json.JSONDecodeError:
    check(True, 'supplied mock emits non-JSON prose')
else:
    raise AssertionError('mock behavior changed')
checker = module('duplication_check')
output = io.StringIO()
with patch.object(sys, 'argv', ['duplication_check.py']), patch.object(checker.urllib.request, 'urlopen', return_value=io.StringIO('{"entries": []}')), contextlib.redirect_stdout(output):
    try:
        checker.main()
    except SystemExit as exc:
        empty_exit = exc.code
check(empty_exit == 0 and 'PASS:' in output.getvalue(), 'empty-ledger false assurance reproduced offline')
plan = (ROOT / 'PROJECT_PLAN.md').read_text()
api = (ROOT / 'API_DOCUMENTATION.md').read_text()
unfinished = re.search(r'^- \[ \] \*\*(\d+\.\d+)\*\*', plan, re.M)
check('- [x] **1.1**' in plan and unfinished is not None
      and '**Next item: ' + unfinished.group(1) + ' ' in plan, 'checklist and next item agree')
check('Workflow draft CRUD, publication, manual/webhook triggers, approval decisions, cancellation, run list/detail (task6.1) and redacted run trace payloads (task6.2) are implemented; the read-and-operate console consumes them (tasks6.3–6.12).' in api, 'API implementation status remains honest')
check('never push or publish' in plan and 'manual user task' in plan, 'local-only publication rule preserved')
check('**3.8** Implement frozen published definitions and per-run definition snapshots' in plan
      and '**5.11** Implement run cancellation' in plan, 'scope corrections and cancellation tracked')
check('Application smoke test, live duplication drill, runtime failures, and AI demo were **not run**' in review, 'runtime verification explicitly outstanding')
print(f'{checks} checks passed.')
