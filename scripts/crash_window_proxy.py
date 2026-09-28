#!/usr/bin/env python3
"""Loopback forwarding proxy that stages the "after side effect, before local
completion" crash window for the kill-and-resume drill (task 7.3).

Every request is forwarded unchanged to --target. The first request matching
--hold (e.g. "POST /shipments") is forwarded and executed by the target as
usual, but its response is held until /_proxy/release is called or --hold-seconds
elapse. Kill the worker while the response is held: the target has recorded the
effect, the worker never learned the outcome. Later matching requests pass
straight through, so the recovering worker's resend reaches the target.

    python3 scripts/crash_window_proxy.py --port 9210 \
        --target http://127.0.0.1:9310 --hold "POST /shipments"

Control (never forwarded):
    GET  /_proxy/state     {"holding": bool, "held_count": int, "released": bool}
    POST /_proxy/release   deliver the held response now (the client may be gone)
Stdlib only; binds 127.0.0.1.
"""
import argparse
import http.client
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HOP_BY_HOP = {'connection', 'keep-alive', 'proxy-connection', 'transfer-encoding', 'upgrade', 'te', 'trailer', 'host'}


class Hold:
    def __init__(self, method, path, seconds):
        self.method, self.path, self.seconds = method, path, seconds
        self.lock, self.release = threading.Lock(), threading.Event()
        self.held_count, self.holding = 0, False

    def claim(self, method, path):
        """True for the first matching request only."""
        with self.lock:
            if self.held_count or method != self.method or not path.startswith(self.path):
                return False
            self.held_count, self.holding = 1, True
            return True

    def state(self):
        with self.lock:
            return {'holding': self.holding, 'held_count': self.held_count, 'released': self.release.is_set()}


def make_handler(target, hold):
    parts = urlsplit(target)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, fmt, *args):
            print('proxy:', fmt % args, flush=True)

        def _reply(self, status, body, headers=()):
            self.send_response(status)
            for name, value in headers:
                self.send_header(name, value)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _control(self):
            if self.command == 'GET' and self.path == '/_proxy/state':
                return self._reply(200, json.dumps(hold.state()).encode(), [('Content-Type', 'application/json')])
            if self.command == 'POST' and self.path == '/_proxy/release':
                hold.release.set()
                return self._reply(200, b'{"released": true}', [('Content-Type', 'application/json')])
            return self._reply(404, b'{"error": "unknown proxy control route"}', [('Content-Type', 'application/json')])

        def _forward(self):
            if self.path.startswith('/_proxy/'):
                return self._control()
            length = int(self.headers.get('Content-Length') or 0)
            body = self.rfile.read(length) if length else None
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
            conn = http.client.HTTPConnection(parts.hostname, parts.port, timeout=30)
            try:
                conn.request(self.command, self.path, body=body, headers=headers)
                resp = conn.getresponse()
                status, payload = resp.status, resp.read()
                out = [(k, v) for k, v in resp.getheaders() if k.lower() not in HOP_BY_HOP | {'content-length'}]
            finally:
                conn.close()
            if hold.claim(self.command, self.path):
                print(f'proxy: HOLDING {self.command} {self.path} -> {status} (target already executed it)', flush=True)
                hold.release.wait(hold.seconds)
                with hold.lock:
                    hold.holding = False
                print('proxy: releasing held response', flush=True)
            try:
                self._reply(status, payload, out)
            except (BrokenPipeError, ConnectionResetError):
                print('proxy: client gone before the held response was delivered', flush=True)

        do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _forward

    return Handler


def serve(port, target, hold_spec, seconds):
    method, path = hold_spec.split(' ', 1)
    server = ThreadingHTTPServer(('127.0.0.1', port), make_handler(target, Hold(method.upper(), path, seconds)))
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--port', type=int, default=9210)
    parser.add_argument('--target', required=True, help='world origin, e.g. http://127.0.0.1:9310')
    parser.add_argument('--hold', required=True, help='"METHOD /path-prefix" whose first response is held')
    parser.add_argument('--hold-seconds', type=float, default=120.0)
    args = parser.parse_args()
    server = serve(args.port, args.target, args.hold, args.hold_seconds)
    print(f'crash-window proxy: http://127.0.0.1:{server.server_port} -> {args.target}, holding first {args.hold}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
