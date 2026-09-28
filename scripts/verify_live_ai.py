#!/usr/bin/env python3
"""Load local provider credentials without shell evaluation and run the explicit live probe."""
import os
from pathlib import Path
import shlex
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def configuration(path):
    allowed = {'RELAY_AI_MODE', 'RELAY_AI_MODEL', 'RELAY_AI_API_KEY'}
    values = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        name, separator, value = line.partition('=')
        if separator != '=' or name not in allowed or name in values:
            raise ValueError('Invalid local configuration field')
        parts = shlex.split(value)
        if len(parts) != 1 or not parts[0]:
            raise ValueError('Missing local configuration value')
        values[name] = parts[0]
    if set(values) != allowed or values['RELAY_AI_MODE'] not in {'openai', 'openrouter'}:
        raise ValueError('Configure the real provider key and model first')
    mode, model, key = (values[k] for k in ('RELAY_AI_MODE', 'RELAY_AI_MODEL', 'RELAY_AI_API_KEY'))
    if not re.fullmatch(r'[A-Za-z0-9_-]+', key) or len(model) > 200 or not re.fullmatch(r'[A-Za-z0-9_.:/-]+', model):
        raise ValueError('Invalid key or model format')
    if (mode == 'openrouter' and not key.startswith('sk-or-v1-')) or (mode == 'openai' and key.startswith('sk-or-v1-')):
        raise ValueError('Provider and key do not match')
    if mode == 'openrouter' and not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.:/-]+', model):
        raise ValueError('OpenRouter model needs a provider prefix')
    return values

def main():
    try:
        values = configuration(ROOT / 'backend/.env.ai')
    except (OSError, ValueError):
        raise SystemExit('Local AI configuration is missing or invalid. Run python3 scripts/configure_ai.py; never paste a key into chat.')
    env = {k: v for k, v in os.environ.items() if not k.startswith(('RELAY_', 'SPRING_', 'SERVER_', 'MANAGEMENT_'))}
    env.update(values)
    result = subprocess.run(['./gradlew', '--gradle-user-home', '.gradle/user-home', '--no-daemon', '--offline', 'liveAiTest'], cwd=ROOT / 'backend', env=env)
    raise SystemExit(result.returncode)

if __name__ == '__main__':
    main()
