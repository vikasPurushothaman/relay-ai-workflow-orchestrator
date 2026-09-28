#!/usr/bin/env python3
"""Regression tests for crash_window_proxy against the unchanged supplied mock world."""
import http.client
import json
import socket
import threading
import time
import unittest
from http.server import ThreadingHTTPServer

import crash_window_proxy
import run_mock


def call(port, method, path, body=None, headers=None, timeout=10):
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
    try:
        data = json.dumps(body).encode() if body is not None else None
        hdrs = {'Content-Type': 'application/json'} if data else {}
        hdrs.update(headers or {})
        conn.request(method, path, body=data, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        return resp.status, (json.loads(raw) if raw else {}), dict(resp.getheaders())
    finally:
        conn.close()


class CrashWindowProxyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.world = ThreadingHTTPServer(('127.0.0.1', 0), run_mock.load_mock('world').Handler)
        cls.world_port = cls.world.server_port
        threading.Thread(target=cls.world.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.world.shutdown()
        cls.world.server_close()

    def setUp(self):
        call(self.world_port, 'POST', '/admin/reset', {})
        self.proxy = crash_window_proxy.serve(0, f'http://127.0.0.1:{self.world_port}', 'POST /shipments', 10)
        self.port = self.proxy.server_port
        threading.Thread(target=self.proxy.serve_forever, daemon=True).start()

    def tearDown(self):
        call(self.port, 'POST', '/_proxy/release')
        self.proxy.shutdown()
        self.proxy.server_close()

    def ledger(self):
        return call(self.world_port, 'GET', '/admin/ledger')[1]['entries']

    def test_forwards_reads_errors_and_idempotency_headers_unchanged(self):
        self.assertEqual(call(self.port, 'GET', '/orders/ord_2003')[:2], call(self.world_port, 'GET', '/orders/ord_2003')[:2])
        self.assertEqual(call(self.port, 'GET', '/orders/ord_9999')[0], 404)
        key = {'Idempotency-Key': 'run_x:1'}
        first = call(self.port, 'POST', '/chat/message', {'channel': '#c', 'message': 'hi'}, key)
        second = call(self.port, 'POST', '/chat/message', {'channel': '#c', 'message': 'hi'}, key)
        self.assertEqual(first[0], 200)
        self.assertEqual(second[1], first[1])
        self.assertEqual(second[2].get('x-mockworld-replayed'), 'true')
        self.assertEqual([(e['idempotency_key'], e['replayed']) for e in self.ledger()], [('run_x:1', False), ('run_x:1', True)])

    def test_holds_first_matching_response_after_target_executed_it(self):
        result = {}
        client = threading.Thread(target=lambda: result.update(r=call(self.port, 'POST', '/shipments', {'order_id': 'ord_2003'}, {'Idempotency-Key': 'run_y:3'})))
        client.start()
        deadline = time.time() + 5
        while not call(self.port, 'GET', '/_proxy/state')[1]['holding'] and time.time() < deadline:
            time.sleep(0.05)
        self.assertEqual(call(self.port, 'GET', '/_proxy/state')[1], {'holding': True, 'held_count': 1, 'released': False})
        self.assertEqual([e['action'] for e in self.ledger()], ['shipment.create'])  # effect exists while held
        self.assertTrue(client.is_alive())
        call(self.port, 'POST', '/_proxy/release')
        client.join(5)
        self.assertEqual(result['r'][0], 201)
        self.assertTrue(result['r'][1]['shipment_id'].startswith('shp_'))
        started = time.time()  # a later matching request is not held
        self.assertEqual(call(self.port, 'POST', '/shipments', {'order_id': 'ord_2003'}, {'Idempotency-Key': 'run_y:3'})[2].get('x-mockworld-replayed'), 'true')
        self.assertLess(time.time() - started, 2)

    def test_survives_client_disconnect_while_holding(self):
        with self.assertRaises((socket.timeout, TimeoutError)):
            call(self.port, 'POST', '/shipments', {'order_id': 'ord_2003'}, {'Idempotency-Key': 'run_z:1'}, timeout=0.5)
        call(self.port, 'POST', '/_proxy/release')
        time.sleep(0.2)
        self.assertEqual(call(self.port, 'GET', '/orders/ord_2003')[0], 200)
        self.assertEqual(len(self.ledger()), 1)

    def test_control_routes_are_local_and_unknown_ones_rejected(self):
        self.assertEqual(call(self.port, 'GET', '/_proxy/nope')[0], 404)
        self.assertEqual(call(self.port, 'GET', '/_proxy/state')[1]['held_count'], 0)
        self.assertEqual(self.ledger(), [])


if __name__ == '__main__':
    unittest.main()
