"""Unit tests for run_checks.py (task 9.2). A fake executor and temporary directories stand in
for Gradle, npm, Playwright and Docker; no real tool runs and no real log is touched."""
from pathlib import Path
from unittest import mock
import contextlib
import io
import tempfile
import unittest

import run_checks as rc

JUNIT = '<testsuite name="{0}" tests="{1}" skipped="{2}" failures="{3}" errors="{4}"/>'
OK_OUTPUT = {'npm test': 'ℹ tests 58\nℹ pass 58\nℹ fail 0\n', 'npx playwright test': '  27 passed (6.1s)\n',
             '-m unittest': 'Ran 23 tests in 0.1s\n\nOK\n'}


class Fake:
    """Records commands; returns canned output; writes JUnit XML for Gradle test tasks."""

    def __init__(self, backend, codes=None, outputs=None, junit=None):
        self.backend, self.calls = backend, []
        self.codes, self.outputs = codes or {}, dict(OK_OUTPUT, **(outputs or {}))
        self.junit = junit or {'test': [('A', 231, 0, 0, 0)], 'testcontainersTest': [('B', 32, 0, 0, 0)]}

    def __call__(self, cmd, cwd, log):
        line = ' '.join(str(c) for c in cmd)
        self.calls.append(line)
        for task in ('testcontainersTest', 'test'):
            if cmd[0] == './gradlew' and task in cmd:
                folder = self.backend / 'build/test-results' / task
                folder.mkdir(parents=True, exist_ok=True)
                for row in self.junit.get(task, []):
                    (folder / f'TEST-{row[0]}.xml').write_text(JUNIT.format(*row))
                break
        code = next((c for key, c in self.codes.items() if key in line), 0)
        output = next((o for key, o in self.outputs.items() if key in line), '')
        log.write_text(log.read_text() + line + '\n')
        return code, output


class RunChecksTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        backend, frontend = self.tmp / 'backend', self.tmp / 'frontend'
        (frontend / 'node_modules').mkdir(parents=True)
        (frontend / 'dist').mkdir()
        (frontend / 'dist/index.html').write_text('<html>')
        backend.mkdir()
        self.backend = backend
        self.patches = [mock.patch.object(rc, 'LOGS', self.tmp / 'logs'), mock.patch.object(rc, 'BACKEND', backend),
                        mock.patch.object(rc, 'FRONTEND', frontend),
                        mock.patch.object(rc, 'java_problem', lambda: None), mock.patch.object(rc, 'node_problem', lambda: None),
                        mock.patch.object(rc, 'docker_problem', lambda: None),
                        mock.patch.object(rc, 'doc_checks', lambda: ['check_a', 'check_b'])]
        for patch in self.patches:
            patch.start()

    def tearDown(self):
        for patch in self.patches:
            patch.stop()

    def run_main(self, argv, fake=None):
        fake = fake or Fake(self.backend)
        lines = []
        code = rc.main(argv, executor=fake, out=lines.append)
        return code, '\n'.join(lines), fake

    def row(self, text, stage):
        return next(l for l in text.splitlines() if l.startswith(stage + ' '))

    def test_all_stages_pass_and_counts_are_summed(self):
        code, text, fake = self.run_main([])
        self.assertEqual(code, 0)
        for stage in rc.STAGES:
            self.assertIn(' PASS ', self.row(text, stage))
        self.assertIn('6 stage(s) run, 0 failed; 371 automated tests passed; 2 document checks passed', text)

    def test_failing_command_fails_its_stage_and_later_stages_still_run(self):
        fake = Fake(self.backend, codes={'npm run build': 1})
        code, text, fake = self.run_main([], fake)
        self.assertEqual(code, 1)
        self.assertIn('FAIL', self.row(text, 'frontend'))
        self.assertIn('npm run build: exit 1', self.row(text, 'frontend'))
        self.assertIn('nvm use', self.row(text, 'frontend'))  # hint
        self.assertIn(' PASS ', self.row(text, 'docs'))
        self.assertTrue(any('check_b' in c for c in fake.calls))

    def test_junit_failures_and_errors_fail_the_stage(self):
        fake = Fake(self.backend, codes={'test --rerun bootJar': 1}, junit={'test': [('A', 10, 1, 2, 1)]})
        code, text, _ = self.run_main(['--only', 'backend'], fake)
        self.assertEqual(code, 1)
        self.assertRegex(self.row(text, 'backend'), r'FAIL\s+6\s+3')

    def test_missing_prerequisites_fail_without_running_the_command(self):
        cases = [('backend', 'java_problem', 'Java 21 required, found: openjdk version "17"'),
                 ('frontend', 'node_problem', 'Node 24.21.0 required (frontend/.nvmrc), found v20.0.0'),
                 ('mysql', 'docker_problem', 'Docker engine not reachable (docker info failed)')]
        for stage, check, reason in cases:
            with self.subTest(stage=stage), mock.patch.object(rc, check, lambda r=reason: r):
                code, text, fake = self.run_main(['--only', stage])
                self.assertEqual(code, 1)
                self.assertIn(reason, self.row(text, stage))
                self.assertEqual(fake.calls, [])
                self.assertIn('Prerequisite missing', (rc.LOGS / (stage + '.log')).read_text())

    def test_missing_node_modules_and_dist_fail(self):
        (rc.FRONTEND / 'dist/index.html').unlink()
        code, text, fake = self.run_main(['--only', 'browser'])
        self.assertEqual(code, 1)
        self.assertIn('frontend/dist missing', self.row(text, 'browser'))
        (rc.FRONTEND / 'node_modules').rmdir()
        code, text, fake = self.run_main(['--only', 'frontend'])
        self.assertIn('node_modules missing', self.row(text, 'frontend'))
        self.assertEqual(fake.calls, [])

    def test_install_runs_npm_ci_first_even_without_node_modules(self):
        (rc.FRONTEND / 'node_modules').rmdir()
        code, text, fake = self.run_main(['--only', 'frontend', '--install'])
        self.assertEqual(code, 0)
        self.assertEqual(fake.calls, ['npm ci', 'npm test', 'npm run build'])

    def test_skip_reports_not_run_and_is_not_counted(self):
        code, text, fake = self.run_main(['--skip', 'mysql'])
        self.assertEqual(code, 0)
        self.assertIn('SKIPPED', self.row(text, 'mysql'))
        self.assertIn('not run (by request)', self.row(text, 'mysql'))
        self.assertIn('339 automated tests passed', text)
        self.assertIn('not run: mysql', text)
        self.assertFalse(any('testcontainersTest' in c for c in fake.calls))

    def test_only_runs_selected_stages(self):
        code, text, fake = self.run_main(['--only', 'docs'])
        self.assertEqual(code, 0)
        self.assertEqual(len(fake.calls), 2)
        self.assertTrue(all(c.endswith(('scripts/check_a.py', 'scripts/check_b.py')) for c in fake.calls))
        self.assertIn('not run: backend, mysql, frontend, browser, scripts', text)

    def test_unknown_stage_is_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            rc.main(['--only', 'deploy'], executor=Fake(self.backend), out=lambda _: None)
        self.assertEqual(raised.exception.code, 2)

    def test_zero_exit_without_results_is_a_failure(self):
        fake = Fake(self.backend, outputs={'npx playwright test': 'Error: no browsers installed\n'})
        code, text, _ = self.run_main(['--only', 'browser'], fake)
        self.assertEqual(code, 1)
        self.assertIn('no test results found (exit 0)', self.row(text, 'browser'))
        fake = Fake(self.backend, junit={'test': []})
        code, text, _ = self.run_main(['--only', 'backend'], fake)
        self.assertEqual(code, 1)

    def test_stale_junit_results_are_removed_before_gradle_runs(self):
        stale = self.backend / 'build/test-results/test'
        stale.mkdir(parents=True)
        (stale / 'TEST-Old.xml').write_text(JUNIT.format('Old', 500, 0, 0, 0))
        code, text, _ = self.run_main(['--only', 'backend'])
        self.assertRegex(self.row(text, 'backend'), r'PASS\s+231\s+0')
        self.assertFalse((stale / 'TEST-Old.xml').exists())

    def test_gradle_commands_force_rerun_and_offline_only_on_request(self):
        _, _, fake = self.run_main(['--only', 'backend', '--only', 'mysql'])
        self.assertEqual(fake.calls, ['./gradlew --gradle-user-home .gradle/user-home --no-daemon test --rerun bootJar',
                                      './gradlew --gradle-user-home .gradle/user-home --no-daemon testcontainersTest --rerun'])
        _, _, fake = self.run_main(['--only', 'backend', '--offline'])
        self.assertIn('--no-daemon --offline test --rerun', fake.calls[0])

    def test_each_stage_writes_its_log(self):
        self.run_main(['--skip', 'browser'])
        for stage in ('backend', 'mysql', 'frontend', 'scripts', 'docs'):
            self.assertTrue((rc.LOGS / (stage + '.log')).read_text())
        self.assertFalse((rc.LOGS / 'browser.log').exists())


class ParserTest(unittest.TestCase):
    def test_node_counts_spec_and_tap(self):
        self.assertEqual(rc.node_counts('ℹ pass 58\nℹ fail 0\n'), (58, 0))
        self.assertEqual(rc.node_counts('# pass 3\n# fail 2\n'), (3, 2))
        self.assertIsNone(rc.node_counts('Error: Cannot find module'))

    def test_playwright_counts(self):
        self.assertEqual(rc.playwright_counts('  2 failed\n    [chromium] x\n  25 passed (9s)\n'), (25, 2))
        self.assertIsNone(rc.playwright_counts('Error: browserType.launch'))

    def test_unittest_counts(self):
        self.assertEqual(rc.unittest_counts('Ran 23 tests in 1s\n\nOK\n'), (23, 0))
        self.assertEqual(rc.unittest_counts('Ran 5 tests in 1s\n\nFAILED (failures=1, errors=2)\n'), (2, 3))
        self.assertIsNone(rc.unittest_counts('ImportError'))

    def test_junit_counts(self):
        folder = Path(tempfile.mkdtemp())
        self.assertIsNone(rc.junit_counts(folder))
        (folder / 'TEST-A.xml').write_text(JUNIT.format('A', 10, 1, 1, 1))
        (folder / 'TEST-B.xml').write_text(JUNIT.format('B', 4, 0, 0, 0))
        self.assertEqual(rc.junit_counts(folder), (11, 2))


class RealTreeTest(unittest.TestCase):
    def test_docs_stage_excludes_service_checks_and_includes_the_rest(self):
        checks = rc.doc_checks()
        self.assertFalse(rc.SERVICE_CHECKS & set(checks))
        every = {p.stem for p in rc.SCRIPTS.glob('check_*.py')}
        self.assertEqual(set(checks) | rc.SERVICE_CHECKS, every)
        self.assertIn('check_publication_guide', checks)

    def test_script_stage_includes_this_test_module(self):
        self.assertIn('test_run_checks', rc.script_tests())


if __name__ == '__main__':
    unittest.main()
