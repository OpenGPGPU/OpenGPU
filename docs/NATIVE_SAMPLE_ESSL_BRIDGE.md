# Native sample ESSL compiler bridge

The private `opengpu_native_fs_emit` API compiles a bounded original Mesa TGSI
fragment stream directly to the native raw SoA ABI. It does not use or change
the legacy 288-byte/nine-array emitter, runtime lowerer, Gallium context or
public screen capabilities. It is not general ESSL support or GPU qualification.

The compiler contract retains 128 threads, sixteen varying slots, four MRTs and
the 512-instruction maximum. ABI6 has 75 input arrays; ABI7 has 77 and always
requires the geometry owner, even when its source does not read geometry.
Both always require the sample owner. Compiler configuration booleans describe
prerequisites; only actual device discovery and the protected submission path
can authorize them. Each array has a 512-byte stride. The complete raw prefix
has 93 or 95 arrays (47,616 or 48,640 bytes), including sixteen color-component
outputs, depth and control. `prefix_bytes` does not declare a uniform bank.

`fs_glsl_frontend.cpp` uses original Mesa 22.3.6 standalone ESSL310 compilation,
GLSL/NIR linking, fragment IO assignment, system-value lowering and NIR-to-TGSI.
Its private standalone scaffold sets a separable program, permitting a real
fragment-only link without inventing a vertex shader. The private context
enables sample-variable syntax; the product screen remains closed. System
values reach the emitter as actual `SAMPLEID`, `SAMPLEPOS` and `SAMPLEMASK`
TGSI declarations. ID and coverage are uint words, position is FP32. The API
also distinguishes signed conversions and truncates float-to-integer toward
zero. Generic inputs currently require smooth/center interpolation; coordinate
properties require native lower-left/half-integer convention. The consumer
must match that interpolation and coordinate contract in its actual GFS/frame.

Uniforms, UBOs, images, textures, storage, unsupported opcodes, flow, modifiers,
qualifiers and declarations are rejected. Register pressure can also reject a
program; full advertised layout capacity does not promise arbitrary live
temporary counts. The compiler validates packet extents before Mesa's TGSI
parser and leaves the result unchanged on failure. Unsupported features must
gain explicit native lowering and tests, never a fallback to the legacy ABI.

The stable qualification sources are
`userspace/tests/native_sample_essl/{sample,mask-depth,mrt,varying,signedness,truncation}.frag`.
The first four exercise sample ID/position/coverage, raw depth/sample-mask
output, all four MRTs, and the final varying slot respectively. The last two
distinguish high-bit unsigned/signed conversion and truncation from rounding.
Raw colors are FP32 words. Control uses the selected ABI6/7 definition: bit0
emit, bit1 depth override, bits5:2 color mask, bit6 sample-mask-written and
bits10:7 the low four shader-mask bits. No packed RGBA conversion occurs.

`native-fs-compile source.frag output.ognsf 6|7` writes an exclusive new file:
eight-byte `OGNSF001`, then fifteen little-endian u32 fields in this exact order:

```text
container_version=1, abi_version, input_arrays, array_stride, prefix_bytes,
used_sysvals, output_color_mask, writes_depth, writes_sample_mask,
requires_sample_owner, requires_geometry_owner,
thread_capacity, varying_slots, color_slots, code_words
```

Exactly `code_words` original little-endian instruction words follow at byte68,
with no trailing data. ABI version also defines the raw output/control layout.
This container records compiler output and does not assert discovery, actual
GS ownership, protected RX publication, shader execution or image correctness.
Source/container/instruction identity must be bound separately by the consumer.

Reproduce CPU compiler checks using an existing configured Mesa22.3.6 static
build. Resolve `CPU_IMAGE` to the local `gpu-gtk-ci-check:latest` image ID and
give `OUT` a fresh managed directory under the topic's `.artifacts`:

```sh
docker run --rm --init --memory=512m --memory-swap=512m --cpus=1 \
  --network=none --read-only \
  -v "$GPU_ROOT:$GPU_ROOT:ro" -v "$OUT:$OUT:rw" -w "$TOPIC" \
  -e PYTHONDONTWRITEBYTECODE=1 -e MESA_SHADER_CACHE_DISABLE=true \
  -e ASAN_OPTIONS=detect_leaks=0 -e TMPDIR="$OUT" -e XDG_CACHE_HOME="$OUT/cache" \
  -e OPENGPU_MESA_DIR="$GPU_ROOT/depends/mesa" \
  -e OPENGPU_MESA_BUILD="$GPU_ROOT/depends/mesa-build" \
  -e LD_LIBRARY_PATH="$GPU_ROOT/depends/aarch64-root/lib" \
  -e OPENGPU_MESA_LINK_LIBS="-l:libz.so.1 $GPU_ROOT/depends/aarch64-root/lib/libexpat.so.1.6.7" \
  "$CPU_IMAGE" python3 scripts/test_native_sample_essl.py --sanitize \
  --build "$OUT/build" --artifacts "$OUT/programs"
```

The script compiles the original Mesa scaffold privately and links existing
immutable libraries; it does not rebuild or patch shared Mesa. Its checks use
actual original tokens and the actual native shader validator, including wrong
sample/geometry gates, prefix truncation, old ABI/capacity, all truncated token
streams and malformed packet heads. Repeating original Mesa instructions tests
the instruction budget without authoring replacement TGSI. Separate original
ESSL resource/flow/qualifier/over-budget programs must be rejected. The additional
independent RV32/RVV word oracle checks all 128 lanes, every untouched array and
guards for each compiled source at 1x, 2x and 4x. It is a compiler oracle only.

Local ASan/UBSan CPU validation passed for all twelve compiled programs (six
original ESSL sources at ABI6 and ABI7), all thirty-six supplemental word-oracle
cases and the rejection checks above. The sequential single-CPU container
finished within its 512MiB memory/no-extra-swap limit, with no OOM. No larger
compiler, JVM or model build was launched for this work.

No actual full112 DUT has executed these sources. ABI7 compilation does not
prove a real GS owner. The protected RX consumer and any future actual DUT
qualification must check the original source, exact container/code bytes,
actual112 discovery and the live native owner independently. Keep integration
qualification draft until those actual execution results exist. Delete ended
private builds, NIR/TGSI dumps, containers and artifacts after their consumers
finish; preserve only source, final results and reproduction commands.
