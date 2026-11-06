# H8 bounded Linux buffer qualification

The 16-item H8 chain passed actual Linux/DRM execution and the offline
PFN/PTE/accepted-AXI join on the shared two-CU ResourceGuest. All eleven parents
completed, including the expected bounds fault, drain and healthy reuse.
The final observer state was `COMPLETE_DRAINED`. This qualifies the small
workload; full H8 and physical implementation closure remain open.

Each parent uses one workgroup with local size 8×2×1. Full allocations remain:
eight 128 MiB BOs, 32 independent 4 MiB texture BOs and twelve 16 KiB UBO sources,
plus a distinct 128 MiB image replacement in the second epoch. The chain retains
32 sampler bindings, four SSBOs, four independent images and default uniforms
plus twelve UBOs. Sampler and SSBO views span their BOs; the rebound image0 uses
the checked 64-byte-offset subview. Item N−1 still accesses each active view's
last valid element. Each parent checks 82,560 prefix/tail/guard words, increasing
to 84,624 after replacement; allocations are not exhaustively scanned.

The offline gate binds original process identity, reviewed argv/environment,
immutable logs, actual 3 GiB DTB and 1536 MiB CMA reservation, all public PFNs,
full PTE mappings, accepted reads and strobed writes with successful responses
before completion. Both producer snapshots contain fifty read-only private
pages each, distinct from public UBO upload sources. The producer's 13 constant
buffers and the texture/image parents are separate dispatches. The observer
records only word0 of each 32-byte beat, so full descriptor or payload
reconstruction is not claimed. This single-workgroup test does not require or
establish two-CU concurrency.

The largest parent took 33,799 service cycles from accepted SUBMIT to POP; all
eleven intervals total 265,749 cycles. At the requested 1 GHz estimate these are
33.799 µs and 265.749 µs. This is cycle conversion, not a QEMU guest-clock setting
or complete application latency; the supervised host run took 444.318 seconds.
The original 128-item run remains FAIL at the texture-parent software watchdog.

The candidate uses a narrow Mesa 22.3.6 patch: compute-stage, 32-bit SSA vec4
buffer `TXF` destinations keep only channels read by NIR uses. Coordinates,
source mappings, register/phi paths, queries, graphics and nonbuffer operations
retain their original handling. Real NIR and ESSL controls cover `.x`, `.y`,
`.zw`, mixed swizzles, RGBA and conditional uses; fallback/query TGSI bytes
remain identical. Independent CPU execution checks their output words and all
eleven H8 segments. The six candidate programs contain
395/425/425/431/460/115 native words; the 512-word and 1024-component default
uniform gates remain unchanged.
An initial fixture compile rejected existing Mesa header initializers; it now
uses Mesa's existing `-Wno-missing-field-initializers` setting.

The normal builder defaults to 16 items with the existing Mesa objects. It does
not automatically apply the mask optimization. The successful guest used the
privately patched candidate object, unchanged RTL/model/module, and the private
[4 KiB PFNMAP observation kernel](h7-bounded-linux-resource-proof.md).
That kernel retains the privileged pagemap-file-opener authorization; this is
not evidence from stock Linux. `CAP_COMPUTE` remains zero. The first offline
pass rejected a missing `INDEX.app`; the separate H8 verifier supports only the
explicit compact 16-item schema without changing captured files or H7/common.

CPU reproduction requires the configured AArch64 Mesa 22.3.6 build and an
existing privately staged checked-uniform source. Run the following in a bounded
container with one CPU, 768 MiB RAM, no extra swap or network, a 120-second inner
deadline and 140-second outer deadline. `BASE16` and `MASK16` must be new private
output directories outside the inputs.

```sh
python3 scripts/build_gallium_compute_runtime.py \
  --mesa-source "$MESA" --mesa-build "$MESA_BUILD" \
  --mesa-nir-lower-io "$CHECKED_IO" --glsl-buffer \
  --run-preflight --sanitize-preflight --out "$BASE16"
python3 scripts/reproduce_mesa_buffer_txf_mask.py \
  --mesa-source "$MESA" --mesa-build "$MESA_BUILD" \
  --runtime-build "$BASE16" --out "$MASK16"
```

The incremental helper privately copies the thin archive/member closure,
recompiles one Mesa object and the control fixture, verifies unchanged global
symbols and reuses the app/frontend objects. It exposes the checks performed by
the qualified frozen worker; the newly published helper was checked by metadata
planning, not executed again. Larger local qualification requires
`--buffer-qualification-items 128` when building and matching explicit sources:

```sh
python3 scripts/gallium_buffer_qualification.py \
  --source userspace/examples/glsl_buffer --out "$SOURCES128" --items 128
"$APP128" --sources "$SOURCES128" --qualification-items 128
```

The later [scalar-query emitter](h8-scalar-buffer-txq.md) has a different H8
golden. To replay this historical 395/425/425/431/460/115-word result, keep its
original frozen checker or extract the qualification sources from the original
`f58edaca48ee910d461b1a030d11d215a992c305` revision. Set `CHECKER` to that
directory's `scripts/qualification/check_h8.py`, and `RUN`, `DTB` and `DTC` to
the original files. This command performs no guest or model execution:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 "$CHECKER" \
  --launch-result "$RUN/launch-result.json" \
  --original-terminal "$RUN/original-terminal.json" \
  --serial "$RUN/serial.log" --stderr "$RUN/stderr.log" --proof "$RUN/proof" \
  --dtb "$DTB" --dtc "$DTC" \
  --guest-profile scripts/qualification/resource_guest_profile.py \
  --expected-common-sha 2a18b803254e6a73df614008e6cb2bbe0d2bab0f14ef66cda1198f9152fb8bbe \
  --out "$RUN/qualification-result.json"
PYTHONDONTWRITEBYTECODE=1 python3 scripts/qualification/selfcheck_h8.py
```

Use a new result path when replaying existing evidence. These offline checks
add no rendering, nightly or hardware-build lane.
