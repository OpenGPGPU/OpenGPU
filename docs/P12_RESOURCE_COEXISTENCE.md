# P12 compute resource coexistence

The counter and shared cases each passed one original Mesa launch on the
assertion-enabled float DUT with 128 local threads, 12 UBOs, four SSBOs, and four
buffer images. The counter case binds eight atomic counters. The shared case
allocates 16 KiB of workgroup shared memory.

The texture case exercises 16 compute-stage buffer textures with four SSBOs and
four buffer images in one 128-thread launch. The float DUT run is pending. P12
remains open at **11/40**.

## Compiler profile boundary

The Mesa compiler frontend cannot lower atomic counters and 16 KiB of shared
memory in the same shader. With both present, `gl_nir_lower_atomics` transforms
the shader so that `emit_shared` fails. A second constraint is the uniform
budget: 1024 default uniforms plus 8 atomic counters exceed the profile's
uniform budget for the same program.

The counter and shared cases use separate shaders because of this compiler
limit. The compute-stage texture target is 16 units. The project-wide combined
target remains 96.

## Corpus

| Case | Threads | Shared | UBO | SSBO | Images | Counters | Shader / oracle |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `p12-counter-coexist` | 128 | none | 12, bindings 24..35, `uvec4 v[1024]`, endpoint `v[1023].w` | 4, 8192-byte views, bindings 0..3 | 4 R32UI buffer images, 512-byte views, bindings 0..3 | 8, binding 0 offset 0, index 7 | `value = atomicCounterIncrement(counters[7]) ^ UBO_seed` written to all 4 SSBO and 4 image outputs with per-unit XOR constants; `memoryBarrierBuffer()` / `memoryBarrierImage()` / `memoryBarrierAtomicCounter()`. Native program ≤512 words (measured 449 words via compile probe). Counter outputs checked as multisets. |
| `p12-shared-coexist` | 128 | 16 KiB, `shared uint data[4096]` | 12, bindings 24..35, `uvec4 v[1024]`, endpoint `v[1023].w` | 4, 8192-byte views, bindings 0..3 | 4 R32UI buffer images, 512-byte views, bindings 0..3 | none (compiler-profile incompatibility) | `data[id]=seed`, `memoryBarrierShared();barrier();`, `peer=data[(id^16)]`; peer value written to all 4 SSBO and 4 image outputs with per-unit XOR constants; `memoryBarrierBuffer()` / `memoryBarrierImage()`. Outputs checked as exact arrays. |
| `p12-texture-coexist` | 128 | none | none | 4, 8192-byte views, bindings 0..3 | 4 R32UI buffer images, 512-byte views, bindings 0..3 | none | XORs the values from the same coordinate in all 16 textures. Each lane reads texel `2047 ^ id`, writes SSBO word `1920 + id` and writes image texel `id`. The program is 468 native words. |

The counter and shared cases derive the UBO seed from each block's `v[0].x` and
`v[1023].w`. The texture case exercises the last word of every texture, SSBO,
and image view. The shader compiled through the original Mesa/Gallium path and
the production ioctl accepted all resources in a CPU capture. The independent
capture check passed for the command registers, all 16 texture descriptor
chains and page mappings, four SSBOs, and four image views.

## Execution result

Execution source SHA `178dbb9457aca6524c337034c2b59ec6a103d96b` on the
assertion-enabled `two-cu-vga60-owner-v2-image-float-stage` Verilator compute
executor. Both cases passed independent returned-byte/guard oracles (multiset
for counter outputs, exact array for shared), full112 float discovery,
GAM1/GSM1/GUM1/GUB1/GUR1 descriptors, PTE/MMU, cleanup ACK, and unchanged
source/model postchecks, each on one DUT/RAM/single reset:

```text
original Mesa compute p12-counter-coexist: actual full-DUT returned bytes and context cleanup PASS
original Mesa compute p12-shared-coexist: actual full-DUT returned bytes and context cleanup PASS
```

## Reproduce

Against a prepared float owner (see
[COMPLETE_OWNER_COMPUTE_EXECUTOR.md](COMPLETE_OWNER_COMPUTE_EXECUTOR.md)):

```sh
OWNER_OUTPUT=$PWD/.artifacts/p12-float-owner
for case in p12-counter-coexist p12-shared-coexist p12-texture-coexist; do
  PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/qualify_compute_owner.py \
    --executor "$OWNER_OUTPUT/executors/compute/executor" \
    --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
    --executor-checker scripts/prepare_owner_executor.py \
    --executor-checker-python "$(command -v python3)" \
    --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
    --case "$case" --timeout 7200 || exit
done
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_p12_counter.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_p12_shared.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_p12_texture.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py
git diff --check
```

## Limits

- The 512-word Mesa emitter and driver validator limit applies to each
  shader. A straight-line shader with 16 texture fetches plus the complete
  12-UBO and eight-counter workload exceeds that limit.
- Namespace isolation, resource aliases and permissions still need a complete
  host matrix across the full resource set, including cancel/reset and
  healthy reuse. The 16 KiB shared endpoint also remains to be exercised.
- The product clock and FPS target remains 2 CUs at 640×480 and 60 FPS.
