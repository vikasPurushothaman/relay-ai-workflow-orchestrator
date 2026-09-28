#!/usr/bin/env python3
"""Offline task 2.2 documentation checks; does not resolve or build dependencies."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'docs/version-sources'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def read_json(name):
    return json.loads((SOURCES / name).read_text())


def version(value):
    assert re.fullmatch(r'\d+(?:\.\d+){1,3}(?:\.Final)?', value), value
    return tuple(map(int, value.removesuffix('.Final').split('.')))


def accepts(value, expression):
    """Only the simple >= and caret OR ranges present in captured metadata."""
    v = version(value)
    for clause in expression.split(' || '):
        match = re.fullmatch(r'(>=|\^)(\d+\.\d+\.\d+)', clause)
        assert match, f'Unsupported range; review checker: {expression}'
        operator, minimum = match.groups()
        low = version(minimum)
        high = (low[0]+1, 0, 0) if low[0] else (0, low[1]+1, 0)
        if v >= low and (operator == '>=' or v < high):
            return True
    return False


def validate(document, baseline):
    pins = baseline['pins']
    for key, value in {**pins, **baseline['boot_managed']}.items():
        version(value)
        assert f'| {key} | {value} |' in document, f'Missing/changed pin: {key}'
    for link in re.findall(r'\]\(([^)]+)\)', document):
        if not link.startswith('https:'):
            assert (ROOT / 'docs' / link.split('#')[0]).exists(), link
    for phrase in ('Document/metadata verification only', 'integration tests were run in task 2.2',
                   'Gradle Groovy DSL', 'package-lock.json', 'distributionSha256Sum',
                   'source-based compatibility inference', 'local-only schema loader'):
        assert phrase in document, phrase
    assert '**Document/metadata verification only; no application dependency resolution or\nintegration tests were run in task 2.2.**' in document
    mapping = {'Vite':'vite', 'Vite React plugin':'vite-react', 'React':'react',
               'React DOM':'react-dom', 'TypeScript':'typescript', 'Node types':'types-node',
               'React types':'types-react', 'React DOM types':'types-react-dom',
               'React Router':'react-router', 'npm':'npm-selected'}
    package_versions = {}
    for name, stem in mapping.items():
        data = read_json(stem+'.json')
        assert data['version'] == pins[name], name
        package_versions[data['name']] = pins[name]
        if 'node' in data.get('engines', {}):
            assert accepts(pins['Node.js'], data['engines']['node']), name
    for stem in mapping.values():
        data = read_json(stem+'.json')
        for peer, constraint in data.get('peerDependencies', {}).items():
            optional = data.get('peerDependenciesMeta', {}).get(peer, {}).get('optional', False)
            if peer in package_versions:
                assert accepts(package_versions[peer], constraint), (peer, constraint)
            else:
                assert optional, f'Missing required peer: {peer}'
    node = next(x for x in read_json('node.json') if x['version'] == 'v'+pins['Node.js'])
    assert node['lts'] and node['npm'] == pins['npm']
    gradle = read_json('gradle.json')
    assert gradle['version'] == pins['Gradle Wrapper'] and not gradle['snapshot']
    assert 17 <= version(pins['Java JDK'])[0] <= 26
    assert version(pins['Gradle Wrapper'])[0] == 9
    assert version(pins['Java JDK'])[0] == 21  # selected compile/daemon baseline
    assert re.fullmatch('[a-f0-9]{64}', (SOURCES/'gradle.sha256').read_text().strip())
    bom = ET.parse(SOURCES/'boot-bom.pom').getroot()
    assert bom.find('m:version', NS).text == pins['Spring Boot']
    for prop, expected in baseline['boot_managed'].items():
        assert bom.find('m:properties/m:'+prop, NS).text == expected, prop
    schema = ET.parse(SOURCES/'schema.pom').getroot()
    assert schema.find('m:version', NS).text == pins['JSON Schema validator']
    requested = schema.find('m:properties/m:version.jackson', NS).text
    managed = baseline['boot_managed']['jackson-bom.version']
    assert version(requested)[:2] == version(managed)[:2] and version(managed) >= version(requested)
    image = (SOURCES/'mysql-image.txt').read_text()
    block = next(b for b in image.split('\n\n') if 'Tags: '+pins['MySQL image']+',' in b)
    assert 'Architectures: amd64, arm64v8' in block
    source = (SOURCES/'flyway-mysql.java').read_text()
    assert 'ensureDatabaseIsRecentEnough("5.1")' in source
    assert 'recommendFlywayUpgradeIfNecessary("9.4")' in source
    assert version('8.0') < version(pins['MySQL image']) < version('9.4')


if __name__ == '__main__':
    manifest = read_json('provenance.json')
    for name, entry in manifest['files'].items():
        assert hashlib.sha256((SOURCES/name).read_bytes()).hexdigest() == entry['sha256'], name
    print(f'PASS: {len(manifest["files"])} source snapshots match provenance')
    document = (ROOT/'docs/SETUP.md').read_text()
    baseline = read_json('baseline.json')
    validate(document, baseline)
    print('PASS: pins, metadata, engines/peers, BOM, image architectures, source compatibility and links')
    mutations = [
        document.replace('| Gradle Wrapper | 9.8.0 |', ''),
        document.replace('| React | 19.3.0 |', '| React | 18.0.0 |'),
        document.replace('(version-sources/baseline.json)', '(missing-baseline.json)'),
        document.replace('no application dependency resolution', 'application dependency resolution'),
    ]
    for changed in mutations:
        try:
            validate(changed, baseline)
        except AssertionError:
            pass
        else:
            raise AssertionError('Invalid document accepted')
    print('PASS: 4 invalid document mutations rejected')
    for value, constraint, expected in [('22.11.9','^20.19.0 || >=22.12.0',False),
                                         ('22.12.0','^20.19.0 || >=22.12.0',True),
                                         ('19.2.0','^19.3.0',False),
                                         ('19.3.0','^19.3.0',True)]:
        assert accepts(value, constraint) == expected
    print('PASS: 4 engine/peer boundary cases')
    print('10 checks passed (source integrity, baseline, 4 mutations, 4 boundaries).')
