# Bounded Yosys OPT strategy comparison

The complete protected-owner host timed out before mapping; its physical clock
remains open. The ordinary post-FSM `opt` repeats mux-tree, reduction, merge,
flip-flop, cleanup and expression passes until the design stops changing.
`opt -fast` instead repeats expression, merge, flip-flop and cleanup passes
while flip-flop optimization changes the design. It can retain more logic, so
functional equivalence and measured completion must precede any whole-host
strategy change.

Two complete components from the frozen 96-word owner RTL passed sequential
ordinary/fast equivalence with one CPU, 1 GiB and no additional swap:

| Component | Ordinary invocation | Fast invocation | Result |
|---|---:|---:|---|
| `RegisteredQueue2_3` | 0.280 s | 0.297 s | Equivalent; equal RTLIL sizes |
| `TessellationImageBankMux` | 1.665 s | 1.811 s | Equivalent; equal RTLIL sizes |

These times include container startup, reading the common RTLIL, optimization
and writing its result. They do not demonstrate a speed improvement or mapped
timing. The complete 2048+2048-ID `TessellationHostMemoryFabric` finished both
optimization variants, but its equivalence proof exceeded the 120-second
per-stage budget and remains unproven. No whole-host OPT strategy was changed.

The comparison starts from a common process-lowered, flattened, coarse-OPT/FSM
checkpoint; inferred memories are lowered identically for SAT. It retains the
complete selected hierarchy and widths, rejects external macros, and compares
named internal state and outputs with `equiv_simple`, four-step induction and
fail-closed `equiv_status -assert`. The full input RTL and all 1,229 verification
files are checked before and after use. Bound assertion execution, actual
mapped macro retention and whole-host timing remain separate requirements.
Temporary designs and owned containers are removed on success, rejection and
timeout; raw reports are not archived.

Reproduce against the matching owner's prepared RTL and pinned Yosys image:

```sh
python -B "$OWNER_ROOT/scripts/prepare_native_owner_flashsim.py" \
  --stage check --output "$OWNER_PREPARED"
python -B scripts/test_verify_yosys_opt_equivalence.py
python -B scripts/verify_yosys_opt_equivalence.py "$OWNER_PREPARED/rtl" \
  --top RegisteredQueue2_3 --top TessellationImageBankMux \
  --image "$PINNED_YOSYS_IMAGE" --timeout 120
```

The image used Yosys 0.67+ (`a518c869e`), the same tool filesystem as the
whole-host attempt. A successful component proof does not qualify the complete
host, its assertions, clock or product frame rate.
