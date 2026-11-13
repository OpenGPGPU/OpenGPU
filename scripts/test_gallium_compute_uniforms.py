#!/usr/bin/env python3
"""Explicit CPU-only ESSL310 -> real Mesa/NIR/TGSI -> GUM1 function fixture.

Requires a configured Mesa build with matching native static objects. This does
not build Mesa, mutate its source/build, expose a GL API or start a GPU/guest.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
from apply_mesa_uniform_index_patch import AFTER as CHECKED_NIR_SHA

ROOT = Path(__file__).resolve().parents[1]


def main(control_flow=False, buffer_resources=False, shared_memory=False, memory_barriers=False,
         storage_atomics=False, texture_namespaces=False, image_ordering=False, atomic_counters=False,
         shared_publication=False, graphics_uniforms=False):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--build", type=Path, required=True, help="new private output directory")
    ap.add_argument("--sanitize", action="store_true")
    if control_flow or buffer_resources:
        ap.add_argument("--artifacts", type=Path,
                        help="new directory for actual validated submission inputs (CPU only)")
    ap.add_argument("--mesa-nir-lower-io", type=Path,
                    help="privately compile this checked Mesa nir_lower_io.c before libnir")
    args = ap.parse_args()
    out = args.build.resolve()
    out.mkdir(parents=True, exist_ok=False)
    mesa = Path(os.environ.get("OPENGPU_MESA_DIR", ROOT / "depends/mesa")).resolve()
    mb = Path(os.environ.get("OPENGPU_MESA_BUILD", ROOT / "depends/mesa-build")).resolve()
    drm = Path(os.environ.get("OPENGPU_DRM_INCLUDE", ROOT / "depends/aarch64-root/include")).resolve()
    if (mesa / "VERSION").read_text().strip() != "22.3.6":
        raise SystemExit("the pinned actual Mesa 22.3.6 source is required")
    checked_source = (args.mesa_nir_lower_io or mesa / "src/compiler/nir/nir_lower_io.c").resolve(strict=True)
    if hashlib.sha256(checked_source.read_bytes()).hexdigest() != CHECKED_NIR_SHA:
        raise SystemExit("stage the checked Mesa uniform index patch, or pass its exact private --mesa-nir-lower-io source")
    database = json.loads((mb / "compile_commands.json").read_text())
    cc = shlex.split(os.environ.get("CC", "cc"))
    cxx = shlex.split(os.environ.get("CXX", "c++"))
    commands = []
    san = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"] if args.sanitize else []

    def run(argv, label, env=None, input_text=None):
        argv = list(map(str, argv))
        commands.append({"argv": argv, "cwd": str(out), "label": label})
        (out / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        with (out / (label + ".log")).open("w") as log:
            subprocess.run(argv, cwd=out, check=True, stdout=log, stderr=subprocess.STDOUT, env=env,
                           input=input_text, text=input_text is not None)

    def configured_flags(relative):
        source = mesa / "src" / relative
        matches = [e for e in database if (Path(e["directory"]) / e["file"]).resolve() == source]
        if not matches:
            raise RuntimeError("missing actual configured compile command: " + str(source))
        entry = matches[0]
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
            if not flag.startswith("-") and (Path(entry["directory"]) / flag).resolve() == source:
                continue
            if flag.startswith("-I") and not Path(flag[2:]).is_absolute():
                flag = "-I" + str((Path(entry["directory"]) / flag[2:]).resolve())
            flags.append(flag)
        return flags

    objects = []
    # Private official compiler scaffold, with a single compiler-profile hook.
    # The product screen keeps CAP_COMPUTE=0/ESSL100. The official standalone
    # context otherwise omits compute combined limits and SSBO binding points.
    standalone = (mesa / "src/compiler/glsl/standalone.cpp").read_text()
    anchor = "   if (options->lower_precision) {"
    assert standalone.count(anchor) == 1
    standalone = standalone.replace(anchor, "   opengpu_frontend_compiler_limits(ctx);\n\n" + anchor)
    standalone = '#include "main/mtypes.h"\nextern void opengpu_frontend_compiler_limits(struct gl_context *);\n' + standalone
    private_standalone = out / "standalone-profile.cpp"
    private_standalone.write_text(standalone)
    official = ["compiler/glsl/standalone.cpp", "compiler/glsl/standalone_scaffolding.cpp",
                "compiler/glsl/ir_builder_print_visitor.cpp", "compiler/glsl/glcpp/pp_standalone_scaffolding.c",
                "mesa/main/extensions_table.c", "mesa/program/prog_parameter.c",
                "mesa/program/symbol_table.c", "mesa/program/dummy_errors.c"]
    for relative in official:
        source = private_standalone if relative.endswith("/standalone.cpp") else mesa / "src" / relative
        obj = out / (relative.replace("/", "_") + ".o")
        compiler = cxx if relative.endswith(".cpp") else cc
        run(compiler + configured_flags(relative) + ["-UNDEBUG"] + san + ["-MMD", "-MF", obj.with_suffix(".d"),
            "-c", source, "-o", obj], "compile-" + obj.stem)
        objects.append(obj)
    # Build the actual parser/callback/lib code with Werror. No parser, code
    # generator or driver body is replaced by the modeled OS/GEM primitives.
    inc = ["-I" + str(p) for p in (ROOT / "userspace/tests", ROOT / "userspace/gallium", ROOT / "userspace",
        ROOT / "driver", drm, mesa / "src/gallium/include", mesa / "src/gallium/auxiliary",
        mesa / "src", mesa / "include")]
    common = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
              "-DHAVE_ENDIAN_H", "-D_POSIX_C_SOURCE=200809L", "-ffunction-sections", "-fdata-sections"]
    primitives = ["-D" + name + "=cs_fixture_" + name for name in ("ioctl", "mmap", "munmap", "dup", "close")]
    if buffer_resources:
        from test_compute_uniform_flow import write_bodies
        kernel = out / "kernel"
        kernel.mkdir()
        write_bodies(kernel)
        obj = out / "cs_buffer_kernel.o"
        run(cc + common + san + ["-I" + str(ROOT / "driver/tests/compute_submit_stubs"),
            "-I" + str(ROOT / "driver"), "-I" + str(kernel), "-MMD", "-MF", obj.with_suffix(".d"),
            "-c", ROOT / "userspace/tests/cs_buffer_kernel.c", "-o", obj], "compile-cs_buffer_kernel")
        objects.append(obj)
    for relative, extra in [
        ("userspace/tests/graphics_uniform_flow.c" if graphics_uniforms else "userspace/tests/cs_atomic_frontend.c" if storage_atomics else "userspace/tests/cs_barrier_frontend.c" if memory_barriers else "userspace/tests/cs_shared_frontend.c" if shared_memory else "userspace/tests/cs_buffer_flow.c" if buffer_resources else
         "userspace/tests/cs_control_flow.c" if control_flow else "userspace/tests/cs_uniform_flow.c",
         ["-DOPENGPU_CS_TEXTURE_NAMESPACE"] if texture_namespaces else
         ["-DOPENGPU_CS_IMAGE_ORDERING"] if image_ordering else
         ["-DOPENGPU_CS_ATOMIC_COUNTER"] if atomic_counters else
         ["-DOPENGPU_CS_SHARED_PUBLICATION"] if shared_publication else []),
        ("userspace/tests/cs_graphics_unused_stubs.c", ["-DOPENGPU_CS_REAL_MESA_FORMATS"]),
        ("userspace/gallium/opengpu_context.c", ["-Wno-sign-compare"]),
        ("userspace/gallium/opengpu_screen.c", []),
        ("userspace/gallium/opengpu_cs_emit.c", []),
        ("userspace/gallium/opengpu_fs_emit.c", []),
        ("userspace/gallium/opengpu_vs_emit.c", []),
        ("userspace/pipe_opengpu.c", primitives), ("userspace/opengpu.c", primitives)]:
        if graphics_uniforms and relative == "userspace/tests/cs_graphics_unused_stubs.c":
            continue  # Graphics uses actual Mesa format/surface helpers.
        source = ROOT / relative
        obj = out / (source.stem + ".o")
        run(cc + common + inc + extra + san + ["-MMD", "-MF", obj.with_suffix(".d"), "-c", source, "-o", obj],
            "compile-" + source.stem)
        objects.append(obj)
    if graphics_uniforms:
        for relative in ("userspace/gallium/opengpu_native_uniform_emit.c",
                         "userspace/gallium/opengpu_native_graphics.c",
                         "userspace/opengpu_native_runtime.c", "driver/opengpu_native_frame.c"):
            source = ROOT / relative
            obj = out / (source.stem + ".o")
            run(cc + common + inc + san + ["-MMD", "-MF", obj.with_suffix(".d"), "-c", source, "-o", obj],
                "compile-" + source.stem)
            objects.append(obj)
    obj = out / "cs_glsl_frontend.o"
    run(cxx + configured_flags("compiler/glsl/standalone.cpp") + ["-UNDEBUG", "-Wall", "-Wextra", "-Werror",
        "-Wno-unused-parameter", "-I" + str(mesa / "src/compiler/nir"), "-I" + str(mb / "src/compiler/nir")] +
        inc + san + ["-MMD", "-MF", obj.with_suffix(".d"), "-c", ROOT / ("userspace/tests/graphics_uniform_frontend.cpp" if graphics_uniforms else "userspace/tests/cs_glsl_frontend.cpp"), "-o", obj],
        "compile-cs_glsl_frontend")
    objects.append(obj)
    obj = out / "checked-nir-lower-io.o"
    run(cc + configured_flags("compiler/nir/nir_lower_io.c") + ["-UNDEBUG"] + san +
        ["-MMD", "-MF", obj.with_suffix(".d"), "-c", checked_source, "-o", obj],
        "compile-checked-nir-lower-io")
    objects.append(obj)
    # One actual state-tracker object, not libmesa's duplicate standalone glue.
    state_tracker = mb / "src/mesa/libmesa.a.p/state_tracker_st_atom_constbuf.c.o"
    if not state_tracker.is_file():
        raise RuntimeError("actual st_bind_cs_ubos object is required")
    objects.append(state_tracker)
    storage_tracker = mb / "src/mesa/libmesa.a.p/state_tracker_st_atom_storagebuf.c.o"
    if not storage_tracker.is_file():
        raise RuntimeError("actual st_bind_cs_ssbos object is required")
    objects.append(storage_tracker)
    if atomic_counters:
        atomic_tracker = mb / "src/mesa/libmesa.a.p/state_tracker_st_atom_atomicbuf.c.o"
        if not atomic_tracker.is_file():
            raise RuntimeError("actual st_bind_hw_atomic_buffers object is required")
        objects.append(atomic_tracker)
    libraries = [mb / p for p in ("src/compiler/glsl/libglsl.a", "src/compiler/nir/libnir.a", "src/compiler/libcompiler.a",
        "src/compiler/glsl/glcpp/libglcpp.a", "src/gallium/auxiliary/libgallium.a", "src/util/libmesa_util.a",
        "src/util/format/libmesa_format.a", "src/c11/impl/libmesa_util_c11.a")]
    if texture_namespaces:
        # Actual mapping, buffer-view creation/cache/refcounts and format
        # conversion. A relocatable GC link selects their reachable sections
        # before archive resolution, avoiding unrelated full-context graphics
        # dependencies. The original Mesa objects remain untouched.
        texture_objects = [mb / ("src/mesa/libmesa.a.p/state_tracker_" + unit + ".c.o")
                           for unit in ("st_atom_texture", "st_sampler_view", "st_format",
                                        "st_context", "st_cb_texture")]
        texture_objects.append(mb / "src/mesa/libmesa.a.p/main_formats.c.o")
        for obj in texture_objects:
            if not obj.is_file():
                raise RuntimeError("actual Mesa texture state tracker object required: " + str(obj))
        selected = out / "actual-texture-state-tracker.o"
        run(cc + ["-r", "-nostdlib", "-Wl,--gc-sections", "-Wl,-u,st_update_compute_textures",
            "-Wl,-u,st_delete_texture_sampler_views", "-Wl,--wrap=st_finalize_texture",
            "-Wl,--wrap=st_get_texture_sampler_view_from_stobj"] + texture_objects +
            ["-o", selected], "select-actual-texture-state-tracker")
        objects.append(selected)
    if not all(p.is_file() for p in libraries):
        raise RuntimeError("configured real Mesa static libraries required")
    if shared_memory or shared_publication:
        from stage_mesa_shared_memory import stage
        source = stage(mesa, out / "private-mesa-source")
        obj = out / "nir_nir_to_tgsi.c.o"
        run(cc + configured_flags("gallium/auxiliary/nir/nir_to_tgsi.c") + ["-UNDEBUG"] + san +
            ["-MMD", "-MF", obj.with_suffix(".d"), "-c", source, "-o", obj], "compile-shared-NTT")
        archive = out / "libgallium.a"
        run(["ar", "-M"], "copy-gallium-archive",
            input_text="create %s\naddlib %s\nsave\nend\n" % (archive, libraries[4]))
        run(["ar", "r", archive, obj], "replace-only-shared-NTT")
        libraries[4] = archive
    binary = out / ("cs_atomic_frontend" if storage_atomics else "cs_barrier_frontend" if memory_barriers else "cs_shared_frontend" if shared_memory else "cs_buffer_flow" if buffer_resources else "cs_control_flow" if control_flow else "cs_uniform_flow")
    namespace_link = (["-Wl,--wrap=st_finalize_texture",
                       "-Wl,--wrap=st_get_texture_sampler_view_from_stobj"] if texture_namespaces else [])
    run(cxx + san + namespace_link + ["-Wl,--gc-sections", "-Wl,-t,-t", "-Wl,-Map=" + str(out / "link.map")] + objects +
        ["-Wl,--start-group"] + libraries + ["-Wl,--end-group"] +
        shlex.split(os.environ.get("OPENGPU_MESA_LINK_LIBS", "-lz -lexpat")) +
        ["-ldl", "-lm", "-pthread", "-o", binary], "link")
    sources = out / "glsl"
    sources.mkdir()
    if graphics_uniforms:
        from test_gallium_graphics_uniforms import write_sources
        write_sources(sources)
    elif shared_publication:
        from test_gallium_shared_publication import write_sources
        write_sources(sources)
    elif image_ordering:
        from test_gallium_image_ordering import write_sources
        write_sources(sources)
    elif atomic_counters:
        from test_gallium_atomic_counter import write_sources
        write_sources(sources)
    elif texture_namespaces:
        from test_gallium_texture_namespace import write_sources
        write_sources(sources)
    elif storage_atomics:
        from test_gallium_compute_atomics import write_sources
        write_sources(sources)
    elif memory_barriers:
        from test_gallium_compute_barriers import write_sources
        write_sources(sources)
    elif shared_memory:
        from test_gallium_compute_shared import write_sources
        write_sources(sources)
    elif buffer_resources:
        from test_gallium_buffer_flow import write_sources
        write_sources(sources)
    elif control_flow:
        from test_gallium_compute_control_flow import write_sources
        write_sources(sources)
    else:
        for name, local in (("full128", (8, 4, 4)), ("full125", (5, 5, 5)), ("old-profile", (8, 4, 4))):
            glsl = "#version 310 es\nprecision highp int;\nlayout(local_size_x=%d,local_size_y=%d,local_size_z=%d) in;\n" % local
            glsl += "uniform uvec4 defaults[256];\n"
            glsl += "".join("layout(std140,binding=%d) uniform Block%d { uvec4 v[1024]; } b%d;\n" % (24+i, i, i) for i in range(12))
            glsl += "".join("layout(std430,binding=%d) buffer Output%d { uint v[]; } o%d;\n" % (i, i, i) for i in range(4))
            glsl += "void main() { uint a=defaults[0].z ^ defaults[255].w;\n"
            glsl += "".join("a = a * 33u ^ b%d.v[0].x ^ b%d.v[1023].w;\n" % (i, i) for i in range(12))
            glsl += "uint index=gl_WorkGroupID.x * %du + gl_LocalInvocationIndex;\n" % (local[0]*local[1]*local[2])
            glsl += "".join("o%d.v[index]=a ^ index ^ %du;\n" % (i, i*0x10203) for i in range(4)) + "}\n"
            (sources / (name + ".comp")).write_text(glsl)
        prefix = "#version 310 es\nprecision highp int;\nlayout(local_size_x=8,local_size_y=4,local_size_z=4) in;\nlayout(std430,binding=0) buffer Output { uint v[]; } o;\n"
        (sources / "dynamic-array.comp").write_text(prefix + "uniform uvec4 defaults[256];\nvoid main(){o.v[gl_LocalInvocationIndex]=defaults[defaults[0].x].y;}\n")
        dynamic_prefix = "#version 310 es\nprecision highp int;\nlayout(local_size_x=8,local_size_y=4,local_size_z=4) in;\nuniform uvec4 defaults[256];\n"
        dynamic_prefix += "".join("layout(std430,binding=%d) buffer Output%d {uint v[];} o%d;\n" % (i,i,i) for i in range(4))
        dynamic_cases = {
            "default": ("", "defaults[o0.v[id]].y"),
            "block": ("layout(std140,binding=35) uniform Block {uvec4 v[1024];} b;\n", "b.v[o0.v[id]].y"),
            "masked": ("layout(std140,binding=35) uniform Block {uvec4 v[1024];} b;\n", "b.v[o0.v[id] & 1023u].y"),
            "nested": ("layout(std140,binding=35) uniform Block {uvec4 v[16][64];} b;\n", "b.v[o0.v[id]][o1.v[id]].z"),
            "base": ("layout(std140,binding=35) uniform Block {uvec4 pad[3];uvec4 v[1021];} b;\n", "b.v[o0.v[id]].w"),
            "signed": ("layout(std140,binding=35) uniform Block {uvec4 v[1024];} b;\n", "b.v[int(o0.v[id])].y"),
            "default-nested": ("", "defaults[o0.v[id]][o1.v[id]].z"),
            "default-masked": ("", "defaults[o0.v[id] & 255u].w"),
        }
        for name,(block,expr) in dynamic_cases.items():
            text = dynamic_prefix + block + "void main(){uint id=gl_LocalInvocationIndex;uint a=(" + expr + ") ^ defaults[255].w;"
            text += "".join("o%d.v[id]=a ^ %du;" % (i,i*0x10203) for i in range(4)) + "}\n"
            if name == "default-nested":
                text = text.replace("defaults[256]", "defaults[4][64]").replace("defaults[255].w", "defaults[3][63].w")
            (sources / ("index-" + name + ".comp")).write_text(text)
        (sources / "dynamic-block.comp").write_text(prefix + "uniform uint which;\nlayout(std140,binding=0) uniform Block {uvec4 v;} b[2];\nvoid main(){o.v[gl_LocalInvocationIndex]=b[which & 1u].v.x;}\n")
        full = (sources / "full128.comp").read_text()
        (sources / "too-many-defaults.comp").write_text(full.replace("defaults[256]", "defaults[257]").replace("defaults[255].w", "defaults[256].w"))
        (sources / "too-many-blocks.comp").write_text(full.replace("void main()", "layout(std140,binding=23) uniform Block12 {uvec4 v[1024];} b12;\nvoid main()").replace("uint index=", "a=a*33u ^ b12.v[0].x ^ b12.v[1023].w;\nuint index="))
        (sources / "too-large-block.comp").write_text(full.replace("Block0 { uvec4 v[1024]", "Block0 { uvec4 v[1025]").replace("b0.v[1023]", "b0.v[1024]"))
        body = "".join("a=a*33u ^ b%d.v[0].x ^ b%d.v[1023].w;\n" % (i, i) for i in range(12))
        (sources / "too-many-words.comp").write_text(full.replace("uint index=", body * 2 + "uint index="))
        swizzle = "#version 310 es\nprecision highp int;\nlayout(local_size_x=8,local_size_y=4,local_size_z=4) in;\nuniform uvec4 defaults;\nlayout(std140,binding=35) uniform Block {uvec4 v;} b;\n"
        swizzle += "".join("layout(std430,binding=%d) buffer Output%d {uint v[];} o%d;\n" % (i, i, i) for i in range(4))
        swizzle += "void main(){uvec4 v=defaults.wzyx ^ b.v;uint index=gl_LocalInvocationIndex;"
        swizzle += "".join("o%d.v[index]=v.%s;" % (i, "xyzw"[i]) for i in range(4)) + "}\n"
        (sources / "swizzles.comp").write_text(swizzle)
    env = os.environ.copy()
    env.pop("OPENGPU_COMPUTE_ARTIFACTS", None)
    env.pop("OPENGPU_CF_ARTIFACTS", None)
    env.pop("OPENGPU_BUFFER_ARTIFACTS", None)
    if shared_publication:
        env["OPENGPU_SHARED_PUBLICATION_QUALIFICATION"] = "1"
    if image_ordering:
        env["OPENGPU_IMAGE_ORDERING_QUALIFICATION"] = "1"
    if atomic_counters:
        env["OPENGPU_ATOMIC_COUNTER_QUALIFICATION"] = "1"
    if texture_namespaces:
        env["OPENGPU_TEXTURE_NAMESPACE_QUALIFICATION"] = "1"
    artifacts = args.artifacts.resolve() if (control_flow or buffer_resources) and args.artifacts else None
    if artifacts:
        artifacts.mkdir(parents=True, exist_ok=False)
        env["OPENGPU_BUFFER_ARTIFACTS" if buffer_resources else "OPENGPU_CF_ARTIFACTS"] = str(artifacts)
    if args.sanitize:
        # Existing Mesa objects are not sanitizer-instrumented and retain
        # process-global compiler singletons. Check new code for ASan/UBSan.
        env["ASAN_OPTIONS"] = "detect_leaks=0:abort_on_error=1"
        env["UBSAN_OPTIONS"] = "halt_on_error=1:print_stacktrace=1"
    run([binary, sources], "actual-acceptance", env)
    if artifacts:
        manifest = "format=1\n" + "".join(p.name + ".sha256=" + hashlib.sha256(p.read_bytes()).hexdigest() + "\n"
            for p in sorted(artifacts.iterdir()))
        (artifacts / "manifest.properties").write_text(manifest)
    (out / "result.json").write_text(json.dumps({"status": "PASS", "GPU_execution": False,
        "API_compute_enabled": False, "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
        "scope": "actual linked Mesa ESSL/NIR/TGSI, stage-tracker binding, original-GEM view planning and native-word CPU oracle; modeled OS/GEM, no production GNJ submit or GPU execution" if graphics_uniforms else
                 ("real Mesa GLSL/NIR/TGSI, callbacks/lib, extracted kernel resource ioctl/prepare/snapshot/scheduler; "
                  "independent CPU word/record oracle, modeled OS/GEM/VM/MMIO, no GPU or real page walk") if buffer_resources else
                 "real Mesa GLSL/NIR/TGSI + real callbacks/lib/kernel word validator; modeled kernel OS/GEM boundary"}, indent=2) + "\n")
    print(("Real GLSL graphics uniform frontend" if graphics_uniforms else "Real GLSL compute " + ("shared publication" if shared_publication else "atomic counters" if atomic_counters else "image ordering" if image_ordering else "texture namespaces" if texture_namespaces else "storage atomics" if storage_atomics else "memory barriers" if memory_barriers else "shared memory" if shared_memory else "buffer resources" if buffer_resources else "control-flow" if control_flow else "uniforms")) + " CPU acceptance PASS: " + str(out))


if __name__ == "__main__":
    main()
