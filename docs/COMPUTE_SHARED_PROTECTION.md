# Linux shared-memory admission

The compute validator can admit initialized local SRAM through a discovered
GCR1 profile. ABI1 never probes the new fields. ABI2 without
`ONE_GROUP_PER_CU` retains its prior compute behavior with shared access closed.
An isolated profile must have a coherent lane/warp/local product, aligned
nonzero shared storage and a nonwrapping 32-bit interval. Probe captures these
read-only quantities internally; no register, builtin or userspace ABI changes.

A shared pointer requires the actual `x8` local-linear-base provenance, shifted
by two and added to the exact discovered shared base. Overwriting `x8` loses
that provenance. A constant, loaded value or `x9` does not authorize shared
memory. Pointer offsets and complete workgroup spans use checked 64-bit
arithmetic. Shared pointers cannot authorize kernarg, packet, scalar, strided,
indexed or atomic memory operations.

The admitted form is unmasked unit-stride 32-bit vector memory at the discovered
physical lane count. Stores extend a contiguous initialized byte prefix only
when every workgroup item covers the next stripe. Each validation begins with
empty written and visible prefixes; all forward-CFG joins intersect them.
Only the existing reconverged barrier publishes written bytes for reads.
Unresolved branches or a divergent early exit cannot publish initialization.
The existing 512-instruction and four-forward-target limits remain unchanged.

An additional read-only provenance recognizes exactly `XORI 64` on zero-offset
local bytes for a complete 128-item, 16-lane group. Its eight warp bases are
multiples of 64 bytes; the operation swaps adjacent warp bases while preserving
lane offsets and the complete group range. It earns no initialization credit.
Other permutations, partial vector lengths and permuted stores remain closed.
This is an initialization and bounds proof, not a general race checker or GLSL
shared-variable compiler contract.

The explicit shared qualification retains the existing full resource set from
[Linux resource execution](LINUX_NATIVE_RESOURCE_EXECUTION.md). It removes only
redundant instruction setup from the original program: a CPU operand trace
compares every resource operation for both groups and all eight warp bases.
The four original/default programs remain byte-identical. All forty resource
result planes remain; thirty-two new planes check every shared word from a
different warp, with distinct group and healthy-epoch values. The 512-word
program checks 18,432 individual results per healthy parent, including 4096
shared words in each group. Guards begin immediately after the actual results.
The uniform fault precedes shared execution; fresh healthy reuse initializes
the complete range again.

One real Linux 7.2 guest passes healthy epoch one, the exact uniform-bounds
completion fault with all prior results intact, and fresh epoch three, followed
by normal power-down and QEMU exit zero. Both groups check all 4096 shared words
in each healthy epoch: 36,864 individual results overall, including 16,384
shared results. The native model was originally emitted from hardware base
`00197df1`; its 687 main/build/project input bytes match `aed4b012`. This guest
uses Linux software base `aed4b012` plus this shared-protection patch. There is
no fresh RTL emission, native build or guest retry; the result does not validate
the later `7d4b3636` scheduler change. The actual guest ELF matches the
CPU-precompiled qualification ELF. The source identities, native binary,
matching DTB and runtime libraries remain unchanged through execution.

CPU validation covers malformed/legacy discovery, common Linux launch
selection, missing/partial/path-dependent initialization, barrier bypass and
early exit, stale validation state, high-bit overflow, forged pointers and
unsupported memory forms. Strict GCC, Clang ASan/UBSan and a real Linux 7.2
AArch64 module build pass; actual-function DRM/MMIO mocks are not guest or GPU
execution. Reproduce the CPU checks with `bash scripts/test_scripts.sh`; add
`CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer'` for the focused
`scripts/test_compute_uniform_flow.py` ownership path.

The subsequent software rebase to `7d4b3636` retains the tested shared code and
program bytes. Current-kernel module and affected C checks validate the combined
driver; they do not rerun or requalify the native model's scheduler.

The optional guest uses an already qualified matching two-CU model with real
SRAM/FPU/default caches, 32-byte AXI and every required service. It adds no
routine CI/nightly GPU case. Keep the normal 16×16 probe, 2 GiB guest RAM,
`cma=768M@0x60000000`, a 7200-second fixture limit and a fixed 900000 ms bound
per shared-qualification fence. No model regeneration is needed when all DUT,
native binary and matching-DTB identities are unchanged:

```sh
OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION=1 \
OPENGPU_NATIVE_LINUX_SHARED_QUALIFICATION=1 \
ARTI_SMOKE_DTB=/path/to/matching/guest.dtb \
QEMU=/path/to/qualified/qemu-system-aarch64 \
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=7200 \
ARTI_SMOKE_DRAW_WAIT_MS=900000 \
scripts/run_arti_small_smoke.sh uniform-counter-source
```

Compiler/API integration, general shared layouts/permutations and complete
memory/barrier semantics, forced cross-context reservation waits, four-CU
product coexistence and physical qualification remain separate H7 gates.
The observer's active-CU peak of two is process-lifetime evidence, not
attribution of simultaneous full-resource execution to both CUs. The observed
`pendingBeforeConsumer=true` does not establish a forced reservation wait.
