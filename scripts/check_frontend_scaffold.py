#!/usr/bin/env python3
"""Verify the frontend dependency pins and reproducible scaffold artifacts."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
frontend = ROOT / 'frontend'
package = json.loads((frontend / 'package.json').read_text())
lock = json.loads((frontend / 'package-lock.json').read_text())
pins = json.loads((ROOT / 'docs/version-sources/baseline.json').read_text())['pins']
for name, label in {
    'react': 'React', 'react-dom': 'React DOM', 'react-router': 'React Router',
    'vite': 'Vite', 'typescript': 'TypeScript', '@vitejs/plugin-react': 'Vite React plugin',
    '@types/node': 'Node types', '@types/react': 'React types', '@types/react-dom': 'React DOM types',
}.items():
    assert lock['packages']['node_modules/' + name]['version'] == pins[label], name
    assert {**package['dependencies'], **package['devDependencies']}[name] == pins[label], name
assert package['engines'] == {'node': pins['Node.js'], 'npm': pins['npm']}
assert package['packageManager'] == 'npm@' + pins['npm']
assert (frontend / '.nvmrc').read_text().strip() == pins['Node.js']
assert lock['packages']['node_modules/@playwright/test']['version'] == '1.63.0'
assert package['private'] is True
assert package['scripts']['build'] == 'tsc --noEmit && vite build'
assert (frontend / 'dist/index.html').is_file()
assert not (frontend / 'yarn.lock').exists() and not (frontend / 'pnpm-lock.yaml').exists()
print('PASS: exact frontend dependencies/lock, Node/npm pins, private package, typechecked build command and built artifact')
