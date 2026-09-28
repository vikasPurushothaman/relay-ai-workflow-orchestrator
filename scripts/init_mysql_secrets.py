#!/usr/bin/env python3
"""Create private local MySQL password files once; never overwrite credentials."""
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]


def initialize(directory):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name in ('password', 'root-password'):
        target = directory / name
        try:
            fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            if target.is_symlink() or not target.is_file() or not target.read_bytes().strip():
                raise ValueError('Existing secret must be a nonempty regular file: ' + str(target))
            continue
        with os.fdopen(fd, 'w') as stream:
            stream.write(secrets.token_hex(32) + '\n')


if __name__ == '__main__':
    initialize(ROOT / 'local-data/mysql')
    print('MySQL secret files ready in local-data/mysql; existing values preserved. No passwords printed.')
