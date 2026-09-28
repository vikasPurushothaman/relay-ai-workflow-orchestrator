#!/usr/bin/env python3
"""Run unchanged Airtribe mock handlers on loopback after verifying pinned sources."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from http.server import ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / 'docs/source-review/pack'


def verify_pack(pack=PACK):
    manifest = json.loads((ROOT / 'docs/source-review/pack-provenance.json').read_text())
    for name, digest in manifest['files'].items():
        if hashlib.sha256((pack / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Pinned pack integrity check failed: ' + name)


def load_mock(role):
    verify_pack()
    name = 'mock_world' if role == 'world' else 'mock_provider'
    spec = importlib.util.spec_from_file_location(name, PACK / 'scripts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def port_value(value):
    try:
        number = int(value)
        if not 0 <= number <= 65535:
            raise ValueError()
        return number
    except ValueError:
        raise argparse.ArgumentTypeError('port must be 0..65535 (0 selects a free test port)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('role', choices=['world', 'provider'])
    parser.add_argument('--port', type=port_value)
    args = parser.parse_args()
    module = load_mock(args.role)
    port = args.port if args.port is not None else (9210 if args.role == 'world' else 9001)
    with ThreadingHTTPServer(('127.0.0.1', port), module.Handler) as server:
        if args.role == 'provider':
            server.provider_name = 'alpha'
            server.api_key = None  # supplied mock accepts any nonempty bearer token
        print(f'Relay mock {args.role}: http://127.0.0.1:{server.server_port}', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
