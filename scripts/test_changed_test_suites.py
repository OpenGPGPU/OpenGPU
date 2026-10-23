#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest

from changed_test_suites import changed_suites


class ChangedTestSuitesTest(unittest.TestCase):
    def test_fixtures_inheritance_and_matching_production_spec(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = root / "src/test/scala/opengpu"
            sources.mkdir(parents=True)
            for name, source in {
                "Support.scala": "trait Support",
                "BaseSpec.scala": "abstract class BaseSpec extends AnyFlatSpec with Support",
                "Profiles.scala": "class FirstSpec extends BaseSpec\nfinal class SecondSpec extends FirstSpec\nclass OtherSpec extends AnyFlatSpec",
                "AluSpec.scala": "class AluSpec extends AnyFlatSpec",
                "UnrelatedSpec.scala": "// Support\nclass UnrelatedSpec extends AnyFlatSpec",
            }.items():
                (sources / name).write_text("package opengpu\n" + source)
            expected = ["opengpu.FirstSpec", "opengpu.SecondSpec"]
            for name in ("Support.scala", "BaseSpec.scala"):
                self.assertEqual(changed_suites(root, ["src/test/scala/opengpu/" + name]), expected)
            self.assertEqual(changed_suites(root, ["src/test/scala/opengpu/Profiles.scala"]),
                             ["opengpu.FirstSpec", "opengpu.OtherSpec", "opengpu.SecondSpec"])
            self.assertEqual(changed_suites(root, ["src/main/scala/opengpu/Alu.scala"]), ["opengpu.AluSpec"])
            for paths in ([], ["docs/foo.md"], ["src/test/scala/opengpu/DeletedSpec.scala"]):
                self.assertEqual(changed_suites(root, paths), [])

    def test_recent_prs_cannot_fall_back_to_only_smoke_tests(self):
        root = Path(__file__).resolve().parents[1]
        self.assertIn("opengpu.testutil.GpuSimSpec",
                      changed_suites(root, [".github/workflows/scala_test.yml"]))
        for filename, required in (
            ("Fp32FragmentInterpolatorSpec.scala", {"Fp32FragmentInterpolatorSpec"}),
            ("NativeDefaultProfileHostAxiSpec.scala", {"NativeDefaultProfileHostAxiSpec", "NativeDefaultStructuredSimtHostAxiSpec", "NativeFullVaryingInterpolationHostAxiSpec"}),
            ("NativeTypedGraphicsHostAxiSpec.scala", {"NativeTypedGraphicsHostAxiSpec", "NativeUniformRendererFourCuHostAxiSpec"}),
        ):
            with self.subTest(filename=filename):
                actual = changed_suites(root, ["src/test/scala/opengpu/graphics/" + filename])
                self.assertTrue(required <= {name.rsplit(".", 1)[1] for name in actual}, actual)


if __name__ == "__main__":
    unittest.main()
