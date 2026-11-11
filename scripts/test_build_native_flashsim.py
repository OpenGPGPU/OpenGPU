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


def wait_for_progress(path, process, predicate):
    deadline = time.monotonic() + 15
    observed = None
    while time.monotonic() < deadline:
        if path.is_file():
            observed = json.loads(path.read_text())
            if predicate(observed):
                return observed
        if process.poll() is not None:
            raise AssertionError(f"builder exited before expected progress: {observed}")
        time.sleep(0.05)
    raise AssertionError(f"builder did not publish expected progress: {observed}")


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
    assert commit["compile_elapsed_seconds"] > 0
    progress = json.loads((build / "native-flashsim-progress.json").read_text())
    assert progress["stage"] == "built" and not progress["active_shards"]
    assert progress["total_shards"] == progress["hashed_shards"] == progress["compiled_shards"] == 2
    assert progress["cached_shards"] == 0 and progress["elapsed_seconds"] > 0
    # Old cache records without observational timing remain valid.
    del commit["compile_elapsed_seconds"]
    (build / "dut_commit_part000.json").write_text(json.dumps(commit))
    second = run()
    assert "dut_0.cpp: cached" in second and "dut_commit_part000.cpp: cached" in second
    assert json.loads((build / "native-flashsim-build.json").read_text()) == manifest
    progress = json.loads((build / "native-flashsim-progress.json").read_text())
    assert progress["compiled_shards"] == 0 and progress["cached_shards"] == 2

    shard.write_text('#include "dut.h"\nint value() { return 41; }\n')
    third = run()
    assert "dut_0.cpp: compiled" in third and "dut_commit_part000.cpp: cached" in third
    assert subprocess.check_output([str(build / "native_product_driver")], text=True).strip() == "43"
    changed_opt = run(("--commit-opt=-O1",))
    assert "dut_0.cpp: cached" in changed_opt and "dut_commit_part000.cpp: compiled" in changed_opt
    commit = json.loads((build / "dut_commit_part000.json").read_text())
    assert commit["context"]["optimization"] == "-O1"
    assert "-fno-strict-aliasing" in commit["context"]["flags"]

    support = subprocess.run(["c++", "-fstrict-flex-arrays=1", "-x", "c++", "-fsyntax-only", "-"],
                             input="int main() {}\n", capture_output=True, text=True)
    if support.returncode == 0:
        strict = run(("--commit-opt=-O1", "--strict-flex-arrays"))
        assert "dut_0.cpp: compiled" in strict and "dut_commit_part000.cpp: compiled" in strict
        assert subprocess.check_output([str(build / "native_product_driver")], text=True).strip() == "43"
        manifest = json.loads((build / "native-flashsim-build.json").read_text())
        assert manifest["strict_flex_arrays"] is True
        assert "-fstrict-flex-arrays=1" in manifest["base_flags"]
        cached = run(("--commit-opt=-O1", "--strict-flex-arrays"))
        assert "dut_0.cpp: cached" in cached and "dut_commit_part000.cpp: cached" in cached

    header.write_text(header.read_text() + "// changed\n")
    changed_header = run(("--commit-opt=-O1",))
    assert "dut_0.cpp: compiled" in changed_header and "dut_commit_part000.cpp: compiled" in changed_header
    shard.write_text("invalid C++ source\n")
    # One worker reports the larger healthy commit object's cache hit before
    # testing the malformed source. Parallel cancellation has its own handshake.
    failed = subprocess.run([*command, "--commit-opt=-O1", "--jobs", "1"],
                            capture_output=True, text=True)
    compiler_log = (build / "dut_0.log").read_text()
    progress = json.loads((build / "native-flashsim-progress.json").read_text())
    diagnostics = {"returncode": failed.returncode, "stdout": failed.stdout,
                   "stderr": failed.stderr, "compiler_log": compiler_log, "progress": progress}
    assert failed.returncode != 0 and "compile failed" in failed.stderr, diagnostics
    assert "dut_commit_part000.cpp: cached" in failed.stdout, diagnostics
    assert "error" in compiler_log.lower() and "invalid" in compiler_log, diagnostics
    assert progress["stage"] == "failed" and not progress["active_shards"], diagnostics
    assert "compile failed: dut_0.cpp" in progress["error"], diagnostics
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


def check_failed_shard_stops_other_compiler_process_group(tmp_path):
    dut, build = tmp_path / "dut", tmp_path / "build"
    dut.mkdir()
    (dut / "dut.h").write_text("// header\n")
    for name in ("dut_0.cpp", "dut_1.cpp"):
        (dut / name).write_text("// shard\n")
    marker = tmp_path / "sibling-pids.json"
    compiler = tmp_path / "failing-cxx"
    compiler.write_text("#!" + sys.executable + "\n" + """import json,os,subprocess,sys,time
from pathlib import Path
if '--version' in sys.argv:
 print('clang mock'); raise SystemExit(0)
source = Path(sys.argv[sys.argv.index('-c')+1])
Path(sys.argv[sys.argv.index('-o')+1]).write_text('partial object')
marker = Path(os.environ['SIBLING_COMPILER_PIDS'])
if source.name == 'dut_0.cpp':
 deadline = time.monotonic()+10
 while not marker.exists():
  if time.monotonic() >= deadline: raise SystemExit('sibling compiler did not start')
  time.sleep(0.05)
 print('intentional dut_0.cpp compiler failure', file=sys.stderr, flush=True)
 raise SystemExit(23)
child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)', __file__])
temporary = marker.with_suffix('.tmp')
temporary.write_text(json.dumps([os.getpid(), child.pid])); temporary.replace(marker)
time.sleep(60)
""")
    compiler.chmod(0o755)
    process = subprocess.Popen([sys.executable, str(SCRIPT), "--dut-dir", str(dut),
                                "--build-dir", str(build), "--cxx", str(compiler), "--jobs", "2"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                               env=dict(os.environ, SIBLING_COMPILER_PIDS=str(marker)))
    try:
        stdout, stderr = process.communicate(timeout=20)
        progress = json.loads((build / "native-flashsim-progress.json").read_text())
        log = (build / "dut_0.log").read_text()
        diagnostics = {"returncode": process.returncode, "stdout": stdout, "stderr": stderr,
                       "compiler_log": log, "progress": progress}
        assert process.returncode != 0 and "compile failed: dut_0.cpp" in stderr, diagnostics
        assert log.strip() == "intentional dut_0.cpp compiler failure", diagnostics
        assert all(name + ": compiling" in stdout for name in ("dut_0.cpp", "dut_1.cpp")), diagnostics
        assert progress["stage"] == "failed" and not progress["active_shards"], diagnostics
        assert "compile failed: dut_0.cpp" in progress["error"], diagnostics
        assert marker.is_file(), diagnostics
        group, child = json.loads(marker.read_text())
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            states = [subprocess.run(["ps", "-o", "stat=", "-p", str(pid)],
                                     capture_output=True, text=True).stdout.strip()
                      for pid in (group, child)]
            if all(not state or state.startswith("Z") for state in states):
                break
            time.sleep(0.05)
        else:
            raise AssertionError(f"sibling compiler or child survived shard failure: {states}; {diagnostics}")
        assert not list(build.glob("*.o")) and not list(build.glob("*.o.tmp")), diagnostics
        assert not any((build / name).exists() for name in
                       ("dut_0.json", "dut_1.json", "native_product_driver", "native-flashsim-build.json")), diagnostics
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate(timeout=10)
        if marker.is_file():
            group, child = json.loads(marker.read_text())
            for pid in (group, child):
                command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)],
                                         capture_output=True, text=True).stdout
                if str(compiler) in command:
                    try:
                        if pid == group:
                            os.killpg(group, signal.SIGKILL)
                        else:
                            os.kill(child, signal.SIGKILL)
                    except ProcessLookupError:
                        pass


def check_assertion_model_build_and_exception(tmp_path):
    if not shutil.which("c++"):
        raise unittest.SkipTest("C++ compiler unavailable")
    dut, build = tmp_path / "dut", tmp_path / "build"
    dut.mkdir()
    (dut / "dut.h").write_text("""#include <cstdio>
#include <stdexcept>
#include <string>
int step(bool fail);
""")
    (dut / "dut_0.cpp").write_text('''#include "dut.h"
int step(bool fail) {
  if (fail) {
    std::fputs("clocked diagnostic; ", stderr);
    throw std::runtime_error(std::string("bound checker") + " before NBA");
  }
  return 42;
}
''')
    driver = tmp_path / "driver.cpp"
    driver.write_text('''#include "dut.h"
#include <iostream>
int main(int argc, char **) {
  try { return step(argc > 1) == 42 ? 0 : 1; }
  catch (const std::exception &e) { std::cerr << e.what(); return 42; }
}
''')
    command = [sys.executable, str(SCRIPT), "--dut-dir", str(dut),
               "--build-dir", str(build), "--driver", str(driver), "--jobs", "1"]
    subprocess.run(command, check=True, capture_output=True, text=True)
    binary = build / "native_product_driver"
    subprocess.run([str(binary)], check=True)
    failing = subprocess.run([str(binary), "fail"], capture_output=True, text=True)
    assert failing.returncode == 42 and failing.stderr == "clocked diagnostic; bound checker before NBA"
    for name in ("cstdio", "stdexcept", "string"):
        shadow = dut / name
        shadow.write_text("// untracked replacement for a standard header\n")
        rejected = subprocess.run(command, capture_output=True, text=True)
        assert rejected.returncode != 0 and "shadows a required standard header" in rejected.stderr
        shadow.unlink()


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
 Path(os.environ['DRIVER_STARTED']).touch()
 deadline=time.monotonic()+15
 while not Path(os.environ['DRIVER_RELEASE']).exists():
  if time.monotonic() >= deadline: raise SystemExit('driver release timed out')
  time.sleep(0.05)
os.execv(os.environ['REAL_CXX'], [os.environ['REAL_CXX'], *sys.argv[1:]])
""")
    compiler.chmod(0o755)
    release = tmp_path / "release-driver"
    env = dict(os.environ, REAL_CXX=real_compiler, DRIVER_STARTED=str(marker),
               DRIVER_RELEASE=str(release))
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
        progress = wait_for_progress(build / "native-flashsim-progress.json", process,
                                     lambda value: value["stage"] == "compiling-driver")
        assert progress["child_pid"] > 0 and not progress["active_shards"]
        header.write_text(header.read_text() + "// changed during build\n")
        release.touch()
        _, stderr = process.communicate(timeout=10)
        assert process.returncode != 0 and "changed during the build" in stderr
        assert not (build / "native_product_driver").exists()
        assert not (build / "native-flashsim-build.json").exists()
        progress = json.loads((build / "native-flashsim-progress.json").read_text())
        assert progress["stage"] == "failed" and progress["child_pid"] is None
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
marker = Path(os.environ['FAKE_COMPILER_PIDS'])
temporary = marker.with_suffix('.tmp')
temporary.write_text(json.dumps([os.getpid(), child.pid])); temporary.replace(marker)
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
        progress_path = tmp_path / "build/native-flashsim-progress.json"
        progress = wait_for_progress(progress_path, process, lambda value:
                                     value["stage"] == "compiling-shards" and
                                     any(p["pid"] == group for p in value["active_shards"]))
        assert progress["pid"] == process.pid
        assert [(p["name"], p["pid"]) for p in progress["active_shards"]] == [("dut_0.cpp", group)]
        # A long-running shard updates without needing to finish first.
        newer = wait_for_progress(progress_path, process,
                                  lambda value: value["updated_at"] != progress["updated_at"])
        assert newer["active_shards"][0]["elapsed_seconds"] > progress["active_shards"][0]["elapsed_seconds"]
        process.terminate()
        process.wait(timeout=10)
        progress = json.loads(progress_path.read_text())
        assert progress["stage"] == "interrupted" and not progress["active_shards"]
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

    def test_failed_shard_stops_other_compiler_group(self):
        self.run_in_temporary_directory(check_failed_shard_stops_other_compiler_process_group)

    def test_assertion_model_build_and_exception(self):
        self.run_in_temporary_directory(check_assertion_model_build_and_exception)

    def test_changed_header_cannot_publish(self):
        self.run_in_temporary_directory(check_changed_header_cannot_publish_a_build)

    def test_termination_stops_compiler_group(self):
        self.run_in_temporary_directory(check_termination_stops_the_compiler_process_group)


if __name__ == "__main__":
    unittest.main()
