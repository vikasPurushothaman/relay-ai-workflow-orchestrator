#!/usr/bin/env python3
"""Write an ignored, owner-readable provider configuration without echoing the key."""
import getpass
import argparse
import os
from pathlib import Path
import re
import shlex

ROOT = Path(__file__).resolve().parents[1]

def main(provider='openai'):
    target = ROOT / 'backend/.env.ai'
    if target.exists():
        raise SystemExit('backend/.env.ai already exists; edit that local file instead of overwriting it.')
    if provider not in {'openai', 'openrouter'}:
        raise SystemExit('Invalid provider; nothing written.')
    model = input(f'{provider} model ID available to your account: ').strip()
    key = getpass.getpass(f'{provider} API key (hidden): ').strip()
    if not model or len(model) > 200 or not re.fullmatch(r'[A-Za-z0-9_.:/-]+', model):
        raise SystemExit('Invalid model ID; nothing written.')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', key):
        raise SystemExit('Invalid API key format; nothing written.')
    if (provider == 'openrouter' and not key.startswith('sk-or-v1-')) or (provider == 'openai' and key.startswith('sk-or-v1-')):
        raise SystemExit('Key does not match the selected provider; nothing written.')
    if provider == 'openrouter' and not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.:/-]+', model):
        raise SystemExit('OpenRouter requires a prefixed model, such as openai/gpt-5-mini; nothing written.')
    content = '\n'.join(f'{k}={shlex.quote(v)}' for k, v in {
        'RELAY_AI_MODE': provider, 'RELAY_AI_MODEL': model, 'RELAY_AI_API_KEY': key}.items()) + '\n'
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(content)
    print('Saved backend/.env.ai with owner-only permissions. No key printed.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provider', choices=['openai', 'openrouter'], default='openai')
    main(parser.parse_args().provider)
