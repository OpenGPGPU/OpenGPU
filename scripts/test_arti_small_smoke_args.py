#!/usr/bin/env python3
"""Exercise small-smoke tool selection without booting a guest or building RTL."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from build_arti_native_compute_model import arti_config, check_guest_dtb, check_guest_memory, observed_wrapper

ROOT = Path(__file__).resolve().parents[1]

class SmallSmokeArguments(unittest.TestCase):
    def test_native_model_guards(self):
        memory = '\tmemory@40000000 {\n\t\treg = <0x00 0x40000000 0x00 0x80000000>;\n\t};\n'
        self.assertEqual(check_guest_memory(memory)['ram_bytes'], 2*1024**3)
        for bad in ('', memory+memory, memory.replace('0x80000000', '0x20000000'),
                    memory.replace('0x40000000 0x00', '0xc0000000 0x00'),
                    memory + '\tmemory@c0000000 {};\n'):
            with self.subTest(memory=bad), self.assertRaises(ValueError):
                check_guest_memory(bad)
        gpu = '\tarti-rtl@b000000 {\n\t\topengpu,width = <0x10>;\n\t\topengpu,height = <0x10>;\n\t\topengpu,stride = <0x40>;\n\t};\n'
        self.assertEqual(check_guest_dtb(memory+gpu)['ram_bytes'], 2*1024**3)
        for bad in (memory, memory+gpu.replace('0x10', '0x20')):
            with self.assertRaises(ValueError): check_guest_dtb(bad)
        config = arti_config([Path('/rtl/Other.sv'), Path('/rtl/GpuHostSystemAxi.sv')])
        self.assertIn('source_files: ["/rtl/GpuHostSystemAxi.sv", "/rtl/Other.sv"]', config)
        for files in ([], [Path('/rtl/GpuHostSystemAxi.sv'), Path('/rtl/comma,name.sv')]):
            with self.assertRaises(ValueError): arti_config(files)
        for invalid in ('', 'static void tick(void)\nstatic void tick(void)'):
            with self.assertRaises(ValueError): observed_wrapper(invalid)

    def test_native_guest_requires_explicit_profile(self):
        env = {k:v for k,v in os.environ.items() if k not in
               ('OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION', 'OPENGPU_NATIVE_LINUX_SHARED_QUALIFICATION', 'ARTI_GUEST_RAM_MIB',
                'ARTI_KERNEL_CMDLINE_EXTRA', 'ARTI_SMOKE_WIDTH', 'ARTI_SMOKE_HEIGHT')}
        for override, message in (({}, 'requires OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION=1'),
               ({'OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION':'1', 'ARTI_GUEST_RAM_MIB':'512'}, 'exactly 2048'),
               ({'OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION':'1', 'ARTI_SMOKE_WIDTH':'32'}, '16x16')):
            with self.subTest(override=override):
                result = subprocess.run(['bash', str(ROOT/'scripts/run_arti_small_smoke.sh'), 'uniform-counter-source'],
                                        env={**env, **override}, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertIn(message, result.stderr)
        for mode,value in (('uniform-counter-source','bad'),('image','1')):
            result = subprocess.run(['bash',str(ROOT/'scripts/run_arti_small_smoke.sh'),mode],
                env={**env,'OPENGPU_NATIVE_LINUX_SHARED_QUALIFICATION':value},capture_output=True,text=True)
            self.assertEqual(result.returncode,2)
            self.assertIn('OPENGPU_NATIVE_LINUX_SHARED_QUALIFICATION',result.stderr)

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
                path.write_text('''#!/usr/bin/env bash
QEMU_RC=$?
set -e
printf '%s\\n' "$DRIVER_MARKER" \\
    'ARTI Linux init: done, powering off' 'reboot: Power down' > "$WORK/serial.log"
''')
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
                                ("counter", "compute_counter.c"),
                                ("counter-resources", "opengpu_compute_counter_resources_gpu_smoke.c"),
                                ("storage", "opengpu_compute_storage_gpu_smoke.c"),
                                ("storage-fault", "opengpu_compute_storage_gpu_smoke.c"),
                                ("storage-dependency", "opengpu_compute_storage_dependency_gpu_smoke.c"),
                                ("resources", "compute_resources.c")):
                with self.subTest(mode=mode):
                    subprocess.run(["bash", str(ROOT/"scripts/run_arti_small_smoke.sh"), mode],
                                   env=env, check=True, stdout=subprocess.DEVNULL)
                    args = (root/"args").read_text().splitlines()
                    self.assertTrue(any(Path(arg).name == guest for arg in args))
                    self.assertEqual(str(ROOT/"userspace/opengpu.c") in args, mode in ("resources", "image-resources", "counter", "counter-resources"))

            # This remains an argv/guard test: all build/QEMU tools are explicit
            # fakes, and none of these markers is GPU execution evidence.
            native_dtb = root/'native.dtb'
            native_dtb.touch()
            dtc = root/'fake-dtc'
            dtc.write_text("#!/usr/bin/env python3\nprint('\\tmemory@40000000 {\\n\\t\\treg = <0 0x40000000 0 0x80000000>;\\n\\t};\\n\\tarti-rtl@b000000 {\\n\\t\\topengpu,width = <0x10>;\\n\\t\\topengpu,height = <0x10>;\\n\\t\\topengpu,stride = <0x40>;\\n\\t};')\n")
            dtc.chmod(0o755)
            harness_source = (harness/'run_linux_test.sh').read_text()
            (harness/'run_linux_test.sh').write_text(harness_source + '''
test "$ARTI_GUEST_RAM_MIB" = 2048
test "$ARTI_KERNEL_CMDLINE_EXTRA" = cma=768M@0x60000000
test "$ARTI_NATIVE_REQUIRE_TWO_CUS" = 1
printf '%s\\n' 'NATIVE_LINUX_RESOURCES epoch=1 PASS' 'NATIVE_LINUX_RESOURCES_FAULT epoch=1 PASS' \\
    'NATIVE_LINUX_RESOURCES epoch=3 PASS' >> "$WORK/serial.log"
echo 'ARTI_NATIVE_COMPUTE_OBSERVER actualProfile=true peakActiveCUs=2 status=PASS' >&2
''')
            native_env = {**env, 'OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION':'1',
                          'ARTI_SMOKE_DTB':str(native_dtb), 'DTC':str(dtc)}
            subprocess.run(['bash', str(ROOT/'scripts/run_arti_small_smoke.sh'), 'uniform-counter-source'],
                           env=native_env, check=True, stdout=subprocess.DEVNULL)
            args = (root/'args').read_text().splitlines()
            self.assertIn(str(ROOT/'driver/tests/opengpu_compute_uniform_counter_source_gpu_smoke.c'), args)
            self.assertNotIn('-DOPENGPU_TEST_SHARED_PROTECTION=1',args)
            shared_env={**native_env,'OPENGPU_NATIVE_LINUX_SHARED_QUALIFICATION':'1'}
            # Old full-resource markers alone cannot pass the new qualification.
            result=subprocess.run(['bash',str(ROOT/'scripts/run_arti_small_smoke.sh'),'uniform-counter-source'],
                env=shared_env,capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            (harness/'run_linux_test.sh').write_text((harness/'run_linux_test.sh').read_text()+'''
printf '%s\\n' 'NATIVE_LINUX_SHARED epoch=1 groups=2 items=128 sharedBytes=16384 sharedWordsPerGroup=4096 resultWords=18432 seed=a1100000 PASS' \\
 'NATIVE_LINUX_SHARED_FAULT epoch=1 intactSharedResults=true PASS' \\
 'NATIVE_LINUX_SHARED epoch=3 groups=2 items=128 sharedBytes=16384 sharedWordsPerGroup=4096 resultWords=18432 seed=a1300000 PASS' >> "$WORK/serial.log"
''')
            subprocess.run(['bash',str(ROOT/'scripts/run_arti_small_smoke.sh'),'uniform-counter-source'],
                env=shared_env,check=True,stdout=subprocess.DEVNULL)
            self.assertIn('-DOPENGPU_TEST_SHARED_PROTECTION=1',(root/'args').read_text().splitlines())
            indirect_env={**shared_env,'OPENGPU_NATIVE_LINUX_INDIRECT_QUALIFICATION':'1'}
            result=subprocess.run(['bash',str(ROOT/'scripts/run_arti_small_smoke.sh'),'uniform-counter-source'],
                env=indirect_env,capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            self.assertIn('explicit GCI1 opt-in DTB',result.stderr)
            original_dtc=dtc.read_text()
            self.assertEqual(original_dtc.count('arti-rtl@b000000 {\\n'),1)
            dtc.write_text(original_dtc.replace('arti-rtl@b000000 {\\n',
                'arti-rtl@b000000 {\\n\\t\\topengpu,gci1;\\n'))
            # Old resource/shared PASS lines cannot satisfy indirect execution.
            result=subprocess.run(['bash',str(ROOT/'scripts/run_arti_small_smoke.sh'),'uniform-counter-source'],
                env=indirect_env,capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            with (harness/'run_linux_test.sh').open('a') as stream:
                for epoch in (1,3):
                    for marker in (
                        f'NATIVE_LINUX_INDIRECT epoch={epoch} opcode=14 argsOffset=4092 counts=2,1,1 immutableSnapshot=true resultWords=18432 PASS',
                        f'NATIVE_LINUX_INDIRECT_ZERO epoch={epoch} opcode=14 counts=0,ffffffff,ffffffff intactResults=true intactCounters=true PASS',
                        f'NATIVE_LINUX_INDIRECT_BOUNDS epoch={epoch} counts=3,1,1 max=2,1,1 admission=ERANGE intactResults=true PASS'):
                        stream.write(f"printf '%s\\n' '{marker}' >> \"$WORK/serial.log\"\n")
                stream.write("printf '%s\\n' 'NATIVE_LINUX_INDIRECT_FAULT epoch=1 opcode=14 intactResults=true intactCounters=true PASS' >> \"$WORK/serial.log\"\n")
            subprocess.run(['bash',str(ROOT/'scripts/run_arti_small_smoke.sh'),'uniform-counter-source'],
                env=indirect_env,check=True,stdout=subprocess.DEVNULL)
            self.assertIn('-DOPENGPU_TEST_INDIRECT_RESOURCES=1',(root/'args').read_text().splitlines())
            dtc.write_text(original_dtc)
            for invalid in ({**native_env,'OPENGPU_NATIVE_LINUX_INDIRECT_QUALIFICATION':'1'},
                            {**shared_env,'OPENGPU_NATIVE_LINUX_INDIRECT_QUALIFICATION':'2'}):
                result=subprocess.run(['bash',str(ROOT/'scripts/run_arti_small_smoke.sh'),'uniform-counter-source'],
                    env=invalid,capture_output=True,text=True)
                self.assertEqual(result.returncode,2)
            # A serial PASS without the actual two-CU observer is insufficient.
            (harness/'run_linux_test.sh').write_text((harness/'run_linux_test.sh').read_text().replace(
                "echo 'ARTI_NATIVE_COMPUTE_OBSERVER actualProfile=true peakActiveCUs=2 status=PASS' >&2", 'true'))
            result = subprocess.run(['bash', str(ROOT/'scripts/run_arti_small_smoke.sh'), 'uniform-counter-source'],
                                    env=native_env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            (harness/'run_linux_test.sh').write_text(harness_source)

            # A private DTB is added only to the copied harness's QEMU call.
            dtb = root / "small model.dtb"
            dtb.touch()
            qemu = root / "qemu"
            qemu.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$SMOKE_QEMU_LOG"\n')
            qemu.chmod(0o755)
            (harness / "run_linux_test.sh").write_text('''#!/usr/bin/env bash
"$QEMU" --smoke-probe
QEMU_RC=$?
set -e
printf '%s\\n' "$DRIVER_MARKER" \\
    'ARTI Linux init: done, powering off' 'reboot: Power down' > "$WORK/serial.log"
''')
            env.update({"ARTI_SMOKE_DTB":str(dtb), "SMOKE_QEMU_LOG":str(root/"qemu-args")})
            subprocess.run(["bash", str(ROOT/"scripts/run_arti_small_smoke.sh"), "counter"],
                           env=env, check=True, stdout=subprocess.DEVNULL)
            self.assertEqual((root/"qemu-args").read_text().splitlines(),
                             ["--smoke-probe", "-dtb", str(dtb)])

            # A guest marker must not turn a failed QEMU exit into a pass.
            (harness / "run_linux_test.sh").write_text('''#!/usr/bin/env bash
false
QEMU_RC=$?
set -e
printf '%s\\n' "$DRIVER_MARKER" \\
    'ARTI Linux init: done, powering off' 'reboot: Power down' > "$WORK/serial.log"
''')
            result = subprocess.run(["bash", str(ROOT/"scripts/run_arti_small_smoke.sh"), "counter"],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("QEMU did not exit cleanly", result.stdout + result.stderr)

            env["ARTI_SMOKE_DTB"] = str(root/"missing.dtb")
            result = subprocess.run(["bash", str(ROOT/"scripts/run_arti_small_smoke.sh"), "counter"],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("missing guest DTB", result.stderr)

if __name__ == "__main__":
    unittest.main()
