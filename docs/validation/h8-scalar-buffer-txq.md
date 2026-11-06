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
by this optimization. Hardware fault retirement, cancellation, drain and cycle
performance are not established by a CPU word oracle.

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

The repository H8 golden now identifies the scalar-query programs. The
[earlier bounded Linux result](h8-bounded-linux-buffer-proof.md) used the old
query lowering and remains a separate historical result. Its original frozen
checker, software and evidence are not rewritten to match the new word counts.
