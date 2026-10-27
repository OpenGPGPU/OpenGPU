#!/usr/bin/env python3
"""Exercise adapter replacement with a real C++/ar link, without building RTL."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from build_arti_idle_adapter import build, digest, members


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.model = self.root / "model"
        self.rtl = self.model / "verilated"
        self.rtl.mkdir(parents=True)
        self.compiler = shutil.which("c++")
        self.assertIsNotNone(self.compiler, "host C++ compiler required")
        self.assertIsNotNone(shutil.which("ar"), "host ar required")
        (self.rtl / "VGpuFixture.h").write_text("// No RTL is compiled by this fixture.\n")
        (self.model / "arti_rtl_model.h").write_text('extern "C" int idle_grace();\n')
        self.source = self.model / "arti_rtl_model.cpp"
        self.source.write_text('''#include "VGpuFixture.h"
#ifndef ARTI_MODEL_MMIO_ADVANCE_CYCLES
#define ARTI_MODEL_MMIO_ADVANCE_CYCLES 500000
#ifndef ARTI_MODEL_IDLE_GRACE
#define ARTI_MODEL_IDLE_GRACE 20000
#endif
#endif
extern "C" int idle_grace() { return ARTI_MODEL_IDLE_GRACE; }
''')
        dut = self.root / "generated_dut_with_a_long_archive_name.cpp"
        dut.write_text('extern "C" int sentinel() { return 77; }\n')
        self.objects = [self.rtl / "arti_rtl_model.o", self.rtl / (dut.stem + ".o")]
        for source, obj in zip([self.source, dut], self.objects):
            subprocess.run([self.compiler, "-I" + str(self.rtl), "-c", str(source), "-o", str(obj)], check=True)
        self.archive = self.rtl / "libarti_rtl_model.a"
        subprocess.run(["ar", "rcs", str(self.archive), *map(str, self.objects)], check=True)

    def execute(self, archive, name):
        source = self.root / (name + ".cpp")
        source.write_text('''#include <cstdio>
extern "C" int idle_grace();
extern "C" int sentinel();
int main() { std::printf("%d %d", idle_grace(), sentinel()); }
''')
        binary = self.root / name
        subprocess.run([self.compiler, str(source), str(archive), "-o", str(binary)], check=True)
        return subprocess.check_output([str(binary)], text=True)

    def test_real_archive_link_preserves_dut_and_original(self):
        original = digest(self.archive)
        result = build(self.model, self.root / "candidate", 64, self.compiler, self.rtl)
        self.assertEqual(self.execute(self.archive, "original"), "20000 77")
        self.assertEqual(self.execute(result["archive"], "candidate_probe"), "64 77")
        self.assertEqual(digest(self.archive), original)
        self.assertEqual(result["changed_members"], ["arti_rtl_model.o"])
        self.assertEqual(result["unchanged_DUT_and_runtime_members"], 1)
        self.assertFalse(result["simulation_qualified"])
        old, new = members(self.archive), members(result["archive"])
        self.assertEqual(old[self.objects[1].name], new[self.objects[1].name])

    def test_rejects_invalid_batches_and_unknown_adapter_before_creating_output(self):
        for cycles in (0, -1, 20001):
            output = self.root / ("invalid" + str(cycles))
            with self.assertRaises(ValueError):
                build(self.model, output, cycles, self.compiler, self.rtl)
            self.assertFalse(output.exists())
        self.source.write_text(self.source.read_text().replace("#ifndef ARTI_MODEL_IDLE_GRACE", "#if 1"))
        output = self.root / "unknown_adapter"
        with self.assertRaises(ValueError):
            build(self.model, output, 64, self.compiler, self.rtl)
        self.assertFalse(output.exists())

    def test_preserves_existing_output(self):
        output = self.root / "existing"
        output.mkdir()
        evidence = output / "previous-result"
        evidence.write_text("keep")
        with self.assertRaises(FileExistsError):
            build(self.model, output, 64, self.compiler, self.rtl)
        self.assertEqual(evidence.read_text(), "keep")


if __name__ == "__main__":
    unittest.main()
