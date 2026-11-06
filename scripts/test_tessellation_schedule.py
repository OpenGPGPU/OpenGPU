#!/usr/bin/env python3
"""Exercise scheduler-owned native snapshots through the production MMU and transport."""
import os
import hashlib
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import tessellation_rooted_host_fixture as rooted_fixture

root = Path(__file__).resolve().parents[1]
hw = (root / "driver/opengpu_hw.c").read_text()
start = hw.index("static int opengpu_hw_invalidate_snapshots_locked(")
end = hw.index("/* Publish one draw as a unified render command.", start)
compute = (root / "driver/opengpu_compute.c").read_text()
track = compute.index("int opengpu_job_track_mapping(")
track_end = compute.index("\n}\n", track) + 2
satp_start = hw.index("int opengpu_hw_set_satp(")
satp_end = hw.index("\n}\n", satp_start) + 2
activate_start = hw.index("static int opengpu_hw_activate_vm_locked(")
activate_end = hw.index("\n}\n", activate_start) + 2
abi = (root / "driver/gpu_abi.h").read_text()
satp_names = ("GPU_REG_UCMD_VECTOR_SATP", "GPU_REG_UCMD_INSTRUCTION_SATP",
              "GPU_SATP_ENABLE", "GPU_SATP_ASID_SHIFT", "GPU_SATP_ASID_MASK",
              "GPU_REG_UCMD_TLB_FLUSH", "GPU_TLB_FLUSH_FULL", "GPU_TLB_FLUSH_ASID",
              "GPU_TLB_FLUSH_ASID_SHIFT", "GPU_TLB_FLUSH_ASID_MASK")
satp_defines = "\n".join(line for line in abi.splitlines()
                         if line.startswith("#define ") and line.split()[1] in satp_names)
tlb = (root / "driver/opengpu_tlb_flush.h").read_text()
tlb_start = tlb.index("enum opengpu_tlb_flush_status")
tlb_end = tlb.index("static inline enum opengpu_tlb_flush_status\nopengpu_tlb_flush_vpn")
flush_start = hw.index("int opengpu_hw_flush_tlbs(")
flush_end = hw.index("\n}\n", hw.index("int opengpu_hw_flush_tlb_asid(")) + 2
with tempfile.TemporaryDirectory(prefix="opengpu-tess-schedule-") as tmp:
    path = Path(tmp)
    (path / "tessellation_under_test.inc").write_text(hw[start:end])
    (path / "job_track_mapping_under_test.inc").write_text(compute[track:track_end])
    (path / "satp_under_test.inc").write_text(satp_defines + "\n" +
        "typedef u32 opengpu_tlb_u32;\n" + tlb[tlb_start:tlb_end] + "\n" +
        hw[satp_start:satp_end] + "\n" + hw[activate_start:activate_end] + "\n" + hw[flush_start:flush_end])
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", str(root / "driver/tests/tessellation_sched_stubs"),
        "-I", str(root / "driver/tests/mmu_stubs"), "-I", str(root / "driver"),
        "-I", tmp, str(root / "driver/tests/opengpu_tessellation_schedule_test.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test"), *sys.argv[1:]], check=True)
    if len(sys.argv) == 1:
        subprocess.run([str(path / "test"), "--owned-uniform"], check=True)
        subprocess.run([str(path / "test"), "--owned-texture"], check=True)
        subprocess.run([str(path / "test"), "--owned-code"], check=True)
        values = ((0x3F000000, 0x3E800000), (0x3F400000, 0x3E800000), (0x3F400000, 0x3F000000))
        for domain, (texture, uniform) in enumerate(values):
            inputs, outputs = path / f"inputs-{domain}", path / f"outputs-{domain}"
            inputs.mkdir()
            outputs.mkdir()
            subprocess.run([str(path / "test"), "--seed-export-fixture", str(inputs), str(domain)], check=True)
            rooted_fixture.prepare(inputs, texture, uniform)
            subprocess.run([str(path / "test"), "--export-rooted", str(inputs), str(outputs)], check=True)
            rooted_fixture.validate_prepared(outputs)
            for name in ("resources", "code", "patch"):
                exported = {"resources": "root-data", "code": "code", "patch": "patch-data"}[name]
                assert (inputs / (name + ".bin")).read_bytes() == (outputs / (exported + ".bin")).read_bytes()
            owned = path / f"owned-{domain}"
            owned.mkdir()
            subprocess.run([str(path / "test"), "--export-owned-uniform", str(inputs), str(owned)], check=True)
            manifest = rooted_fixture.validate_prepared(owned)
            assert manifest["uniformOwned"]
            for name in ("root-data", "code", "patch-data"):
                assert (outputs / (name + ".bin")).read_bytes() == (owned / (name + ".bin")).read_bytes()
            texture_owned = path / f"texture-owned-{domain}"
            texture_owned.mkdir()
            subprocess.run([str(path / "test"), "--export-owned-texture", str(inputs), str(texture_owned)], check=True)
            manifest = rooted_fixture.validate_prepared(texture_owned)
            assert manifest["uniformOwned"] and manifest["textureOwned"]
            for name in ("root-data", "code", "patch-data"):
                assert (outputs / (name + ".bin")).read_bytes() == (texture_owned / (name + ".bin")).read_bytes()
            for mode, directory in (("trusted", outputs), ("uniform", owned), ("texture", texture_owned)):
                digest = hashlib.sha256()
                for entry in sorted(directory.iterdir()):
                    digest.update(entry.name.encode() + b"\0" + hashlib.sha256(entry.read_bytes()).digest())
                print(f"C_EXPORT domain={domain} mode={mode} sha256={digest.hexdigest()}", flush=True)
        print("C-prepared three-domain rooted snapshot/Sv32 roundtrip PASS (no RTL execution)")
        print("Owned GUR/GUB/default C exports: complete metadata, Sv32 and unchanged shader/data values PASS (no RTL execution)")
        print("Owned GXR/GXB/GXI/GXS/GXL C exports: copied chain, complete Sv32 footprints and unchanged texels PASS (no RTL execution)")
