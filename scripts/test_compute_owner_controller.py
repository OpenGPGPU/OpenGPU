#!/usr/bin/env python3
"""CPU-only active-owner liveness and response-file refusal boundaries."""
from pathlib import Path
import tempfile
import unittest
from compute_owner_controller import atomic, wait_file

ROOT = Path(__file__).resolve().parents[1]


class Process:
    def __init__(self, code):
        self.code = code

    def poll(self):
        return self.code


class Controller(unittest.TestCase):
    def test_ready_does_not_override_a_dead_owner_and_responses_do_not_overwrite(self):
        parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compute-controller-', dir=parent) as temporary:
            root = Path(temporary)
            ready = root / 'READY'; ready.write_bytes(b'CPU capture only')
            with self.assertRaisesRegex(ValueError, 'producer exited'):
                wait_file(ready, Process(0), None, 1)
            missing = root / 'held.json'
            with self.assertRaisesRegex(ValueError, 'executor exited'):
                wait_file(missing, Process(None), Process(0), 1)
            ready.unlink(); ready.symlink_to(missing.name)
            missing.write_bytes(b'held')
            with self.assertRaisesRegex(ValueError, 'invalid IPC'):
                wait_file(ready, Process(None), Process(None), 1)
            response = root / 'BEGIN_CLEANUP'
            atomic(response, b'first authorized response')
            with self.assertRaisesRegex(ValueError, 'pre-existing response'):
                atomic(response, b'changed response')
            self.assertEqual(response.read_bytes(), b'first authorized response')


if __name__ == '__main__':
    unittest.main()
