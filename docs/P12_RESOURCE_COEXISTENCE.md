# P12 resource coexistence — first increment

First P12 compute resource-coexistence increment on the assertion-enabled float
DUT: two original-Mesa single-launch cases on one DUT/RAM/single reset, covering
128 local threads, 16 KiB shared, 12 UBO with endpoint, 4 SSBO, 4 buffer images
and 8 atomic counters in one launch across the pair. These resources are
simultaneously present **across the two cases, not in one shader** (see
[Compiler profile boundary](#compiler-profile-boundary)).

This is a first increment; it does **not** close P12. Count stays **11/40** and
P12 remains open. The 32-texture combined launch, the end-address / namespace /
alias / permission matrices, cancel/reset/robustness with the full resource set,
and product clock/FPS keep their own gates.

## Overview

Each case is one compute launch through the original Mesa → ioctl → complete
float DUT path, with one DUT/RAM and one reset. Both cases bind a 128-local-thread
group together with the full non-texture resource set discovered by the float
owner, and are checked by independent returned-byte/guard oracles rather than by
component or CPU peers. The two cases split the P12 first-increment coverage
along the only axis the compiler profile cannot combine:

- the counter case exercises 8 atomic counters together with the storage/image/
  uniform set, but no shared memory;
- the shared case exercises 16 KiB of workgroup shared memory together with the
  same storage/image/uniform set, but no atomic counters.

Together they give full-simultaneity evidence for 128 threads, 16 KiB shared,
12 UBO/UBO endpoint, 4 SSBO, 4 image and 8 counter on the real qualifier; no
claim is made that all of these coexist inside one shader binary.

## Compiler profile boundary

The Mesa compiler frontend cannot lower atomic counters and 16 KiB of shared
memory in the same shader. With both present, `gl_nir_lower_atomics` transforms
the shader so that `emit_shared` fails. A second constraint is the uniform
budget: 1024 default uniforms plus 8 atomic counters exceed the profile's
uniform budget for the same program.

Consequently a single shader holding both 16 KiB shared and 8 atomic counters
cannot be compiled in this profile, and the first increment uses two
complementary single-launch cases instead. This is an honest limit of the
increment: coverage is simultaneous across the two qualified launches, not
within one compiled shader. The 32-texture combined launch remains a separate,
unclosed P12 requirement.

## Corpus

| Case | Threads | Shared | UBO | SSBO | Images | Counters | Shader / oracle |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `p12-counter-coexist` | 128 | none | 12, bindings 24..35, `uvec4 v[1024]`, endpoint `v[1023].w` | 4, 8192-byte views, bindings 0..3 | 4 R32UI buffer images, 512-byte views, bindings 0..3 | 8, binding 0 offset 0, index 7 | `value = atomicCounterIncrement(counters[7]) ^ UBO_seed` written to all 4 SSBO and 4 image outputs with per-unit XOR constants; `memoryBarrierBuffer()` / `memoryBarrierImage()` / `memoryBarrierAtomicCounter()`. Native program ≤512 words (measured 449 words via compile probe). Counter outputs checked as multisets. |
| `p12-shared-coexist` | 128 | 16 KiB, `shared uint data[4096]` | 12, bindings 24..35, `uvec4 v[1024]`, endpoint `v[1023].w` | 4, 8192-byte views, bindings 0..3 | 4 R32UI buffer images, 512-byte views, bindings 0..3 | none (compiler-profile incompatibility) | `data[id]=seed`, `memoryBarrierShared();barrier();`, `peer=data[(id^16)]`; peer value written to all 4 SSBO and 4 image outputs with per-unit XOR constants; `memoryBarrierBuffer()` / `memoryBarrierImage()`. Outputs checked as exact arrays. |

Both cases derive the UBO seed from each block's `v[0].x` and `v[1023].w`
endpoint, so the endpoint is exercised as part of the oracle rather than only
bound. Neither case includes texture units.

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
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/qualify_compute_owner.py \
  --executor "$OWNER_OUTPUT/executors/compute/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$(command -v python3)" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case p12-counter-coexist --timeout 7200

OWNER_OUTPUT=$PWD/.artifacts/p12-float-owner
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/qualify_compute_owner.py \
  --executor "$OWNER_OUTPUT/executors/compute/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$(command -v python3)" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case p12-shared-coexist --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_p12_counter.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_p12_shared.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py
git diff --check
```

## Limits

- This first increment does **not** close P12; count stays **11/40** and P12
  remains open.
- The 32 texture units must still be exercised in the combined launch; neither
  case here binds texture units.
- The end-address, namespace, alias and permission matrices over the full
  resource set remain unclosed.
- Cancel/reset/robustness with the full resource set remains unclosed.
- Shared and atomic-counter coexistence in one shader is blocked by the compiler
  profile described above; the two-case split is a coverage limit, not a single
  combined shader.
- Product clock and FPS (2 CUs, 640x480, 60 FPS) keep their own gates.
