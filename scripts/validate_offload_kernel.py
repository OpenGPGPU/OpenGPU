#!/usr/bin/env python3
"""Check LLVM device code against the shader-corpus validator.

Compiles a few ``__attribute__((device_kernel))`` kernels with
``clang --target=opengpu``, extracts ``.text``, and admits them with the same
``userspace/shaders/validate.c`` profiles as ``validate_shader_corpus.py``.
The two C shaders that the corpus already compiles with RISC-V gcc
(``compute_increment``, ``vertex_offset``) are compiled both ways. Register
allocation may differ; the load, add-immediate, store and ``cease`` operations
must match. ``kadd`` is also packaged with ``clang-offload-packager`` and
loaded back through ``libopengpu``.

``userspace/shaders/fragment_color.c`` and ``vertex_passthrough.c`` are the
same per-lane copies as the Gallium assembly, written with an ``x8`` lane
base and RVV loads and stores. They are admitted on the fragment and vertex
profiles. Texture and quad assembly (``vtex.sample``, ``vquad.dfdx``) stays
on ``validate_shader_corpus.py`` until a clang builtin exists. Gallium still
binds the assembly words.

The script skips when the OpenGPU clang is not available, so the host CI job
can call it unconditionally. Set ``OPENGPU_CLANG`` to override the sibling
``../llvm-build/bin/clang`` probe.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TRIPLE = "opengpu-unknown-none-elf"
CEASE = 0x30500073

# profile, optional corpus shader name, source
KERNELS = (
    (0, "compute_increment", """\
__attribute__((device_kernel))
void shader(unsigned *kernarg)
{
    kernarg[1] = kernarg[0] + 1u;
}
"""),
    (2, "vertex_offset", """\
__attribute__((device_kernel))
void shader(unsigned *kernarg)
{
    kernarg[64] = kernarg[0] + 0x100u;
}
"""),
    (0, None, """\
__attribute__((device_kernel))
void kadd(int *p, int n)
{
    *p = *p + n + 3;
}
"""),
    (5, None, """\
__attribute__((device_kernel))
void kf(float *p, float n)
{
    *p = *p + n;
}
"""),
)


def default_tool(env_name, filename):
    override = os.environ.get(env_name)
    if override:
        return override
    sibling = ROOT.parent / "llvm-build" / "bin" / filename
    if sibling.is_file():
        return str(sibling)
    return filename


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def toolchain_supports_opengpu(clang):
    with tempfile.TemporaryDirectory(prefix="opengpu-probe-") as directory:
        source = Path(directory) / "probe.c"
        source.write_text("int opengpu_probe;\n")
        probe = subprocess.run([clang, "--target=opengpu", "-c", str(source),
                                "-o", str(Path(directory) / "probe.o")],
                               stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
    return probe.returncode == 0


def load_words(path):
    data = Path(path).read_bytes()
    if not data or len(data) % 4:
        raise SystemExit(f"{path}: device code is not a word stream")
    return [int.from_bytes(data[i:i + 4], "little") for i in range(0, len(data), 4)]


def signed_imm(value):
    if value & 0x800:
        value -= 0x1000
    return value


def mem_ops(words):
    """x1-relative lw/sw, addi immediates, and cease. Registers are ignored."""
    ops = []
    for insn in words:
        opcode = insn & 0x7f
        funct3 = (insn >> 12) & 7
        rs1 = (insn >> 15) & 0x1f
        if insn == CEASE:
            ops.append(("cease", 0))
        elif opcode == 0x03 and funct3 == 0x2 and rs1 == 1:
            ops.append(("lw", signed_imm(insn >> 20)))
        elif opcode == 0x23 and funct3 == 0x2 and rs1 == 1:
            imm = ((insn >> 25) << 5) | ((insn >> 7) & 0x1f)
            ops.append(("sw", signed_imm(imm)))
        elif opcode == 0x13 and funct3 == 0:
            ops.append(("addi", signed_imm(insn >> 20)))
    return ops


# profile, source path. These need the OpenGPU clang RVV builtin path.
VECTOR_KERNELS = (
    (1, ROOT / "userspace/shaders/fragment_color.c"),
    (2, ROOT / "userspace/shaders/vertex_passthrough.c"),
)


def compile_device(clang, objcopy, source, work, name):
    src = work / f"{name}.c"
    obj = work / f"{name}.o"
    binary = work / f"{name}.bin"
    src.write_text(source)
    run([clang, "--target=opengpu", "-O2", "-c", str(src), "-o", str(obj)])
    run([objcopy, "-O", "binary", "--only-section=.text", str(obj), str(binary)])
    return binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clang", default=default_tool("OPENGPU_CLANG", "clang"))
    parser.add_argument("--objcopy", default=None)
    parser.add_argument("--packager", default=None)
    parser.add_argument("--host-cc", default=os.environ.get("CC", "cc"))
    args = parser.parse_args()
    clang_bin = Path(args.clang).parent
    objcopy = args.objcopy or str(clang_bin / "llvm-objcopy")
    if not Path(objcopy).is_file():
        objcopy = shutil.which("llvm-objcopy") or "llvm-objcopy"
    packager = args.packager or str(clang_bin / "clang-offload-packager")
    if not Path(packager).is_file():
        packager = os.environ.get("OPENGPU_PACKAGER", "clang-offload-packager")

    if not toolchain_supports_opengpu(args.clang):
        print(f"{args.clang}: no OpenGPU target, skipping")
        return

    with tempfile.TemporaryDirectory(prefix="opengpu-offload-") as directory:
        work = Path(directory)
        binaries = []
        kadd_obj = None
        for index, (profile, corpus_name, source) in enumerate(KERNELS):
            name = corpus_name or f"kernel{index}"
            binary = compile_device(args.clang, objcopy, source, work, name)
            binaries.append((binary, profile, corpus_name))
            if "void kadd(" in source:
                kadd_obj = work / f"{name}.o"
        for profile, path in VECTOR_KERNELS:
            binary = compile_device(args.clang, objcopy, path.read_text(),
                                    work, path.stem)
            binaries.append((binary, profile, None))

        validator = work / "validate"
        run([args.host_cc, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
             str(ROOT / "userspace/shaders/validate.c"), "-o", str(validator)])
        validate_args = [str(validator)]
        for binary, profile, _corpus_name in binaries:
            validate_args.extend([str(binary), str(profile)])
        run(validate_args)

        gcc = os.environ.get("RISCV_GCC", "riscv64-unknown-elf-gcc")
        if shutil.which(gcc):
            for binary, profile, corpus_name in binaries:
                if corpus_name is None:
                    continue
                corpus_bin = work / f"corpus-{corpus_name}.bin"
                run(["python3", str(ROOT / "scripts/validate_shader_corpus.py"),
                     "--emit", corpus_name, str(corpus_bin)])
                run([str(validator), str(corpus_bin), str(profile)])
                llvm_ops = mem_ops(load_words(binary))
                corpus_ops = mem_ops(load_words(corpus_bin))
                if sorted(llvm_ops) != sorted(corpus_ops):
                    raise SystemExit(
                        f"{corpus_name}: LLVM ops {llvm_ops} differ from "
                        f"corpus ops {corpus_ops}")
                print(f"{corpus_name}: LLVM matches corpus memory ops")
        else:
            print(f"{gcc}: not on PATH, skipping corpus cross-check")

        container = work / "kadd.fatbin"
        run([packager, "-o", str(container),
             f"--image=file={kadd_obj},triple={TRIPLE},arch=opengpu"])
        probe = work / "probe"
        run([args.host_cc, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
             "-I" + str(ROOT / "userspace/libopengpu"),
             "-I" + str(ROOT / "driver"),
             str(ROOT / "userspace/libopengpu/tools/opengpu_offload_validate.c"),
             str(ROOT / "userspace/libopengpu/opengpu_offload.c"),
             "-o", str(probe)])
        run([str(probe), str(container), TRIPLE])
    print("LLVM device code vs shader corpus: PASS")


if __name__ == "__main__":
    main()
