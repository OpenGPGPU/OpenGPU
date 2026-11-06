#!/usr/bin/env python3
"""Implementation of build_gallium_compute_runtime.py --glsl-buffer.

Reuses real configured Mesa AArch64 objects. Outputs are isolated; this never
rebuilds shared Mesa, opens a DRM device, emits RTL or boots a guest.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time

from apply_mesa_uniform_index_patch import AFTER
from gallium_buffer_qualification import stage_sources

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def elf(path):
    data = Path(path).read_bytes()[:20]
    if data[:6] != b"\x7fELF\x02\x01" or int.from_bytes(data[18:20], "little") != 183:
        raise RuntimeError("not an actual AArch64 little-endian ELF: " + str(path))


def build(args):
    mesa, mb, out = args.mesa_source.resolve(), args.mesa_build.resolve(), args.out.resolve()
    if (mesa / "VERSION").read_text().strip() != "22.3.6":
        raise RuntimeError("the configured actual Mesa22.3.6 source is required")
    checked = (args.mesa_nir_lower_io or mesa / "src/compiler/nir/nir_lower_io.c").resolve(strict=True)
    if sha(checked) != AFTER:
        raise RuntimeError("stage the exact tracked Mesa checked-uniform patch in a private source first")
    if out.is_relative_to(mesa) or out.is_relative_to(mb):
        raise RuntimeError("output must be outside shared Mesa source/build")
    out.mkdir(parents=True, exist_ok=False)
    commands, inventory, before = [], {}, {}
    cc, cxx = shlex.split(args.cc), shlex.split(args.cxx)
    database = json.loads((mb / "compile_commands.json").read_text())
    env = os.environ.copy()
    for key in ("OPENGPU_COMPUTE_ARTIFACTS", "OPENGPU_CF_ARTIFACTS", "OPENGPU_BUFFER_ARTIFACTS"):
        env.pop(key, None)
    error, exit_code = None, 1

    def run(argv, label, sanitized=False):
        argv = list(map(str, argv))
        commands.append({"argv": argv, "cwd": str(out), "log": label + ".log"})
        (out / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        active_env = env.copy()
        if sanitized:
            # The prebuilt Mesa archives retain compiler-global singletons and
            # are not fully instrumented. New app/frontend/oracle code is.
            active_env.update(ASAN_OPTIONS="detect_leaks=0:abort_on_error=1",
                              UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
        if label == "CPU-app-lifecycle":
            active_env.update(ASAN_OPTIONS="detect_leaks=1:abort_on_error=1",
                              UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
        with (out / (label + ".log")).open("w") as log:
            subprocess.run(argv, cwd=out, check=True, stdout=log, stderr=subprocess.STDOUT, env=active_env)
        return (out / (label + ".log")).read_text()

    def configured(relative):
        target = mesa / "src" / relative
        entry = next(e for e in database if (Path(e["directory"]) / e["file"]).resolve() == target)
        raw = entry.get("arguments") or shlex.split(entry["command"])
        flags, skip = [], False
        for flag in raw[1:]:
            if skip:
                skip = False
                continue
            if flag in ("-o", "-MF", "-MQ", "-MT"):
                skip = True
                continue
            if flag in ("-c", "-MD", "-MMD"):
                continue
            if not flag.startswith("-") and (Path(entry["directory"]) / flag).resolve() == target:
                continue
            if flag.startswith("-I") and not Path(flag[2:]).is_absolute():
                flag = "-I" + str((Path(entry["directory"]) / flag[2:]).resolve())
            flags.append(flag)
        return flags

    def compile_unit(compiler, flags, source, name):
        obj = out / (name + ".o")
        run(compiler + flags + ["-MMD", "-MF", obj.with_suffix(".d"), "-c", source, "-o", obj], "compile-" + name)
        elf(obj)
        return obj

    def closure(binary, label):
        elf(binary)
        programs = run(["readelf", "-l", binary], label + "-PT_INTERP")
        dynamic = run(["readelf", "-d", binary], label + "-NEEDED")
        run(["readelf", "-V", binary], label + "-symbol-versions")
        interpreter = re.findall(r"Requesting program interpreter: ([^\]]+)", programs)
        if interpreter != ["/lib/ld-linux-aarch64.so.1"]:
            raise RuntimeError("unexpected actual ELF interpreter: " + repr(interpreter))
        needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
        pending, selected = needed + ["ld-linux-aarch64.so.1"], {}
        while pending:
            name = pending.pop()
            if name in selected:
                continue
            paths = [Path("/lib/aarch64-linux-gnu") / name, Path("/usr/lib/aarch64-linux-gnu") / name,
                     Path("/lib") / name]
            path = next((p.resolve() for p in paths if p.is_file()), None)
            if path is None:
                raise RuntimeError("no real coherent image library for " + name)
            elf(path)
            deps = re.findall(r"\(NEEDED\).*?\[(.*?)\]", run(["readelf", "-d", path], label + "-" + name))
            selected[name] = {"path": str(path), "sha256": sha(path), "bytes": path.stat().st_size, "needed": deps}
            pending += deps
        rootfs = out / "runtime-rootfs"
        for name, row in selected.items():
            target = rootfs / ("lib/ld-linux-aarch64.so.1" if name == "ld-linux-aarch64.so.1" else "lib/aarch64-linux-gnu/" + name)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(row["path"], target)
            target.chmod(0o755 if name == "ld-linux-aarch64.so.1"
                         else Path(row["path"]).stat().st_mode & 0o777)
        result = {"interpreter": interpreter, "needed": needed, "libraries": selected,
                  "ELF_sha256": sha(binary), "scope": "actual app dynamic closure; no guest execution"}
        (out / (label + "-ELF-closure.json")).write_text(json.dumps(result, indent=2) + "\n")
        return result

    try:
        run(cc + ["--version"], "C-compiler")
        run(cxx + ["--version"], "CXX-compiler")
        archives = [mb / p for p in (
            "src/compiler/glsl/libglsl.a", "src/compiler/nir/libnir.a", "src/compiler/libcompiler.a",
            "src/compiler/glsl/glcpp/libglcpp.a", "src/gallium/auxiliary/libgallium.a", "src/util/libmesa_util.a",
            "src/util/format/libmesa_format.a", "src/c11/impl/libmesa_util_c11.a")]
        for i, archive in enumerate(archives):
            before[str(archive)] = sha(archive)
            for member in run(["ar", "t", archive], "archive-" + str(i)).splitlines():
                path = Path(member)
                if not path.is_absolute():
                    path = archive.parent / path
                path = path.resolve(strict=True)
                elf(path)
                inventory[str(path)] = {"archive": str(archive), "sha256": sha(path), "bytes": path.stat().st_size}
                before[str(path)] = sha(path)
        state = [mb / "src/mesa/libmesa.a.p" / ("state_tracker_st_atom_" + n + ".c.o") for n in ("constbuf", "storagebuf")]
        for path in state:
            elf(path)
            before[str(path)] = sha(path)
        for path in (mb / "compile_commands.json", checked):
            before[str(path)] = sha(path)
        (out / "mesa-members.json").write_text(json.dumps(inventory, indent=2, sort_keys=True) + "\n")
        (out / "input-before.json").write_text(json.dumps(before, indent=2, sort_keys=True) + "\n")
        text = (mesa / "src/compiler/glsl/standalone.cpp").read_text()
        anchor = "   if (options->lower_precision) {"
        if text.count(anchor) != 1:
            raise RuntimeError("official standalone compiler profile preimage changed")
        text = text.replace(anchor, "   opengpu_frontend_compiler_limits(ctx);\n\n" + anchor)
        text = '#include "main/mtypes.h"\nextern void opengpu_frontend_compiler_limits(struct gl_context *);\n' + text
        private_standalone = out / "standalone-profile.cpp"
        private_standalone.write_text(text)
        common_objects = []
        for rel in ("compiler/glsl/standalone.cpp", "compiler/glsl/standalone_scaffolding.cpp",
                    "compiler/glsl/ir_builder_print_visitor.cpp", "compiler/glsl/glcpp/pp_standalone_scaffolding.c",
                    "mesa/main/extensions_table.c", "mesa/program/prog_parameter.c",
                    "mesa/program/symbol_table.c", "mesa/program/dummy_errors.c"):
            source = private_standalone if rel.endswith("/standalone.cpp") else mesa / "src" / rel
            common_objects.append(compile_unit(cxx if rel.endswith(".cpp") else cc, configured(rel) + ["-UNDEBUG"],
                                               source, rel.replace("/", "_")))
        include = ["-I" + str(p) for p in (ROOT / "userspace/tests", ROOT / "userspace/gallium", ROOT / "userspace",
            ROOT / "driver", args.drm_include.resolve(), mesa / "src/gallium/include", mesa / "src/gallium/auxiliary",
            mesa / "src", mesa / "include")]
        config = configured("gallium/auxiliary/tgsi/tgsi_parse.c")
        definitions = [x for x in config if x.startswith(("-D", "-U"))]
        mesa_includes = [x for x in config if x.startswith("-I")]
        flags = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                 "-Wno-sign-compare", "-fno-strict-aliasing", "-ffunction-sections", "-fdata-sections", "-pthread"]
        flags += definitions + ["-UNDEBUG"] + include + mesa_includes
        frontend_flags = configured("compiler/glsl/standalone.cpp") + ["-UNDEBUG", "-Wall", "-Wextra", "-Werror",
            "-Wno-unused-parameter", "-I" + str(mesa / "src/compiler/nir"), "-I" + str(mb / "src/compiler/nir")] + include
        frontend = compile_unit(cxx, frontend_flags, ROOT / "userspace/tests/cs_glsl_frontend.cpp", "real-frontend")
        common_objects += [compile_unit(cc, configured("compiler/nir/nir_lower_io.c") + ["-UNDEBUG"], checked, "checked-IO")] + state
        sources = [ROOT / "userspace/examples/gallium_buffer_glsl.c"] + [ROOT / ("userspace/" + x) for x in (
            "gallium/opengpu_screen.c", "gallium/opengpu_context.c", "gallium/opengpu_cs_emit.c",
            "gallium/opengpu_fs_emit.c", "gallium/opengpu_vs_emit.c", "pipe_opengpu.c", "opengpu.c")]
        own_objects = [compile_unit(cc, flags, source, source.stem) for source in sources]
        extra = [Path("/lib/aarch64-linux-gnu") / x for x in ("libz.so.1", "libexpat.so.1")]
        for path in extra:
            elf(path)
        libs = ["-Wl,--start-group"] + archives + ["-Wl,--end-group"] + extra + ["-ldl", "-lm", "-pthread"]
        binary = out / "gallium_buffer_glsl"
        run(cxx + ["-Wl,--gc-sections", "-Wl,-t,-t", "-Wl,-Map=" + str(out / "guest.map")] +
            own_objects + [frontend] + common_objects + libs + ["-o", binary], "link-guest")
        app_closure = closure(binary, "guest")
        symbols = run(["nm", "-a", binary], "guest-symbols")
        if "cs_vm_" in symbols or "cs_fixture_" in symbols:
            raise RuntimeError("CPU decoder or modeled OS boundary leaked into the actual guest ELF")
        required = ("cs_frontend_compile_buffers", "st_bind_cs_ubos", "st_bind_cs_ssbos", "standalone_compile_shader",
                    "nir_to_tgsi", "opengpu_cs_emit", "pipe_opengpu_launch_grid_uniforms", "pipe_opengpu_launch_grid_buffers", "opengpu_compute_uniform_resources")
        actual_symbols = {line.split()[-1] for line in symbols.splitlines() if line.split()}
        if set(required) - actual_symbols:
            raise RuntimeError("missing real frontend/callback/DRM symbols: " + repr(set(required) - actual_symbols))
        glsl = out / "glsl"
        stage_sources(ROOT / "userspace/examples/glsl_buffer", glsl, args.buffer_qualification_items)
        profile = ["--qualification-items", str(args.buffer_qualification_items)]
        if args.run_preflight:
            captures = out / "guest-preflight"
            run([binary, "--preflight", captures, glsl] + profile, "guest-preflight")
            san = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"] if args.sanitize_preflight else []
            obj = compile_unit(cc, flags + san, ROOT / "userspace/tests/gallium_buffer_glsl_preflight.c", "CPU-oracle")
            cpu = out / "CPU-oracle"
            run(cc + san + [obj, "-o", cpu], "link-CPU-oracle")
            run([cpu, captures] + profile, "CPU-word-preflight", bool(san))
            if san:
                # Compiler/preflight/cleanup portion of the actual app also
                # runs instrumented. A preflight never opens a DRM device.
                app_san = compile_unit(cc, flags + san, sources[0], "ASan-app")
                frontend_san = compile_unit(cxx, frontend_flags + san, ROOT / "userspace/tests/cs_glsl_frontend.cpp", "ASan-frontend")
                sanitized = out / "ASan-app"
                run(cxx + san + ["-Wl,--gc-sections"] + [app_san] + own_objects[1:] +
                    [frontend_san] + common_objects + libs + ["-o", sanitized], "link-ASan-app")
                run([sanitized, "--preflight", out / "ASan-preflight", glsl] + profile, "ASan-frontend-preflight", True)
            # Exercise the actual app's destruction order with real Gallium
            # callbacks and only the existing modeled OS/GEM boundary. The
            # guest ELF above never contains these interposed primitives.
            app_text = sources[0].read_text()
            first = app_text.index("static void destroy_context(void)\n{")
            last = app_text.index("\nstatic void cleanup(void)", first)
            (out / "buffer-app-destroy.inc").write_text(app_text[first:last] + "\n")
            # File-offset redirects otherwise turn the fixture mmap alias
            # into real mmap64; keep the existing OS-boundary test ABI.
            life_flags = [f for f in flags if not f.startswith("-D_FILE_OFFSET_BITS=")] + san + ["-I" + str(out)]
            life_objects = [compile_unit(cc, life_flags,
                ROOT / "userspace/tests/gallium_buffer_glsl_lifecycle.c", "lifecycle-fixture")]
            for source in sources[1:]:
                primitives = ["-D" + name + "=cs_fixture_" + name for name in
                              ("ioctl", "mmap", "munmap", "dup", "close")] if source.name in ("pipe_opengpu.c", "opengpu.c") else []
                life_objects.append(compile_unit(cc, life_flags + primitives, source, "lifecycle-" + source.stem))
            life = out / "CPU-lifecycle"
            run(cxx + san + ["-Wl,--gc-sections"] + life_objects + libs + ["-o", life], "link-CPU-lifecycle")
            # Unlike the real frontend, this focused callback test initializes
            # no compiler-global singletons, so LeakSanitizer stays enabled.
            run([life], "CPU-app-lifecycle")

        (out / "result.json").write_text(json.dumps({"status": "PASS_CPU_BUILD_PREFLIGHT",
            "qualification_items": args.buffer_qualification_items,
            "binary_sha256": sha(binary), "ELF_bytes": binary.stat().st_size,
            "actual_linked_frontend_and_callbacks": required, "ELF_closure": app_closure,
            "CPU_preflight": args.run_preflight, "sanitized_new_CPU_code": args.sanitize_preflight,
            "scope": "real ESSL/NIR/TGSI+validator+CPU oracle; callbacks/DRM linked, device/GPU/guest execution UNRUN"}, indent=2) + "\n")
        exit_code = 0
    except BaseException as exc:
        error = repr(exc)
        raise
    finally:
        # Preserve actual inputs even when link, 512word, TEMP or oracle gates
        # fail. Thin archive member bytes, not just tiny archive headers, count.
        deps = {}
        for path in out.glob("*.d"):
            for token in path.read_text().replace("\\\n", " ").split(":", 1)[1].split():
                p = Path(token)
                if not p.is_absolute():
                    p = out / p
                p = p.resolve(strict=True)
                deps[str(p)] = sha(p)
        (out / "actual-inputs.json").write_text(json.dumps(deps, indent=2, sort_keys=True) + "\n")
        trace = (out / "link-guest.log").read_text() if (out / "link-guest.log").exists() else ""
        selected = {p: row for p, row in inventory.items() if p in trace}
        (out / "selected-mesa-members.json").write_text(json.dumps(selected, indent=2, sort_keys=True) + "\n")
        post = {p: sha(p) for p in before}
        (out / "input-post.json").write_text(json.dumps(post, indent=2, sort_keys=True) + "\n")
        changed = [p for p in before if before[p] != post[p]]
        (out / "terminal.json").write_text(json.dumps({"exit_code": exit_code or int(bool(changed)),
            "error": error, "input_changed": changed, "actual_MMD_count": len(deps),
            "actual_selected_Mesa_members": len(selected), "completed_ns": time.time_ns(),
            "GPU_execution": False, "guest_boot": False}, indent=2) + "\n")
        if changed:
            raise RuntimeError("read-only actual Mesa inputs changed")
