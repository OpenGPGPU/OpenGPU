#!/usr/bin/env python3
"""Exercise small-smoke tool selection without booting a guest or building RTL."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class SmallSmokeArguments(unittest.TestCase):
    def test_invalid_draw_timeout_is_rejected_before_build(self):
        for value in ("0", "900001", "-1", "bad"):
            with self.subTest(value=value):
                env = {**os.environ, "ARTI_SMOKE_DRAW_WAIT_MS": value}
                result = subprocess.run(["bash", str(ROOT/"scripts/run_arti_small_smoke.sh"), "image-resources"],
                                        env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertIn("smoke draw timeout", result.stderr)

    def test_modes_compile_the_expected_guest(self):
        with tempfile.TemporaryDirectory(prefix="opengpu-smoke-args-") as directory:
            root = Path(directory)
            for name in ("qemu", "linux/Makefile", "build/arch/arm64/boot/Image",
                         "headers/include/drm/drm.h", "config.yaml"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            harness = root / "arti/examples/linux_arti_driver"
            harness.mkdir(parents=True)
            for name in ("run_linux_test.sh", "integration_env.sh", "driver_preflight.sh"):
                path = harness / name
                path.write_text("#!/usr/bin/env bash\nexit 0\n")
                path.chmod(0o755)
            compiler = root / "fake-gcc"
            compiler.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$SMOKE_ARG_LOG"\n')
            noop = root / "noop"
            noop.write_text("#!/usr/bin/env bash\nexit 0\n")
            compiler.chmod(0o755)
            noop.chmod(0o755)
            env = os.environ.copy()
            env.update({"ARTI_DIR":str(root/"arti"), "ARTI_WORK":str(root),
                        "LINUX_SOURCE":str(root/"linux"), "LINUX_BUILD":str(root/"build"),
                        "LINUX_HEADERS":str(root/"headers"), "INTEGRATION_CONFIG":str(root/"config.yaml"),
                        "QEMU":str(root/"qemu"), "CROSS_COMPILE":str(root/"fake-"),
                        "MAKE":str(noop), "LLVM_MC":str(noop), "LLVM_OBJCOPY":str(noop),
                        "ARTI_SMOKE_ARTIFACTS":str(root/"runs"), "SMOKE_ARG_LOG":str(root/"args")})
            for mode, guest in (("texture", "opengpu_compute_texture_gpu_smoke.c"),
                                ("image", "opengpu_compute_image_gpu_smoke.c"),
                                ("image-alias", "opengpu_compute_image_alias_gpu_smoke.c"),
                                ("image-texture", "opengpu_compute_image_texture_gpu_smoke.c"),
                                ("image-texture-alias", "opengpu_compute_image_texture_alias_gpu_smoke.c"),
                                ("image-resources", "opengpu_compute_image_resources_gpu_smoke.c"),
                                ("image-resource-ranges", "opengpu_compute_image_resource_ranges_gpu_smoke.c"),
                                ("image-storage-alias", "opengpu_compute_image_storage_alias_gpu_smoke.c"),
                                ("image-storage-ranges", "opengpu_compute_image_storage_ranges_gpu_smoke.c"),
                                ("multiview-object", "opengpu_compute_multiview_object_gpu_smoke.c"),
                                ("image-atomic", "opengpu_compute_image_atomic_gpu_smoke.c"),
                                ("storage-atomic", "opengpu_compute_storage_atomic_gpu_smoke.c"),
                                ("image-atomic-fence", "opengpu_compute_image_atomic_fence_gpu_smoke.c"),
                                ("image-barrier", "opengpu_compute_image_barrier_gpu_smoke.c"),
                                ("compute-fence", "opengpu_compute_fence_gpu_smoke.c"),
                                ("storage", "opengpu_compute_storage_gpu_smoke.c"),
                                ("storage-fault", "opengpu_compute_storage_gpu_smoke.c"),
                                ("storage-dependency", "opengpu_compute_storage_dependency_gpu_smoke.c"),
                                ("resources", "compute_resources.c")):
                with self.subTest(mode=mode):
                    subprocess.run(["bash", str(ROOT/"scripts/run_arti_small_smoke.sh"), mode],
                                   env=env, check=True, stdout=subprocess.DEVNULL)
                    args = (root/"args").read_text().splitlines()
                    self.assertTrue(any(Path(arg).name == guest for arg in args))
                    self.assertEqual(str(ROOT/"userspace/opengpu.c") in args, mode in ("resources", "image-resources"))

if __name__ == "__main__":
    unittest.main()
