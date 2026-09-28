#!/usr/bin/env python3
"""Characterization tests: the unchanged supplied duplication checker run against
the unchanged supplied mock world, so its PASS/FAIL meaning is known (task 7.4)."""
import http.client
import json
import subprocess
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer

import run_mock

CHECKER = run_mock.PACK / 'scripts/duplication_check.py'


class DuplicationCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.world = ThreadingHTTPServer(('127.0.0.1', 0), run_mock.load_mock('world').Handler)
        cls.url = f'http://127.0.0.1:{cls.world.server_port}'
        threading.Thread(target=cls.world.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.world.shutdown()
        cls.world.server_close()

    def setUp(self):
        self.post('/admin/reset', {})

    def post(self, path, body, key=None):
        conn = http.client.HTTPConnection('127.0.0.1', self.world.server_port, timeout=5)
        headers = {'Content-Type': 'application/json'}
        if key:
            headers['Idempotency-Key'] = key
        conn.request('POST', path, body=json.dumps(body), headers=headers)
        status = conn.getresponse().status
        conn.close()
        return status

    def email(self, key=None, to='lena@example.com'):
        return self.post('/email/send', {'to': to, 'subject': 'Order received', 'message': 'We received ord_2003'}, key)

    def check(self, *args):
        result = subprocess.run([sys.executable, str(CHECKER), '--url', self.url, *args],
                                capture_output=True, text=True, timeout=30)
        return result.returncode, result.stdout

    def test_drill_shaped_clean_ledger_passes(self):
        self.email('run_a:1')
        self.post('/shipments', {'order_id': 'ord_2003'}, 'run_a:3')
        self.email('run_a:4', to='lena+shipped@example.com')
        code, out = self.check()
        self.assertEqual(code, 0, out)
        self.assertIn('executed: 3, replays absorbed: 0, rejected: 0', out)
        self.assertNotIn('WARN', out)

    def test_replay_with_same_key_is_absorbed_not_duplicated(self):
        self.post('/shipments', {'order_id': 'ord_2003'}, 'run_b:3')
        self.post('/shipments', {'order_id': 'ord_2003'}, 'run_b:3')
        code, out = self.check()
        self.assertEqual(code, 0, out)
        self.assertIn('executed: 1, replays absorbed: 1', out)

    def test_keyless_resend_is_flagged_as_duplicate_and_warned(self):
        self.post('/shipments', {'order_id': 'ord_2003'})
        self.post('/shipments', {'order_id': 'ord_2003'})
        code, out = self.check()
        self.assertEqual(code, 1, out)
        self.assertIn('FAIL: 1 duplicated side effect(s)', out)
        self.assertIn('WARN: 2 executed side effect(s) carried no Idempotency-Key', out)

    def test_resend_with_a_new_key_is_a_duplicate(self):
        self.post('/shipments', {'order_id': 'ord_2003'}, 'run_c:3')
        self.post('/shipments', {'order_id': 'ord_2003'}, 'run_c:3-retry')
        code, out = self.check()
        self.assertEqual(code, 1, out)
        self.assertIn('shipment.create executed 2x', out)

    def test_since_scopes_out_earlier_entries(self):
        self.email()
        self.email()
        self.assertEqual(self.check()[0], 1)
        self.email('run_d:1', to='other@example.com')
        code, out = self.check('--since', '2')
        self.assertEqual(code, 0, out)
        self.assertIn('Ledger entries checked: 1', out)

    def test_rejected_calls_are_not_counted(self):
        self.post('/shipments', {'order_id': 'ord_9999'}, 'run_e:3')
        self.post('/shipments', {'order_id': 'ord_9999'}, 'run_e:3')
        code, out = self.check()
        self.assertEqual(code, 0, out)
        self.assertIn('executed: 0, replays absorbed: 0, rejected: 2', out)

    def test_empty_ledger_passes_so_exit_code_alone_is_not_evidence(self):
        code, out = self.check()
        self.assertEqual(code, 0, out)
        self.assertIn('Ledger entries checked: 0', out)


if __name__ == '__main__':
    unittest.main()
