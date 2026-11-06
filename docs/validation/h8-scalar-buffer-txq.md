# Scalar buffer texture-size queries

The compute TGSI emitter lowers a static buffer `TXQ` at LOD zero to one scalar
size query followed by a vector broadcast. This replaces five native prefix
instructions with three. The query still validates the selected descriptor
when only the levels component is written; the remaining components retain
their buffer values `(size, 0, 0, 1)`. Resource bindings, immutable descriptor
ownership and the 512-word sandbox are unchanged.

This applies to the existing straight-line compute compiler profile. Resource
operations in control-flow programs remain rejected, the prologue sets VLMAX,
and the dispatcher launches only nonempty warps. Arbitrary empty execution
masks or VL0 are not equivalent scalar/vector query cases and are not covered
by this optimization. The CPU word oracle alone does not establish hardware
fault retirement, cancellation, drain or cycle performance; the bounded Linux
observation below is separate evidence.

With the separately qualified buffer-TXF component-mask lowering, the six
16-item ESSL programs change from 395/425/425/431/460/115 native words to
395/423/423/429/460/113. A CPU experiment used the original real Mesa frontend
to produce binary TGSI, checked the baseline's complete native words and
resource/reflection metadata, then allowed only the four query-prefix changes.
The original token files were unchanged. Both word decoders checked all eleven
buffer-chain segments and their sparse payload/guard words. This is CPU
semantic evidence, not a new GPU or application-throughput result.

The existing `mesa_buffer_txf_mask.c` fixture also exercises statically bound
units 0 and 31, component write masks, 1/15/16 active items, zero/odd packed v0,
subsequent grid-argument loads and descriptor rejection for a levels-only
query. Its callbacks use the real C snapshot validator and a CPU-only failure
escape; they do not emulate hardware trap or memory-drain behavior. The shared
word VM adds only the emitted scalar size subset and retains the original
control-flow decoder.

Final CPU validation passed 23 stages, compiling 12 affected C objects with
ASan/UBSan while reusing unchanged shared Mesa/frontend objects without
instrumentation. Both original and masked NTT passed 19 query controls,
10 NIR cases and six ESSL cases; the updated oracle passed all 11 chain
segments, and the H8 selfcheck passed 26 cases. These checks establish CPU
compiler/callback/validator semantics only.

The updated six-program chain also passed actual Linux/DRM execution and the
strict offline PFN/PTE/accepted-AXI join on the shared full-service two-CU
ResourceGuest. It uses the same full BO allocations and views as the
[earlier 16-item qualification](h8-bounded-linux-buffer-proof.md), one workgroup
of 8×2×1 items per parent, the read-only uniform module and the private
[4 KiB PFNMAP observation kernel](h7-bounded-linux-resource-proof.md), rather
than stock Linux. All eleven parent checks passed, including the expected
bounds fault with no GPU write, fresh second-epoch reuse, both 50-page read-only
uniform snapshots and final `COMPLETE_DRAINED`.

One baseline/candidate observation gave these accepted SUBMIT-to-POP intervals:

| Interval sum | Earlier query lowering | Scalar query | Reduction |
| --- | ---: | ---: | ---: |
| Six texture parents | 199,314 cycles | 194,216 cycles | 2.56% |
| All eleven parents | 265,749 cycles | 259,992 cycles | 2.17% |

At the assumed 1 GHz, the sums convert to 199.314→194.216 µs and
265.749→259.992 µs. These intervals include guest polling, observation and
software delay through POP; they are not pure GPU latency or application FPS.
The unchanged producer intervals increased by 77/68 cycles and image intervals
by 94/24 cycles across the two epochs; the expected-fault interval changed from
2,119 to 1,197 cycles. This single pair does not establish repeatability or
attribute every cycle change to the query prefix.

Actual MMIO texture roots, SATP/PTE translations and the observed root word-8
bindings pointer identify three metadata addresses per query parent: two
64-byte root blocks and the selected 64-byte binding. Each of seven parents
issued fifteen fewer accepted reads at each address, with all successful
responses before its POP: 315 fewer AR bursts and 630 fewer R beats in total.
Overall reads fell by 316 AR/632 R; the additional fault-parent 1 AR/2 R reduction
is unclassified. Public endpoint traffic structure and accepted AW/W/B totals
stayed unchanged.
Only the captured root words and address/response joins are used here; complete
descriptor or payload reconstruction is not claimed. The original 128-item
watchdog failure remains separate; full H8, general GL/conformance and two-CU
concurrency are not qualified by this serial workload.

Run the affected callback and control-flow regressions in the prepared Linux
CPU environment, reusing the configured Mesa libraries and checked uniform
source. Each output directory must be new:

```sh
python3 scripts/test_gallium_buffer_flow.py --build "$NEW_BUFFER_CPU" \
  --mesa-nir-lower-io "$CHECKED_IO" --sanitize
python3 scripts/test_gallium_compute_control_flow.py --build "$NEW_CF_CPU" \
  --mesa-nir-lower-io "$CHECKED_IO" --sanitize
```

For a runtime built with this emitter, generate and validate the six-program
exports using the matching updated CPU oracle:

```sh
"$APP" --preflight "$NEW_EXPORTS16" "$SOURCES16" --qualification-items 16
"$CPU_ORACLE" "$NEW_EXPORTS16" --qualification-items 16 --texture-components 1
```

The permanent NIR/ESSL controls remain available through
`scripts/reproduce_mesa_buffer_txf_mask.py`; its runtime build input must use
the matching emitter and word oracle. That complete reproducer also rebuilds
the one private Mesa mask object. An incremental local check can reuse an
already qualified mask object and rebuild only the changed C fixtures/emitter.
No RTL model, guest, nightly lane or large-frame rendering is required for
these CPU regressions.

For a new actual run, use the shared
[one-boot supervisor](../../scripts/qualification/resource_guest_once.py) and
the documented private-kernel/3 GiB setup, select `app='h8'`, and include the
updated app, six 16-item sources and matching H8 qualification helpers in the
reviewed configuration. The supervisor supplies the original identity,
terminal, launch-result and immutable proof binding; QEMU exit zero alone is
not a semantic pass. Replay that new run with this revision's checker and
golden, setting `COMMON_SHA` to the reviewed `check_guest_common.py` SHA-256 and
using a new output path:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/qualification/check_h8.py \
  --launch-result "$RUN/launch-result.json" \
  --original-terminal "$RUN/original-terminal.json" \
  --serial "$RUN/serial.log" --stderr "$RUN/stderr.log" --proof "$RUN/proof" \
  --dtb "$DTB" --dtc "$DTC" \
  --guest-profile scripts/qualification/resource_guest_profile.py \
  --expected-common-sha "$COMMON_SHA" --out "$NEW_QUALIFICATION_RESULT"
PYTHONDONTWRITEBYTECODE=1 python3 scripts/qualification/selfcheck_h8.py
```

The repository H8 golden now identifies the scalar-query programs. The
[earlier bounded Linux result](h8-bounded-linux-buffer-proof.md) used the old
query lowering and remains a separate historical result. Its original frozen
checker, software and evidence are not rewritten to match the new word counts.
