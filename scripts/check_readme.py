"""Offline consistency checks for README.md against the source tree: environment
variables and defaults, routes, links, quoted error messages, ports and jar path.
Does not start services or run the documented commands."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / 'README.md'
JAVA = ROOT / 'backend/src/main/java'

SECTIONS = ('Current status', 'Prerequisites', 'Quick start', 'Configuration', 'Seed loading', 'API overview',
            'Testing', 'Shutdown and reset', 'Troubleshooting', 'Known limitations', 'Publication')
# String constants in Java that look like settings but are not environment variables.
NOT_ENV = {'RELAY_MANAGEMENT'}  # Spring Security authority name
# Troubleshooting symptoms produced by other tools, not by Relay source.
EXTERNAL = {'java: command not found', 'docker compose up', '--offline', 'npm run dev', 'npm run build'}
STALE = ('planned', 'not implemented', 'placeholder', 'relay-ai-workflow-orchestrator')
SECRETS = (r'sk-[A-Za-z0-9_-]{10,}', r'\b[0-9a-f]{32,}\b')
# README phrase -> text that must appear in the verification report.
CLAIMS = {'371 automated tests, 0 failures': '371 automated tests, 0 failures'}
ENV_NAME = r'(?:RELAY|MOCK|VITE)_[A-Z0-9_]+'


def slug(heading):
    return re.sub(r'[^a-z0-9 -]', '', heading.lower()).replace(' ', '-')


def section(text, name):
    return text.split('\n## ' + name + '\n', 1)[1].split('\n## ', 1)[0]


def read_sources():
    java = '\n'.join(p.read_text() for p in sorted(JAVA.rglob('*.java')))
    examples = {p: p.read_text() for p in (ROOT / '.env.example', ROOT / 'backend/.env.example',
                                            ROOT / 'frontend/.env.example')}
    tests = '\n'.join(p.read_text() for p in sorted((ROOT / 'frontend/tests').glob('*.ts')))
    compose = (ROOT / 'compose.yaml').read_text()
    env = {n for n in re.findall(r'"(' + ENV_NAME + ')"', java)} - NOT_ENV
    for body in examples.values():
        env |= set(re.findall(r'^(' + ENV_NAME + ')=', body, re.M))
    env |= set(re.findall(r'process\.env\.(' + ENV_NAME + ')', tests))
    env |= set(re.findall(r'\$\{(' + ENV_NAME + '):-', compose))
    defaults = {}
    reads = r'(?:getProperty|value|integer)\(\s*(?:env\s*,\s*)?"(' + ENV_NAME + r')"\s*,\s*("[^"]*"|\d+)'
    for name, value in re.findall(reads, java):
        value = value.strip('"')
        if value:
            assert defaults.get(name, value) == value, 'Conflicting code defaults: ' + name
            defaults[name] = value
    constant = re.search(r'DEFAULT_ORIGINS="([^"]+)"', java)  # RELAY_ALLOWED_ORIGINS default lives in ManagementCors
    if constant and 'getProperty("RELAY_ALLOWED_ORIGINS", com.relay.security.ManagementCors.DEFAULT_ORIGINS)' in java:
        defaults['RELAY_ALLOWED_ORIGINS'] = constant.group(1)
    defaults.update(re.findall(r'\$\{(' + ENV_NAME + r'):-([^}]+)\}', compose))
    defaults.update(re.findall(r'^(VITE_[A-Z_]+)=(.+)$', examples[ROOT / 'frontend/.env.example'], re.M))
    # Validation messages are built as key + " " + rule; quote them the way they print.
    messages = [k + ' ' + r for k, r in re.findall(r'invalid\("(' + ENV_NAME + r')",\s*"([^"]+)"\)', java)]
    corpus = '\n'.join([java, (ROOT / 'backend/build.gradle').read_text(), *messages])
    api = (ROOT / 'API_DOCUMENTATION.md').read_text()
    index = api.split('\n## Route index\n', 1)[1].split('\n## ', 1)[0]
    routes = set(re.findall(r'^\| (GET|POST|PUT|DELETE|PATCH) \| (/\S*) \|', index, re.M))
    seeds = json.loads((ROOT / 'backend/src/main/resources/catalog/seed_workflows.json').read_text())
    mock = (ROOT / 'scripts/run_mock.py').read_text()
    ports = re.search(r"(\d+) if args\.role == 'world' else (\d+)", mock).groups()
    version = re.search(r"^version = '([^']+)'", (ROOT / 'backend/build.gradle').read_text(), re.M).group(1)
    report = (ROOT / 'docs/VERIFICATION_REPORT.md').read_text()
    return dict(env=env, defaults=defaults, corpus=corpus, routes=routes, report=report, ports=ports,
                seeds=[w['id'] for w in seeds['workflows']], jar=f'backend/build/libs/relay-backend-{version}.jar')


def validate(text, src):
    for name in SECTIONS:
        assert '\n## ' + name + '\n' in text, 'Missing section: ' + name
    # Environment variables: complete and nothing invented.
    config = section(text, 'Configuration')
    documented = set(re.findall(r'`(' + ENV_NAME + ')`', config))
    assert not src['env'] - documented, 'Undocumented variables: ' + ', '.join(sorted(src['env'] - documented))
    assert not documented - src['env'], 'Unknown variables: ' + ', '.join(sorted(documented - src['env']))
    rows = dict(re.findall(r'^\| `(' + ENV_NAME + r')` \|[^|\n]*\| `([^`]+)` \|', config, re.M))
    for name, value in src['defaults'].items():
        assert rows.get(name) == value, f'Default for {name} must be `{value}`, README has {rows.get(name)!r}'
    # Routes: identical to the API_DOCUMENTATION route index.
    api = section(text, 'API overview')
    readme_routes = set()
    for methods, path in re.findall(r'^\| ((?:`[A-Z]+`(?: / )?)+) \| `(/[^`]*)` \|', api, re.M):
        readme_routes |= {(m, path) for m in re.findall(r'`([A-Z]+)`', methods)}
    assert readme_routes == src['routes'], 'Route mismatch: missing ' + str(sorted(src['routes'] - readme_routes)) \
        + ', extra ' + str(sorted(readme_routes - src['routes']))
    # Links and anchors, including anchors in other Markdown files.
    for link in re.findall(r'\]\(([^)]+)\)', text):
        if re.match(r'https?://', link):
            continue
        target, _, anchor = link.partition('#')
        path = ROOT / target if target else DOC
        assert path.exists(), 'Broken link: ' + link
        if anchor:
            anchors = {slug(h) for h in re.findall(r'^#+ (.+)$', path.read_text(), re.M)}
            assert anchor in anchors, 'Broken anchor: ' + link
    # Every quoted troubleshooting symptom exists in source or is a known external message.
    trouble = section(text, 'Troubleshooting')
    for symptom in re.findall(r'^\| ([^|\n]+) \|', trouble, re.M)[2:]:
        quoted = re.findall(r'`([^`]+)`', symptom)
        for q in quoted:
            assert q in EXTERNAL or q in src['corpus'], 'Quoted message not in source: ' + q
    # Facts derived from the build and scripts.
    assert src['jar'] in text, 'Jar path must be ' + src['jar']
    for port in src['ports']:
        assert '127.0.0.1:' + port in text, 'Mock port missing: ' + port
    seeds = section(text, 'Seed loading')
    for seed in src['seeds']:
        assert '`' + seed + '`' in seeds, 'Seed missing: ' + seed
    for claim, source in CLAIMS.items():
        assert claim in text and source in src['report'], 'Unsupported claim: ' + claim
    lowered = text.lower()
    for word in STALE:
        assert word not in lowered, 'Stale wording: ' + word
    for pattern in SECRETS:
        assert not re.search(pattern, text), 'Credential-like value: ' + pattern
    assert 'never pushed or published' in section(text, 'Publication'), 'Missing never-push statement'
    for placeholder in ('_pending_', 'TODO', 'TBD', 'XXX'):
        assert placeholder not in text, 'Placeholder left: ' + placeholder


def main():
    text = DOC.read_text()
    src = read_sources()
    validate(text, src)
    print(f'PASS: {len(SECTIONS)} sections; {len(src["env"])} variables documented, none invented; '
          f'{len(src["defaults"])} defaults match code/compose/frontend example')
    print(f'PASS: {len(src["routes"])} routes match the API_DOCUMENTATION route index; links/anchors resolve; '
          'troubleshooting messages found in source; jar path, mock ports and seed IDs match')
    mutations = [
        ('missing section', '\n## Troubleshooting\n', '\n## Problems\n'),
        ('undocumented variable', '| `RELAY_JOB_RENEW_MS` |', '| JOB_RENEW |'),
        ('invented variable', '| `RELAY_API_PORT` |', '| `RELAY_API_PORT`, `RELAY_API_HOST` |'),
        ('changed default', '| `RELAY_JOB_LEASE_MS` | API, worker | `60000` |', '| `RELAY_JOB_LEASE_MS` | API, worker | `30000` |'),
        ('missing route', '| `GET` | `/runs` |', '| `GET` | `/run-list` |'),
        ('broken link', '(docs/CONSOLE_DEMO.md)', '(docs/CONSOLE_DEMO_MISSING.md)'),
        ('broken anchor', '(#seed-loading)', '(#seeding)'),
        ('broken cross-file anchor', 'VERIFICATION_REPORT.md#reproducing', 'VERIFICATION_REPORT.md#reproduce'),
        ('invented error message', '`RELAY_LOAD_SEEDS must be true or false`', '`RELAY_LOAD_SEEDS must be a boolean`'),
        ('stale wording', '| Run list/detail and redacted trace APIs | Implemented and tested |',
         '| Run list/detail and redacted trace APIs | Planned next |'),
        ('credential value', 'paste the token from', 'paste the token ' + 'a1' * 32 + ' from'),
        ('wrong mock port', 'http://127.0.0.1:9001', 'http://127.0.0.1:9002'),
        ('missing never-push statement', 'never pushed or published', 'kept private'),
    ]
    for label, old, new in mutations:
        assert old in text, 'Absent mutation target: ' + label
        try:
            validate(text.replace(old, new), src)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    drifted = dict(src, defaults=dict(src['defaults'], RELAY_JOB_POLL_MS='500'))
    try:
        validate(text, drifted)
    except AssertionError:
        print('PASS: rejects code default drift')
    else:
        raise AssertionError('Undetected source drift')
    print(f'{2 + len(mutations) + 1} checks passed. No services started; commands are not executed by this check.')


if __name__ == '__main__':
    main()
