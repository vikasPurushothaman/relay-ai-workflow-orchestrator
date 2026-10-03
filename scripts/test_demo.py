#!/usr/bin/env python3
"""Demo helper tests: pack/seed lookups, run and ledger formatting against a local stub
server, token handling and worker-PID selection. No real Relay stack or network."""
import contextlib
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import unittest
from unittest.mock import patch
import demo

RUN = {'run_id': 'run_x', 'workflow_id': 'wf_runaway', 'status': 'failed',
       'error': {'code': 'max_steps', 'node_id': 'check_order'},
       'steps': [{'sequence': 1, 'node_id': 'check_order', 'status': 'succeeded', 'attempt_count': 1,
                  'idempotency_key': None, 'attempts': [{'cause': 'initial'}]},
                 {'sequence': 2, 'node_id': 'notify', 'status': 'succeeded', 'attempt_count': 2,
                  'idempotency_key': 'run_x:2', 'attempts': [{'cause': 'initial'}, {'cause': 'recovery'}]}]}
LEDGER = {'entries': [{'seq': 1, 'action': 'email.send', 'idempotency_key': 'run_x:2', 'replayed': False},
                      {'seq': 2, 'action': 'email.send', 'idempotency_key': 'run_x:2', 'replayed': True}]}


class Stub(BaseHTTPRequestHandler):
    seen = []

    def do_GET(self):
        Stub.seen.append((self.path, self.headers.get('Authorization')))
        if self.path == '/admin/ledger':
            body, status = LEDGER, 200
        elif self.path == '/runs/run_x' and self.headers.get('Authorization') == 'Bearer good':
            body, status = RUN, 200
        else:
            body, status = {'error': {'code': 'unauthorized'}}, 401
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class DemoHelperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Stub)
        cls.url = f'http://127.0.0.1:{cls.server.server_port}'
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def cli(self, *argv, env=None):
        out, err = io.StringIO(), io.StringIO()
        with patch.dict('os.environ', env or {}, clear=False), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = demo.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def test_payload_prints_exact_pack_body(self):
        for line in demo.PAYLOADS.read_text().splitlines():
            item = json.loads(line)
            if item['id'] in ('pay_001', 'pay_201'):
                code, out, _ = self.cli('payload', item['id'])
                self.assertEqual(code, 0)
                self.assertEqual(json.loads(out), item['body'])

    def test_payload_unknown_id_fails(self):
        code, out, err = self.cli('payload', 'pay_999')
        self.assertEqual((code, out), (2, ''))
        self.assertIn('Unknown payload ID', err)

    def test_secret_only_for_webhook_seeds(self):
        seeds = {w['id']: w for w in json.loads(demo.SEEDS.read_text())['workflows']}
        code, out, _ = self.cli('secret', 'wf_expense_approval')
        self.assertEqual((code, out.strip()), (0, seeds['wf_expense_approval']['trigger']['secret']))
        for workflow, message in (('wf_runaway', 'not a webhook'), ('wf_missing', 'Unknown workflow')):
            code, out, err = self.cli('secret', workflow)
            self.assertEqual((code, out), (2, ''))
            self.assertIn(message, err)

    def test_run_summary_sends_token_and_formats_steps(self):
        code, out, _ = self.cli('--api', self.url, 'run', 'run_x', env={'RELAY_DEMO_TOKEN': 'good'})
        self.assertEqual(code, 0)
        self.assertIn('run_x  wf_runaway  failed  error=max_steps@check_order', out)
        self.assertIn('check_order', out)
        self.assertIn('attempts=2 [initial,recovery] key=run_x:2', out)
        self.assertIn(('/runs/run_x', 'Bearer good'), Stub.seen)

    def test_run_rejected_token_and_missing_token_fail_clearly(self):
        code, out, err = self.cli('--api', self.url, 'run', 'run_x', env={'RELAY_DEMO_TOKEN': 'bad'})
        self.assertEqual((code, out), (2, ''))
        self.assertIn('HTTP 401', err)
        code, out, err = self.cli('--api', self.url, 'run', 'run_x', env={'RELAY_DEMO_TOKEN': ''})
        self.assertEqual((code, out), (2, ''))
        self.assertIn('Export RELAY_DEMO_TOKEN', err)

    def test_unreachable_api_fails_clearly(self):
        code, _, err = self.cli('--api', 'http://127.0.0.1:1', 'run', 'run_x', env={'RELAY_DEMO_TOKEN': 'good'})
        self.assertEqual(code, 2)
        self.assertIn('Cannot reach', err)

    def test_ledger_marks_replays(self):
        code, out, _ = self.cli('--world', self.url, 'ledger')
        self.assertEqual(code, 0)
        lines = out.strip().splitlines()
        self.assertEqual(len(lines), 2)
        self.assertTrue(lines[0].rstrip().endswith('executed'))
        self.assertTrue(lines[1].rstrip().endswith('REPLAYED'))

    def test_worker_pid_selects_single_non_api_jvm(self):
        ps = ('11 /usr/bin/java -jar backend/build/libs/relay-backend-0.1.0.jar\n'
              '22 /usr/bin/java -jar backend/build/libs/relay-backend-0.1.0.jar\n'
              '33 python3 scripts/run_mock.py world\n44 java -jar other-app.jar\n')
        self.assertEqual(demo.worker_pid('8080', ps, '11\n'), '22')
        with self.assertRaisesRegex(demo.DemoError, 'found 0'):
            demo.worker_pid('8080', ps.replace('22 /usr/bin/java -jar backend/build/libs/relay-backend-0.1.0.jar\n', ''), '11\n')
        with self.assertRaisesRegex(demo.DemoError, 'found 2'):
            demo.worker_pid('8080', ps, '')


if __name__ == '__main__':
    unittest.main()
