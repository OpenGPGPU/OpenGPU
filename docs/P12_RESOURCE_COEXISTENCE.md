# P12 compute resource coexistence

Each case uses one 128-thread compute launch with four SSBOs and four R32UI
buffer images. The counter and shared cases each use 12 UBOs. The counter
case increments all eight atomic counters in the last 32 bytes of their GEM.
The shared case reads across warps and exercises the last word of 16 KiB of
workgroup shared memory. The texture case binds 16 buffer textures at Mesa
API units 16–31 and verifies the last word of every texture, SSBO and image view.

All three endpoint shaders passed Mesa compilation, the production Linux
ioctl CPU capture, independent descriptor and page-table checks, and complete
DUT execution. P12 remains open at **11/40**.

## Compiler profile boundary

The combined shader reuses an unchanged buffer-texture coordinate packet
across sampler instructions. The original Mesa frontend and Linux ioctl CPU
capture accepted the 455-word shader with 128 local threads, 16 KiB shared
memory, 16 texture units, four SSBOs, four buffer images, eight atomic counters,
12 UBOs and 1016 numeric default-uniform components. The four images consume
the other eight components of the Mesa linker's 1024-component budget.
The separate `uniforms` case exercises all 1024 numeric components.

The compute-stage texture target is 16 units. The project-wide combined target
remains 96. The combined admission fixture rejects a storage slot supplied as
a texture, a texture slot supplied as storage or image, and a writable counter
window backed by a texture, SSBO or image GEM. The production Linux ioctl
rejected all six requests and accepted the valid launch in the same context.
The expanded rejection set passed CPU capture and independent descriptor and
page-table checks. The combined launch passed the assertion-enabled two-CU float DUT at source
`90ffe52c3adde36e88c29e7d3949974412271046`. The independent oracle verified
the counter old-value streams, final counter words, mirrored SSBO and image
endpoints, all remaining physical bytes, and observed context cleanup.

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
| `p12-texture-coexist` | 16 texture bindings, four SSBOs and four image views in one launch. The last word of every view and all other physical bytes passed independent checks. |
| `p12-counter-coexist` | Sixteen increments per slot across all eight counters, including GEM end and wraparound. Each output passed independent old-value multiset checks. |
| `uniforms` | 1024 default uniform components, 12 UBOs and original SSBO output bytes. |
| `mixed-atomic-r32f-swap-swap-storage-first-endpoint` | SSBO and R32F image atomics alias the same original GEM word at the allocation end. |

Two further R32F buffer-image cases passed on the same factory. The write
permission fault preserved physical guards. The delayed write response reset
returned cancellation and preserved the expected partial writes. Each recovered
with the same command ID on retained RAM and passed final context cleanup.

## Reproduce

Against a prepared float owner (see
[COMPLETE_OWNER_COMPUTE_EXECUTOR.md](COMPLETE_OWNER_COMPUTE_EXECUTOR.md)).
This run prepared RTL with native Java 21, Espresso on `PATH` and
`--java-heap 27G`.

```sh
OWNER_OUTPUT=$PWD/.artifacts/p12-float-owner
qualify_p12() {
  PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/qualify_compute_owner.py \
    --executor "$OWNER_OUTPUT/executors/compute/executor" \
    --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
    --executor-checker scripts/prepare_owner_executor.py \
    --executor-checker-python "$(command -v python3)" \
    --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
    --timeout 7200 "$@"
}
for case in p12-counter-coexist p12-shared-coexist p12-texture-coexist p12-combined-coexist uniforms \
  mixed-atomic-r32f-swap-swap-storage-first-endpoint; do
  qualify_p12 --case "$case" || exit
done
qualify_p12 --case image-atomic-buffer-r32f --image-permission-recovery write || exit
qualify_p12 --case image-atomic-buffer-r32f --image-reset-recovery write
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

- The 512-word Mesa emitter and driver validator limit applies to each shader.
  The combined case reads the last word of each UBO. The dedicated counter
  and shared cases also read each UBO's first word.
- The same-GEM alias, write permission and delayed write-response reset are
  scoped to the tested SSBO and buffer-image cases. The complete resource set
  still needs the namespace, alias, permission and cancel/reset matrix.
- The product clock and FPS target remains 2 CUs at 640×480 and 60 FPS.
