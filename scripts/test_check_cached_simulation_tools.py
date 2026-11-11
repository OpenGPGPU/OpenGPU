#!/usr/bin/env python3
"""Exercise the cache check with absent, broken and real toolchains."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


CHECK = Path(__file__).with_name("check_cached_simulation_tools.sh")
TOOLS = ("git", "perl", "python3", "make", "g++", "ccache", "curl")


class CachedSimulationToolTests(unittest.TestCase):
    def run_check(self, prefix):
        return subprocess.run(
            ["bash", str(CHECK), str(prefix)], text=True, capture_output=True
        )

    def make_verilator(self, prefix, text):
        binary = Path(prefix) / "bin" / "verilator"
        binary.parent.mkdir()
        binary.write_text("#!/bin/sh\n" + text + "\n")
        binary.chmod(0o755)

    def test_missing_and_wrong_version_are_rejected(self):
        with tempfile.TemporaryDirectory() as prefix:
            result = self.run_check(prefix)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("missing cached Verilator", result.stderr)
            self.make_verilator(prefix, "echo 'Verilator 5.0500'")
            result = self.run_check(prefix)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("unexpected cached Verilator version", result.stderr)

    @unittest.skipUnless(all(shutil.which(tool) for tool in TOOLS), "build tools unavailable")
    def test_matching_version_cannot_mask_a_broken_compiler(self):
        with tempfile.TemporaryDirectory() as prefix:
            self.make_verilator(
                prefix,
                'if [ "$1" = --version ]; then echo "Verilator 5.050"; exit; fi\n'
                'echo "fake compiler failed" >&2; exit 1',
            )
            result = self.run_check(prefix)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("fake compiler failed", result.stderr)

    @unittest.skipUnless(all(shutil.which(tool) for tool in TOOLS), "build tools unavailable")
    def test_installed_pinned_toolchain_can_build_and_run_a_model(self):
        binary = shutil.which("verilator")
        if not binary:
            self.skipTest("Verilator unavailable")
        version = subprocess.check_output([binary, "--version"], text=True).strip()
        if version != "Verilator 5.050" and not version.startswith("Verilator 5.050 "):
            self.skipTest("pinned Verilator unavailable")
        result = self.run_check(Path(binary).parent.parent)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
