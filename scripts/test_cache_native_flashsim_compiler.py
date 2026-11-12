"""Exercise auditable FlashSim shard reuse with real C++ compilation."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from cache_native_flashsim_compiler import UNRECORDED_ENV


HERE = Path(__file__).resolve().parent
BUILDER = HERE / "build_native_flashsim.py"
ADAPTER = HERE / "cache_native_flashsim_compiler.py"
CXX_ADAPTER = HERE / "cache_native_flashsim_cxx"
CXX = shutil.which("c++")
CLANG = bool(CXX and "clang" in subprocess.check_output([CXX, "--version"], text=True).lower())


class Fixture:
    def __init__(self, root, *, pch=False, path_sensitive=False, header_extra=""):
        self.root = root
        self.old = root / "old dut with spaces"
        self.new = root / "new dut with spaces"
        self.old_build = root / "old build"
        self.build = root / "new build"
        self.plan = root / "cache plan.json"
        self.events = root / "cache events"
        self.witness = root / "witness.json"
        self.driver = root / "driver.cpp"
        self.pch = pch
        # The macOS /usr/bin/python3 launcher adds LIBRARY_PATH. Keep positive
        # tests independent of unrecorded ambient compiler overrides.
        self.env = {key: value for key, value in os.environ.items() if key not in UNRECORDED_ENV}
        header = "#pragma once\n" + header_extra + "int value(); int commit_value();\n"
        main = '#include "dut.h"\nint value() { return 40; }\n'
        commit = ('#include "dut.h"\nint commit_value() { return ' +
                  ('__FILE__[0] ? 2 : 1' if path_sensitive else '2') + '; }\n')
        for dut in (self.old, self.new):
            dut.mkdir()
            (dut / "dut.h").write_text(header)
            (dut / "dut_0.cpp").write_text(main)
            (dut / "dut_commit_part000.cpp").write_text(commit)
        self.driver.write_text('#include "dut.h"\nint main() { return value()+commit_value()==42 ? 0 : 1; }\n')
        self.run_builder(self.old, self.old_build)

    def run(self, command, *, env=None, check=True):
        result = subprocess.run(command, env=env if env is not None else self.env,
                                capture_output=True, text=True)
        if check and result.returncode:
            raise AssertionError(f"command failed: {command!r}\nstdout: {result.stdout}\nstderr: {result.stderr}")
        return result

    def run_builder(self, dut, output, *, cached=False, options=(), check=True):
        command = [sys.executable, str(BUILDER), "--dut-dir", str(dut),
                   "--build-dir", str(output), "--driver", str(self.driver), "--jobs", "2",
                   "--conservative-alias", *options]
        if self.pch:
            command += ["--precompiled-header"]
        env = self.env.copy()
        if cached:
            command += ["--cxx", str(CXX_ADAPTER)]
            env["OPENGPU_NATIVE_CACHE_PLAN"] = str(self.plan)
        else:
            command += ["--cxx", CXX]
        return self.run(command, env=env, check=check)

    def prepare(self, *, env=None, check=True):
        return self.run([sys.executable, str(ADAPTER), "prepare", "--dut-dir", str(self.new),
                         "--build-dir", str(self.build), "--cache-build", str(self.old_build),
                         "--plan", str(self.plan), "--events-dir", str(self.events)], env=env, check=check)

    def verify(self, *, check=True, witness=None):
        return self.run([sys.executable, str(ADAPTER), "verify", "--plan", str(self.plan),
                         "--witness", str(witness or self.witness)], check=check)

    def events_by_name(self):
        return {f.stem: json.loads(f.read_text()) for f in self.events.glob("*.json")}


@unittest.skipUnless(CXX, "C++ compiler unavailable")
class NativeCompilerCacheTest(unittest.TestCase):
    def fixture(self, **kwargs):
        tmp = tempfile.TemporaryDirectory(prefix="flashsim cache test ")
        self.addCleanup(tmp.cleanup)
        return Fixture(Path(tmp.name), **kwargs)

    def check_reuse_and_audit(self, pch):
        f = self.fixture(pch=pch)
        (f.new / "dut_0.cpp").write_text('#include "dut.h"\nint value() { return 41; }\n')
        f.driver.write_text('#include "dut.h"\nint main() { return value()+commit_value()==43 ? 0 : 1; }\n')
        f.prepare()
        self.assertEqual(set(json.loads(f.plan.read_text())["candidates"]), {"dut_commit_part000.cpp"})
        f.run_builder(f.new, f.build, cached=True)
        subprocess.run([str(f.build / "native_product_driver")], check=True)
        self.assertEqual({name: event["mode"] for name, event in f.events_by_name().items()},
                         {"dut_0": "compiled", "dut_commit_part000": "reused"})
        f.verify()
        witness = json.loads(f.witness.read_text())
        self.assertEqual((witness["status"], witness["reused"], witness["compiled"]), ("pass", 1, 1))
        event_path = f.events / "dut_commit_part000.json"
        event = json.loads(event_path.read_text())
        event["arguments"][-4] = "-O3"
        event_path.write_text(json.dumps(event))
        rejected = f.verify(check=False, witness=f.root / "altered-witness.json")
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("built object or event differs", rejected.stderr)

    def test_mixed_reuse_and_audit(self):
        self.check_reuse_and_audit(False)

    @unittest.skipUnless(CLANG, "Clang required for PCH")
    def test_mixed_reuse_with_precompiled_header(self):
        self.check_reuse_and_audit(True)

    def test_path_sensitive_and_mismatched_flags_compile_normally(self):
        f = self.fixture(path_sensitive=True)
        f.prepare()
        self.assertEqual(set(json.loads(f.plan.read_text())["candidates"]), {"dut_0.cpp"})
        f.run_builder(f.new, f.build, cached=True, options=("--shard-opt=-O0",))
        self.assertTrue(all(event["mode"] == "compiled" for event in f.events_by_name().values()))
        f.verify()
        subprocess.run([str(f.build / "native_product_driver")], check=True)

    def test_mutated_cached_object_is_rejected(self):
        f = self.fixture()
        f.prepare()
        with (f.old_build / "dut_0.o").open("ab") as stream:
            stream.write(b"modified")
        result = f.run_builder(f.new, f.build, cached=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cache input changed before reuse", (f.build / "dut_0.log").read_text())
        self.assertFalse(f.witness.exists())

    def test_unsafe_header_and_unrecorded_environment_are_rejected(self):
        f = self.fixture(header_extra="#define UNTRACKED 1\n")
        result = f.prepare(check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unsupported path-dependent syntax", result.stderr)
        self.assertFalse(f.plan.exists())
        self.assertFalse(f.events.exists())
        g = self.fixture()
        env = dict(g.env, SOURCE_DATE_EPOCH="1")
        result = g.prepare(env=env, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unrecorded compiler override", result.stderr)


if __name__ == "__main__":
    unittest.main()
