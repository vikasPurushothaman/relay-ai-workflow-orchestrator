#!/usr/bin/env python3
"""Configuration helper regression tests using only fake credentials and temporary files."""
import contextlib
import io
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import configure_ai
import verify_live_ai

class ConfigurationTest(unittest.TestCase):
    def test_openrouter_configuration_roundtrip_and_mismatches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'backend').mkdir()
            target = root / 'backend/.env.ai'
            with patch.object(configure_ai, 'ROOT', root), patch('builtins.input', return_value='openai/gpt-5-mini'), patch('getpass.getpass', return_value='sk-or-v1-test-secret'), contextlib.redirect_stdout(io.StringIO()):
                configure_ai.main('openrouter')
            values = verify_live_ai.configuration(target)
            self.assertEqual(values['RELAY_AI_MODE'], 'openrouter')
            self.assertEqual(values['RELAY_AI_MODEL'], 'openai/gpt-5-mini')
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
            original = target.read_text()
            for bad in [original.replace('openrouter', 'openai'), original.replace('sk-or-v1-test-secret', 'sk-direct-key'), original.replace('openai/gpt-5-mini', 'gpt-5-mini')]:
                target.write_text(bad)
                with self.assertRaises(ValueError):
                    verify_live_ai.configuration(target)

    def test_private_round_trip_and_no_overwrite_or_secret_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'backend').mkdir()
            output = io.StringIO()
            with patch.object(configure_ai, 'ROOT', root), patch('builtins.input', return_value='test-model'), patch('getpass.getpass', return_value='fake-secret'), contextlib.redirect_stdout(output):
                configure_ai.main()
                with self.assertRaises(SystemExit):
                    configure_ai.main()
            target = root / 'backend/.env.ai'
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
            self.assertEqual(verify_live_ai.configuration(target)['RELAY_AI_API_KEY'], 'fake-secret')
            self.assertNotIn('fake-secret', output.getvalue())

    def test_invalid_input_writes_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'backend').mkdir()
            for model, key in [('bad model', 'fake-key'), ('test-model', 'key;command'), ('', 'fake-key')]:
                with patch.object(configure_ai, 'ROOT', root), patch('builtins.input', return_value=model), patch('getpass.getpass', return_value=key), self.assertRaises(SystemExit):
                    configure_ai.main()
                self.assertFalse((root / 'backend/.env.ai').exists())

    def test_loader_rejects_extra_duplicate_missing_and_malformed_values(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'config'
            for text in ['OTHER=x', 'RELAY_AI_MODE=openai\nRELAY_AI_MODE=openai', 'RELAY_AI_API_KEY=', "RELAY_AI_API_KEY='"]:
                target.write_text(text)
                with self.assertRaises(ValueError):
                    verify_live_ai.configuration(target)

if __name__ == '__main__':
    unittest.main()
