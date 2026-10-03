"""Publication guide check (task 9.1): docs/PUBLICATION.md matches the repository, keeps every
publication step with the owner, and is linked from the README. Reads files and local git
state only; never contacts the remote, merges or pushes."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SECTIONS = ('Repository state', '1. Pre-publication checks', '2. Decide the pack licence (D04)',
            '3. Update `main` (owner runs these)', '4. Repository settings (owner, on GitHub)', '5. After publishing')
REQUIRED_PHRASES = ('**Only the owner runs the publication steps.**', 'never push, upload or change the remote',
                    'The agent does not make it', '**Option A', '**Option B', 'it does not push')


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True)


def validate(guide, readme):
    headings = re.findall(r'^## (.+)$', guide, re.M)
    for name in SECTIONS:
        assert any(h.startswith(name) for h in headings), 'Missing section: ' + name
    for phrase in REQUIRED_PHRASES:
        assert phrase in guide, 'Missing statement: ' + phrase
    # Every relative link and every scripts/ path resolves.
    for target in re.findall(r'\]\(([^)#]+)(?:#[^)]*)?\)', guide):
        if not target.startswith('http'):
            assert (ROOT / 'docs' / target).resolve().exists(), 'Broken link: ' + target
    for script in set(re.findall(r'scripts/[\w./-]+\.py', guide)):
        assert (ROOT / script).is_file(), 'Missing script: ' + script
    # Every "never commit" path is ignored by git and not tracked.
    table = guide.split('### Never commit these', 1)[1].split('\n## ', 1)[0]
    paths = re.findall(r'^\| `([^`]+)` \|', table, re.M)
    assert len(paths) >= 8, 'Never-commit table too short'
    for path in paths:
        probe = path + 'x' if path.endswith('/') else path
        assert git('check-ignore', '-q', '--no-index', probe).returncode == 0, 'Not ignored by .gitignore: ' + path
        assert not git('ls-files', '--', path).stdout.strip(), 'Tracked although never-commit: ' + path
    # The main update is a fast-forward from an existing branch that contains main.
    merges = re.findall(r'^git merge (.+)$', guide, re.M)
    assert merges, 'No merge step'
    for args in merges:
        assert args.split()[0] == '--ff-only', 'Merge step is not --ff-only: ' + args
        source = args.split()[-1]
        assert git('rev-parse', '--verify', '-q', 'refs/heads/' + source).returncode == 0, 'Merge source branch missing: ' + source
        assert git('merge-base', '--is-ancestor', 'main', source).returncode == 0, 'main is not an ancestor of ' + source
    remote = git('remote', 'get-url', 'origin').stdout.strip()
    if remote:
        assert '`' + remote + '`' in guide, 'Guide does not name the configured origin URL'
    assert '](docs/PUBLICATION.md)' in readme, 'README does not link the publication guide'
    return len(headings), len(paths), len(merges)


def main():
    guide = (ROOT / 'docs/PUBLICATION.md').read_text()
    readme = (ROOT / 'README.md').read_text()
    sections, paths, merges = validate(guide, readme)
    print(f'PASS: {sections} sections, owner-only statements and D04 options present; links and scripts resolve')
    print(f'PASS: {paths} never-commit paths ignored and untracked; {merges} merge step is --ff-only from a branch containing main; '
          'origin URL named; README links the guide')
    mutations = [
        ('removed owner-only statement', 0, lambda g: g.replace('**Only the owner runs the publication steps.**', '', 1)),
        ('broken link', 0, lambda g: g.replace('](DEMO.md)', '](DEMO_MISSING.md)', 1)),
        ('non-ignored never-commit path', 0, lambda g: g.replace('| `tmp/` |', '| `docs/` |', 1)),
        ('merge without --ff-only', 0, lambda g: g.replace('git merge --ff-only UIENCH', 'git merge UIENCH', 1)),
        ('missing D04 section', 0, lambda g: g.replace('## 2. Decide the pack licence (D04)', '## 2. Licence', 1)),
        ('README without the link', 1, lambda r: r.replace('](docs/PUBLICATION.md)', '](docs/PUBLISH.md)')),
    ]
    for label, which, change in mutations:
        docs = [guide, readme]
        changed = change(docs[which])
        assert changed != docs[which], 'Absent mutation target: ' + label
        docs[which] = changed
        try:
            validate(*docs)
        except AssertionError:
            print('PASS: rejects ' + label)
        else:
            raise AssertionError('Undetected mutation: ' + label)
    print(f'{2 + len(mutations)} checks passed. Local files and git state only; nothing pushed.')


if __name__ == '__main__':
    main()
