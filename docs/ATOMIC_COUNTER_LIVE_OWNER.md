# Original-GEM atomic counter live owner

Nine explicit compute cases connect six original Mesa 22.3.6 ESSL sources from
`test_gallium_atomic_counter.py` to the atomic-buffer state tracker, Gallium,
library and production Linux counter-source resource ioctl. They cover static
increment/read/decrement and two dynamic-index recipes for each operation.
They never use `cs_buffer_flow.c` or a CPU shader VM. Execution requires a freshly
prepared, generated and qualified `two-cu-vga60-owner-v2-sample-stage` compute
executor. Its complete 112-word factory retains two 16-lane/eight-warp CUs,
16 KiB shared memory, four native engines, 2048² extent, 65536 directory entries
and 512 KiB payload capacity. The product target remains 2 CUs at 640×480/60 FPS.

The existing `both` default and 21-case `mandatory-compute` selection are
unchanged. Select a counter recipe explicitly with `--case`. Old 96,
compute-stage 96 and geometry 104 executor identities are rejected. Changing a
producer/checker input invalidates its executor qualification; records from an
older model cannot be relabeled or edited to pass.

## Fixed recipe and ownership

The original ESSL declares `local_size_x=4`, eight counters at binding 0 and four
SSBO outputs. Four groups execute 16 invocations. An independent 8192-byte original
GEM, filled with 0x5a, supplies offset 4080/count 8/index 7, crossing a page boundary.
Its eight words are initialized to `0xfffffffe+i*0x10203` modulo 2³², except
counter 7 starts at `0xfffffffe`. Each original 8192-byte SSBO starts at 0xdeadbeef.
The source is not copied into a storage slot or precomputed result buffer.

Production admission waits for the original backing's reservation dependency,
retains the GEM and maps its source through private Sv32 command slot 7. The
capture adds only its exact 32-byte logical counter view to the original
kernarg/four-SSBO writable ranges; it does not make either containing page or
whole GEM writable in the executor guard. The retained source survives public
handle closure. The producer remains live until actual held/retired receipts,
independent returned-byte verification and same-DUT PTE clear/shootdown/context
cleanup complete. Bad cleanup acknowledgements retain reachable source memory.

Strict READY metadata binds the original allocations/views, complete 112-word
profile plus ten auxiliary reads, 16-invocation opcode 0 launch, GAM1 context,
GSB1 record and cross-page PTEs. Native SSBO slot order comes from actual Mesa
`cs_frontend_storage_binding`, must be a complete 0..3 permutation, and is checked
against all four captured descriptors and original GEM translations. It is not
assumed to match source binding order. The current production private mappings
have RWX low PTE flags 15; the checker matches those actual mappings and still
checks every returned descriptor/PTE byte unchanged. This adds no driver
permission exemption.

The independent oracle requires the four SSBOs to agree for each invocation,
and their 16 returned old values to be exactly the multiset
`{0xfffffffe,0xffffffff,0,…,13}`. Counter 7 must become 14. Arbitration may permute
those 16 values; duplicates, omissions and returning new instead of old words
fail. Every other counter, original allocation byte, SSBO tail, source, argument,
code, descriptor and PTE byte must remain unchanged. Initial output buffers are
checked for precomputation before accepting any result.

## Input qualification and local result

Compute-specific mandatory checkers and a recursive GPU include closure bind
all original 21-case producers/oracles as well as the counter additions. Missing
or aliased source and new unbound quoted includes fail closed. Base native
checker selection remains its original five files. The counter's separate
compiler input wrapper binds original Mesa sources/headers/generated build
headers, archives, real `st_atom_atomicbuf` object, DRM headers and libraries.
Every actual translation unit has MMD validation; generated inputs are hashed
before compilation and original inputs are rechecked before/after execution.
The exact original-object/library link recipe is checked. The counter container
uses 512 MiB memory and total memory-plus-swap limit, one CPU, no network and read-only inputs.

Final local CPU validation passed:

- 53 Python tests: seven counter tests, 21 executor tests, 13 compute qualifier
  tests, four byte-oracle tests, two controller tests and six Verilator executor
  boundary tests. They reject wrong
  arithmetic/returns, omitted nonzero UBO indices, 0↔7 index/result swaps, hidden
  kernel submissions, changed guards/PTEs/descriptors, stale source identities,
  missing include dependencies and mismatched factories.
- Real production Linux submission, scheduler/MMU/MMIO capture and canonical
  C++ parsing pass with ASan/UBSan for the old 96 and new 112 CPU profiles.
  Counter-specific bad cleanup acknowledgements retain the original GEM.
- One pinned immutable AArch64 Linux build compiled 22 translation units with
  22 checked MMD files, then captured all nine recipes from the six original
  ESSL files. All 8102 external inputs and 161 GPU/checker/driver inputs remained
  unchanged. The container used 512 MiB memory and total memory-plus-swap,
  one CPU and no network. Strict READY/uniform/PTE checks and canonical C++
  ASan/UBSan parsing passed for all nine captures. Observed Mesa storage orders
  were `[3,2,0,1]` and `[0,2,3,1]`; each capture used its actual binding order.
  Every untouched initial memory was rejected by the result oracle.

These are CPU compiler/capture/rejection results. No counter shader executed on
an actual DUT and no GPU qualification was written. Actual-DUT acceptance for
all nine recipes, the original scalar and four-image coexistence sources, image/storage
atomics, indirect work, cross-domain barriers, full resource-limit combinations
and whole-host physical/clock/FPS gates remain open. Ended containers, binaries,
captures, input snapshots and temporary reports were removed; source commits
remain available.

## Static read and decrement

`counter-read` and `counter-decrement` select the original `read.comp` and
`decrement.comp` through the same production counter-source/owner path. Both
retain four groups of four invocations and the original full 112-only factory.
Read starts counter 7 at 0xfffffffe and requires 16 identical reads with no changed
source byte. Decrement starts at 1 and requires the 16 **new** values
`{0,0xffffffff,…,0xfffffff1}`, with final counter 7=0xfffffff1. This matches ESSL
pre-decrement, not TGSI add(-1)'s old-value result: Mesa lowers to add(-1) plus a
result adjustment, and the native emitter preserves that distinction.

## Dynamic indices

The six dynamic recipes use the original `dynamic-increment.comp`,
`dynamic-read.comp` and `dynamic-decrement.comp`, each with two fixed launches:

| Case prefix | Original binding 35 index | Groups × local | Counter indices |
|---|---:|---:|---|
| `counter-dynamic-` |4|1×4|4..7|
| `counter-dynamic-all-` |0|2×4|0..7|

All eight dynamic initial values are distinct: indices 0..6 use
`seed+(i+1)*0x10203` modulo 2³², and index 7 uses `seed`. Static increment retains
its original `seed+i*0x10203` initialization unchanged. Explicit 0↔7 result/final
mutants are rejected, including the all-counter recipe.

The nonzero case distinguishes dropping the original UBO addition from using
`gl_GlobalInvocationID.x` directly. The all-counter case separately covers all
eight original words. Neither reduces the factory's supported resources or
proves simultaneous activity on two CUs. All retain the same 8192-byte original
counter GEM and exact 32-byte writable view. Dynamic decrement uses seed 0,
including counter 7=0, so its selected indices check modulo 2³² underflow.

The original 4096-byte index UBO has a 16-byte view, first word 0 or 4 and three
fixed nonzero padding components, with 0x6d guards outside that view. READY binds
the source to a complete 8192-byte read-only uniform snapshot: GUM1 command,
GUR1/GUB1 metadata, every reserved/padding byte, the 16 copied source bytes and
both snapshot PTEs. No additional writable interval is granted. Results are
checked per invocation against its exact selected counter; every unselected
counter, original UBO, private snapshot and SSBO tail remains unchanged. Read
requires no source change; decrement requires new values, not TGSI old values.

READY also requires exactly one final opcode 0 kernel; preceding submissions
may only be production opcode 5 publication invalidates. Hidden same-opcode or
opcode 14 submissions and actions after the kernel are rejected. Disabling only
that check makes the hidden-kernel regression pass its malformed input, proving
the test detects the previous last-register-snapshot gap. The canonical C++
parser independently enforces the same command shape.

`--capture-counter-matrix` is an explicit CPU-only build/capture mode. It
requires `--case counter-increment --capture-only` and reuses one build for all
nine recipes. It cannot start a live DUT qualification.

## Reproduction

CPU checks, with temporary outputs removed by each test:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_counter_source.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_prepare_owner_executor.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_qualify_compute_owner.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_oracle.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_controller.py
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_host.py --sample-stage --canonical-root .
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_host.py --canonical-root .
```

After fresh preparation/generation and matching source-qualified compute executor
build, the actual-DUT entry reproduces the original Mesa build, live capture,
independent result verification and retirement/cleanup:

```sh
python3 scripts/qualify_compute_owner.py \
  --executor "$OWNER/executors/compute/executor" \
  --executor-qualification "$OWNER/executors/compute/qualification.json" \
  --executor-checker "$SOURCE/scripts/prepare_owner_executor.py" \
  --executor-checker-python "$MATCHED_PYTHON" \
  --case counter-increment
```

Preserve the generation-matched FlashSim/CIRCT environment. This command refuses
an absent/stale executor qualification before building a producer. It cannot
succeed using CPU-only captures: it requires actual DUT receipts and returned
bytes. Do not run it against an older model by changing a manifest/profile label.
