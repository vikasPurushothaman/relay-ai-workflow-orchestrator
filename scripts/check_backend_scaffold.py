#!/usr/bin/env python3
"""Check the built task 2.4 scaffold, version locks, wrapper integrity and API docs."""
import hashlib
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
backend = ROOT/'backend'
provenance = json.loads((ROOT/'docs/backend-wrapper-provenance.json').read_text())
assert hashlib.sha256((backend/'gradle/wrapper/gradle-wrapper.jar').read_bytes()).hexdigest() == provenance['wrapperJarSha256']
properties = (backend/'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'distributionSha256Sum='+provenance['distributionSha256'] in properties
assert 'gradle-9.8.0-bin.zip' in properties
assert (backend/'gradlew.bat').is_file() and os.access(backend/'gradlew', os.X_OK)
print('PASS: generated wrapper files, executable launcher, archive pin and wrapper checksum')

baseline = json.loads((ROOT/'docs/version-sources/baseline.json').read_text())
locked = {}
for line in (backend/'gradle.lockfile').read_text().splitlines():
    if line.startswith('#') or not line or line.startswith('empty='):
        continue
    coordinate, configurations = line.split('=', 1)
    group, name, version = coordinate.split(':')
    locked[f'{group}:{name}'] = (version, configurations.split(','))
for name, prop in {
    'com.mysql:mysql-connector-j':'mysql.version',
    'org.flywaydb:flyway-core':'flyway.version',
    'org.flywaydb:flyway-mysql':'flyway.version',
    'org.hibernate.orm:hibernate-core':'hibernate.version',
    'tools.jackson.core:jackson-databind':'jackson-bom.version',
    'org.springframework:spring-webmvc':'spring-framework.version',
    'org.springframework.security:spring-security-core':'spring-security.version',
    'org.junit.jupiter:junit-jupiter-engine':'junit-jupiter.version',
}.items():
    assert locked[name][0] == baseline['boot_managed'][prop], name
for starter in ['webmvc','validation','data-jpa','security','flyway','actuator']:
    name = 'org.springframework.boot:spring-boot-starter-'+starter
    assert locked[name][0] == baseline['pins']['Spring Boot']
    assert 'runtimeClasspath' in locked[name][1]
assert all('h2database' not in name for name in locked)
print('PASS: all required starters and managed library versions locked; no H2 substitute')

report = ET.parse(backend/'build/test-results/test/TEST-com.relay.bootstrap.HealthEndpointsTest.xml').getroot()
assert int(report.attrib['tests']) == 23
assert all(int(report.attrib[k]) == 0 for k in ('failures','errors','skipped'))
assert (backend/'build/libs/relay-backend-0.1.0.jar').is_file()
print('PASS: 23 passing HTTP tests, none skipped, and packaged jar exists')

api = (ROOT/'API_DOCUMENTATION.md').read_text()
for path in ['/actuator/health','/actuator/health/liveness','/actuator/health/readiness']:
    assert '| GET | '+path+' |' in api
for phrase in ['Workflow draft CRUD, publication, manual/webhook triggers, approval decisions, cancellation, run list/detail (task6.1) and redacted run trace payloads (task6.2) are implemented; the read-and-operate console consumes them (tasks6.3–6.12).', 'malformed JSON',
               'OUT_OF_SERVICE', '401', '503', 'No token is needed', 'No session', 'test bootJar']:
    assert phrase.lower() in api.lower(), phrase
for doc in [ROOT/'API_DOCUMENTATION.md', ROOT/'docs/SETUP.md']:
    for target in re.findall(r'\]\(([^)]+)\)',doc.read_text()):
        if not target.startswith('https:'):
            assert (doc.parent/target.split('#')[0]).exists(),target
print('PASS: route contracts, implementation boundaries and documentation links')
