"""Exercise the native FlashSim builder with tiny C++ shards."""
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("build_native_flashsim.py")


def check_builder_reuses_only_matching_objects(tmp_path):
    if not shutil.which("c++"):
        raise unittest.SkipTest("C++ compiler unavailable")
    dut, build = tmp_path / "DUT with spaces", tmp_path / "build with spaces"
    dut.mkdir()
    header = dut / "dut.h"
    header.write_text("int value(); int commit_value();\n")
    shard = dut / "dut_0.cpp"
    shard.write_text('#include "dut.h"\nint value() { return 40; }\n')
    (dut / "dut_commit_part000.cpp").write_text('int commit_value() { return 2; }\n')
    driver = tmp_path / "driver.cpp"
    driver.write_text('#include "dut.h"\n#include <cstdio>\nint main(){std::printf("%d\\n",value()+commit_value());}\n')
    command = [sys.executable, str(SCRIPT), "--dut-dir", str(dut), "--build-dir", str(build),
               "--driver", str(driver), "--jobs", "2", "--conservative-alias"]

    def run(extra=()):
        return subprocess.run([*command, *extra], check=True, capture_output=True, text=True).stdout

    first = run()
    assert "dut_0.cpp: compiled" in first and "dut_commit_part000.cpp: compiled" in first
    assert subprocess.check_output([str(build / "native_product_driver")], text=True).strip() == "42"
    manifest = json.loads((build / "native-flashsim-build.json").read_text())
    assert manifest["dut_sources"]["dut_0.cpp"] == hashlib.sha256(shard.read_bytes()).hexdigest()
    commit = json.loads((build / "dut_commit_part000.json").read_text())
    assert commit["context"]["optimization"] == "-O0"
    second = run()
    assert "dut_0.cpp: cached" in second and "dut_commit_part000.cpp: cached" in second

    shard.write_text('#include "dut.h"\nint value() { return 41; }\n')
    third = run()
    assert "dut_0.cpp: compiled" in third and "dut_commit_part000.cpp: cached" in third
    assert subprocess.check_output([str(build / "native_product_driver")], text=True).strip() == "43"
    changed_opt = run(("--commit-opt=-O1",))
    assert "dut_0.cpp: cached" in changed_opt and "dut_commit_part000.cpp: compiled" in changed_opt
    commit = json.loads((build / "dut_commit_part000.json").read_text())
    assert commit["context"]["optimization"] == "-O1"
    assert "-fno-strict-aliasing" in commit["context"]["flags"]

    header.write_text(header.read_text() + "// changed\n")
    changed_header = run(("--commit-opt=-O1",))
    assert "dut_0.cpp: compiled" in changed_header and "dut_commit_part000.cpp: compiled" in changed_header
    shard.write_text("invalid C++ source\n")
    failed = subprocess.run(command, capture_output=True, text=True)
    assert failed.returncode != 0 and "compile failed" in failed.stderr
    assert (build / "dut_0.log").is_file()
    shard.write_text('#include "untracked.h"\nint value() { return 41; }\n')
    (dut / "untracked.h").write_text("// local dependency\n")
    failed = subprocess.run(command, capture_output=True, text=True)
    assert failed.returncode != 0 and "unsupported generated include" in failed.stderr
    shard.write_bytes(b" " * (1024 * 1024 - 5) + b'#include "untracked.h"\n')
    failed = subprocess.run(command, capture_output=True, text=True)
    assert failed.returncode != 0 and "unsupported generated include" in failed.stderr
    shard.write_text('#include "dut.h"\nint value() { return 41; }\n')
    (tmp_path / "untracked.h").write_text("// local dependency\n")
    driver.write_text('#include "untracked.h"\nint main() { return 0; }\n')
    failed = subprocess.run(command, capture_output=True, text=True)
    assert failed.returncode != 0 and "unsupported or shadowed driver include" in failed.stderr
    driver.write_text('#include "dut.h"\nint main() { return 0; }\n')
    (tmp_path / "dut.h").write_text("// shadow header\n")
    failed = subprocess.run(command, capture_output=True, text=True)
    assert failed.returncode != 0 and "unsupported or shadowed driver include" in failed.stderr


def check_builder_rejects_nonpositive_job_count(tmp_path):
    result = subprocess.run([sys.executable, str(SCRIPT), "--dut-dir", str(tmp_path),
                             "--build-dir", str(tmp_path / "build"), "--jobs", "0"],
                            capture_output=True, text=True)
    assert result.returncode != 0 and "--jobs must be positive" in result.stderr


def check_changed_header_cannot_publish_a_build(tmp_path):
    real_compiler = shutil.which("c++")
    if not real_compiler:
        raise unittest.SkipTest("C++ compiler unavailable")
    dut, build = tmp_path / "dut", tmp_path / "build"
    dut.mkdir()
    header = dut / "dut.h"
    header.write_text("int value();\n")
    (dut / "dut_0.cpp").write_text('#include "dut.h"\nint value() { return 1; }\n')
    driver = tmp_path / "driver.cpp"
    driver.write_text('#include "dut.h"\nint main() { return value()-1; }\n')
    marker = tmp_path / "driver-started"
    compiler = tmp_path / "slow-cxx"
    compiler.write_text("#!" + sys.executable + "\n" + """import os,sys,time
from pathlib import Path
if '-c' in sys.argv and sys.argv[sys.argv.index('-c')+1].endswith('driver.cpp'):
 Path(os.environ['DRIVER_STARTED']).touch(); time.sleep(1)
os.execv(os.environ['REAL_CXX'], [os.environ['REAL_CXX'], *sys.argv[1:]])
""")
    compiler.chmod(0o755)
    env = dict(os.environ, REAL_CXX=real_compiler, DRIVER_STARTED=str(marker))
    process = subprocess.Popen([sys.executable, str(SCRIPT), "--dut-dir", str(dut),
                                "--build-dir", str(build), "--driver", str(driver),
                                "--cxx", str(compiler)], stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, env=env)
    try:
        deadline = time.monotonic() + 10
        while not marker.exists() and time.monotonic() < deadline:
            if process.poll() is not None:
                raise AssertionError("builder exited before compiling the driver")
            time.sleep(0.05)
        assert marker.exists()
        header.write_text(header.read_text() + "// changed during build\n")
        _, stderr = process.communicate(timeout=10)
        assert process.returncode != 0 and "changed during the build" in stderr
        assert not (build / "native_product_driver").exists()
        assert not (build / "native-flashsim-build.json").exists()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)


def check_termination_stops_the_compiler_process_group(tmp_path):
    dut = tmp_path / "dut"
    dut.mkdir()
    (dut / "dut.h").write_text("// header\n")
    (dut / "dut_0.cpp").write_text("// shard\n")
    marker = tmp_path / "compiler-pids.json"
    compiler = tmp_path / "slow-cxx"
    compiler.write_text("#!" + sys.executable + "\n" + """import json,os,subprocess,sys,time
from pathlib import Path
if '--version' in sys.argv:
 print('clang mock'); raise SystemExit(0)
child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
Path(os.environ['FAKE_COMPILER_PIDS']).write_text(json.dumps([os.getpid(), child.pid]))
time.sleep(60)
""")
    compiler.chmod(0o755)
    env = dict(os.environ, FAKE_COMPILER_PIDS=str(marker))
    process = subprocess.Popen([sys.executable, str(SCRIPT), "--dut-dir", str(dut),
                                "--build-dir", str(tmp_path / "build"), "--cxx", str(compiler)],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
    group = child = None
    try:
        deadline = time.monotonic() + 10
        while not marker.exists() and time.monotonic() < deadline:
            if process.poll() is not None:
                raise AssertionError("builder exited before starting the compiler")
            time.sleep(0.05)
        assert marker.exists()
        group, child = json.loads(marker.read_text())
        process.terminate()
        process.wait(timeout=10)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            state = subprocess.run(["ps", "-o", "stat=", "-p", str(child)],
                                   capture_output=True, text=True).stdout.strip()
            if not state or state.startswith("Z"):
                break
            time.sleep(0.05)
        else:
            raise AssertionError("compiler child survived builder termination")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        if group is not None:
            leader = subprocess.run(["ps", "-o", "command=", "-p", str(group)],
                                    capture_output=True, text=True).stdout
            try:
                if str(compiler) in leader:
                    os.killpg(group, signal.SIGKILL)
                elif child is not None:
                    command = subprocess.run(["ps", "-o", "command=", "-p", str(child)],
                                             capture_output=True, text=True).stdout
                    if "time.sleep(60)" in command:
                        os.kill(child, signal.SIGKILL)
            except ProcessLookupError:
                pass


class BuildNativeFlashSimTest(unittest.TestCase):
    def run_in_temporary_directory(self, check):
        with tempfile.TemporaryDirectory() as directory:
            check(Path(directory))

    def test_reuses_only_matching_objects(self):
        self.run_in_temporary_directory(check_builder_reuses_only_matching_objects)

    def test_rejects_nonpositive_job_count(self):
        self.run_in_temporary_directory(check_builder_rejects_nonpositive_job_count)

    def test_changed_header_cannot_publish(self):
        self.run_in_temporary_directory(check_changed_header_cannot_publish_a_build)

    def test_termination_stops_compiler_group(self):
        self.run_in_temporary_directory(check_termination_stops_the_compiler_process_group)


if __name__ == "__main__":
    unittest.main()
