# P12 compute resource coexistence

Each case uses one 128-thread compute launch with four SSBOs and four R32UI
buffer images. The counter and shared cases each use 12 UBOs. The counter
case increments all eight atomic counters in the last 32 bytes of their GEM.
The shared case reads across warps and exercises the last word of 16 KiB of
workgroup shared memory. The texture case binds 16 buffer textures at Mesa
API units 16–31 and verifies the last word of every texture, SSBO and image view.

All three endpoint shaders passed Mesa compilation, the production Linux
ioctl CPU capture, and independent descriptor and page-table checks. The
shared and counter endpoint cases passed the complete DUT. P12 remains open
at **11/40**.

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
| `p12-counter-coexist` | 128 | none | 12, bindings 24..35, `uvec4 v[1024]`, endpoint `v[1023].w` | 4, 8192-byte views, bindings 0..3 | 4 R32UI buffer images, 512-byte views, bindings 0..3 | 8 at GEM offset 8160 | `value = atomicCounterIncrement(counters[id & 7]) ^ UBO_seed` written to all 4 SSBO and 4 image outputs with per-unit XOR constants; `memoryBarrierBuffer()` / `memoryBarrierImage()` / `memoryBarrierAtomicCounter()`. 487 native words. The oracle checks one old-value multiset per counter in each output and all eight final counter values. |
| `p12-shared-coexist` | 128 | 16 KiB, `shared uint data[4096]` | 12, bindings 24..35, `uvec4 v[1024]`, endpoint `v[1023].w` | 4, 8192-byte views, bindings 0..3 | 4 R32UI buffer images, 512-byte views, bindings 0..3 | none (compiler-profile incompatibility) | `data[id]=seed^id` and `data[3968+id]=id*3`, then `memoryBarrierShared();barrier();` and `peer=data[id^16]^data[3968+id]`. The result is written to all 4 SSBO and 4 image outputs with per-unit XOR constants; `memoryBarrierBuffer()` / `memoryBarrierImage()`. 399 native words with exact-array output checks. |
| `p12-texture-coexist` | 128 | none | none | 4, 8192-byte views, bindings 0..3 | 4 R32UI buffer images, 512-byte views, bindings 0..3 | none | XORs the values from the same coordinate in all 16 textures. Each lane reads texel `2047 ^ id`, writes SSBO word `1920 + id` and writes image texel `id`. The program is 468 native words. |

The counter and shared cases derive the UBO seed from each block's `v[0].x` and
`v[1023].w`. The texture case exercises the last word of every texture, SSBO,
and image view. The shader compiled through the original Mesa/Gallium path and
the production ioctl accepted all resources in a CPU capture. The independent
capture check passed for the command registers, all 16 texture descriptor
chains and page mappings, four SSBOs, and four image views.

## Complete host execution

The assertion-enabled `two-cu-vga60-owner-v2-image-float-stage` Verilator
compute executor ran source `636caa78af084145a44c8b925711167aa9858bc4`. The
following cases passed original Mesa compilation, production Linux ioctls,
returned-byte and physical guard checks, and observed retirement and context
cleanup. Each case used its own DUT, RAM and single initial reset.

| Case | Scope |
| --- | --- |
| `p12-shared-coexist` | 16 KiB shared endpoint, adjacent-warp reads and both endpoints of all 12 UBOs. |
| `p12-counter-coexist` | Sixteen increments per slot across all eight counters, including GEM end and wraparound. Each output passed independent old-value multiset checks. |
| `uniforms` | 1024 default uniform components, 12 UBOs and original SSBO output bytes. |
| `mixed-atomic-r32f-swap-swap-storage-first-endpoint` | SSBO and R32F image atomics alias the same original GEM word at the allocation end. |

The full-DUT texture result is pending.

## Reproduce

Against a prepared float owner (see
[COMPLETE_OWNER_COMPUTE_EXECUTOR.md](COMPLETE_OWNER_COMPUTE_EXECUTOR.md)):

```sh
OWNER_OUTPUT=$PWD/.artifacts/p12-float-owner
for case in p12-counter-coexist p12-shared-coexist p12-texture-coexist uniforms \
  mixed-atomic-r32f-swap-swap-storage-first-endpoint; do
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
  healthy reuse.
- The product clock and FPS target remains 2 CUs at 640×480 and 60 FPS.
