# Protected tessellation ownership (P16)

P16 supplies independent checked TCS/TES GUR3/GUB3 uniform copies and a
version-1 TCS/TES program validator composed with private RX snapshots. These
helpers do **not** provide complete GNQ/GNT admission, a protected tessellation
ioctl, capability discovery, actual CU execution or a qualified full host.
P16 remains incomplete. The existing trusted GNQ/GNT/GNF
and GUR1/GUB1 paths keep their original contracts.

## Encoder contract

The input uses two existing `drm_opengpu_native_uniform_stage` records. Position
0 is TCS stage 4 and position 1 is TES stage 5; no caller-selected stage, magic,
version or GPU address is accepted. Both kernel plans are captured once before
any source callback. A callback cannot alter roots or late relocations by
modifying the caller's plans.

Each stage retains 12 logical blocks of at most 16384 bytes and 1024 default
components (4096 bytes). The aggregate has a 24-entry private block table; only
identical original `(handle, offset, bytes)` views share a slot. Cross-stage
sharing produces stage mask `0x30`; distinct offsets or sizes remain distinct,
even within the same GEM. Defaults retain independent stage-specific bindings
at selector 31. This is a private logical-block mapping, not the API72 mapping
planned for P17.

Original views require four-byte alignment, nonempty local handles and bounded
sizes without u64 offset/end overflow. Inactive views and explicit padding must
be zero. The caller/copy callback must additionally verify the original object's
complete extent. Source windows are copied exactly, UBO payloads are privately
realigned to 256 bytes, and complete read-line tails are zero padded. All unused
roots/table slots/default descriptors, reserved words and narrow-field padding
are zero. Disabled stage outputs are canonical zero; if both stages are disabled,
no segment is appended.

The private owner supplies ordinary read-only, non-executable metadata/data
segments and physical leases. The encoder never calls the executable segment
API or grants writable source views. Return values contain only private segment
IDs/root offsets/presence, not an exported GEM or address. Output remains
unchanged on failure. A relocation-allocation failure can follow a successful
append: the caller must retire the entire unpublished owner after **any** error.

## Program validator and exact RX capture

`opengpu_tessellation_program_snapshot_append` captures both the original program
view and an internal verified-owner profile before reading the source once. It
decodes a separate little-endian analysis copy; the entire original view,
including any opaque prefix and unreachable suffix, is copied unchanged into
an RX, non-writable private segment. Entry offsets are four-byte aligned and
may be nonzero. The complete view is bounded to 512 words. Only the reachable
entry suffix is validated; all reachable paths must terminate with CEASE.
Failures leave the output unchanged. Any failure requires whole-owner retirement.

The profile is an internal caller obligation, **not** permission conveyed by
untrusted stage metadata. Version 1 accepts GPU stage 4 (TCS) and 5 (TES), lanes
1/2/4/8/16 and warps 2/4/8, retaining the mandatory 16-lane, eight-warp capacity.
TCS checks the actual input/output vertex and component layout (32 vertices,
64 components and 120 patch components maximum). TES authorizes only the current
single-point, vertex-zero transport: three TessCoord words followed by at most
61 explicitly selected patch words. This is not a general dynamic `gl_in[]`
contract. Future P40 full-patch service/ISA needs a separate matched permission
and validation contract. The missing original VS-to-patch execution is P39.
Neither restriction is closed by a CPU-produced patch payload.

Definedness starts from the real launch: x1 kernarg, x2..4 group coordinates,
x5..7 local sizes, x8 linear base, x9 physical CU capacity, and only v1 initialized.
Other scalar, vector and FP registers start undefined. x1 may be overwritten as
data; no register value grants raw memory authority. Full E32/M1 VL uses verified
physical capacity or VLMAX, never output vertex count. Forward scalar branches
and bounded structured TCS SIMT merge definite register/parameter state. Backward
branches, indirect control, short VL, unsafe cross-lane operations, multi-register
widen/narrow operations and lane-zero extraction inside SIMT are rejected by
this conservative dialect.

TCS CUSTOM1 functions 20/21/22 use the real parameters/load/store interface.
Stores require initialized parameters for the current mask, valid checked patch
address fields and output permission. Constant malformed layouts fail before
publication; dynamic indices remain subject to the actual task-owned patch
service's bounds/status checks. TessLevel descriptors require output, perPatch
and tessLevel bits. The only barrier is the real `0x0000000b` instruction at a
converged PC, without outstanding control alternatives or earlier peer CEASE.
An ordinary memory fence is not a patch barrier. Runtime memory-idle parking,
barrier-PC matching, cancellation and CEASE/finish drain remain mandatory.

TES functions 32/33/35/36 read the static input, write components, emit exactly
one point and end it before CEASE. Branches cannot bypass publication. Function
37 exposes only the existing invocation information selectors, not original
GL patch PrimitiveID. Layer/GS primitive-ID functions 34/38 are forbidden.
Checked scalar/vector uniform function 7 additionally requires an owned uniform
scope. Ordinary defined scalar/vector FP32 and integer ALU operations are
allowed; raw loads/stores, atomics and unrelated compute/geometry resources are
not. Old VS/FS/GS validators and APIs are unchanged.

## Required production caller

Before calling the encoder, the future protected submitter must:

1. Copy userspace records once and derive exact per-domain GNT4/5/6 plans.
2. Resolve and retain every original local GEM, validate full logical views and
   physical writer aliases, capture syncobjs and hold all reservation locks.
3. Wait the original producer fences without blocking hardware retirement, then
   let the copy callback read exactly those stable original bytes. CPU writes
   must not race this copy.
4. Build the GUR3 copy and separately validate/copy TCS/TES programs into private
   RX views. Generate only typed GNQ/GNT/resource relocations; raw caller
   addresses cannot authorize them.
5. Publish through the private owner's verified enqueue path, retaining original
   references/context through actual execution and translation retirement.
6. Revoke every mapping/lease before releasing any backing or reference. An
   idle-wait/unmap/shootdown failure quarantines the whole job and its originals.

The encoder does not look up GEMs, acquire references, wait fences or resolve
physical aliases itself. Its callback's retained/producer-ready tests verify
this boundary; they are not a production Linux ioctl/GEM-reference test. The
real private owner/MMU tests do exercise its existing PA alias exclusion,
R-NX mappings and retirement/quarantine implementation.

The remaining P16 chain needs complete untrusted GNQ/GNT/GNF admission,
original patch/frame resource ownership,
protected tessellation enqueue/discovery, three-domain execution and cleanup.
The present VS/FS GUR1 helper and FS state-based tessellation uniform plans do
not supply those pieces. P15's optional constructor remains disabled in existing
host factories; neither old tessellation transport discovery nor CPU tests
prove the new provider exists on a DUT. Supported capacities and the mandatory
2-CU 640×480/60 FPS target are unchanged.

## CPU validation and reproduction

The actual encoder composed with the actual private owner/MMU passes C tests
with ASan/UBSan. A literal ABI oracle checks every encoded byte at full
24×16KiB block plus two full-default capacity, including all padding and read
line tails. Tests cover exact-view/cross-stage sharing, independent roots,
defaults-only/disabled scopes, four-byte views, original source mutation,
caller-plan mutation, missing source references/producer readiness, full source
bounds, malformed view/overflow/padding/inactive metadata and all copy failure
positions. Every metadata word is mutated against the independent oracle.
All aggregate/segment/relocation allocation failure positions are followed by
whole-owner retirement and healthy reuse.

Real MMU tests reject foreign writable/executable aliases to the private pages
and reject a pre-existing writer before PA sealing. Retirement timeout and
shootdown failures retain the complete owner/context/backing until explicit
recovery; healthy reuse then succeeds. Five isolated wrong-stage, wrong-mask,
wrong-view-dedup, nonzero-reserved and live-plan encoder variants are rejected by
the regression. The unchanged existing GUR1/private owner tests also execute.
Temporary binaries, mutated source copies and generated include fragments are
removed by each test; no raw evidence or qualification record is retained.

```sh
python3 -B scripts/test_tessellation_uniform_snapshot.py --sanitize --negative-controls
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_geometry_uniform_snapshot.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_program_snapshot.py
python3 -B scripts/test_tessellation_uniform_abi.py
git diff --check
```

The program fixture also covers actual entry-relative reachable control,
launch definedness, scalar/FP/vector uniforms and ALU, masked patch parameters,
branch merges, SIMT convergence, malformed patch access, physical-capacity VL,
barrier/early-exit rejection and TES single-point publication. Exact source and
caller-profile mutation, source-reference/producer readiness failures, allocation
failures, RX/no-W PA alias exclusion, timeout/shootdown quarantine and healthy
reuse exercise the real private owner/MMU. Seven isolated validator mutations
are rejected by semantic assertions, covering definedness, masked parameters,
barrier drain, publication, uniform ownership, raw memory and wrong VL capacity.
Original ESSL compiled by the real Mesa frontend and the independent P17
emitter also passes this validator/RX/MMU path: TCS 228 words with exact layout
(input vertices/components 2/4, output vertices/components 2/8, patch components
0), TES 149 words with eight static patch selections and eight output components,
16 lanes and eight warps, both owned uniform scopes enabled. This validates
original native bytes and their protected copy; it does not execute the shaders
or validate a GPU-computed result. The TessLevel regression rejects a missing
perPatch bit and accepts level5 even with zero ordinary patch components.

Reference/fence readiness in the copy callback remains a boundary test; this is
not a Linux GEM-reference/submit integration test.

```sh
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize --negative-controls
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_geometry_program_snapshot.py
# Optional actual producer byte validation; exact launch metadata is required:
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
  --programs /path/to/original-emitter-output \
  --tcs-layout 2 4 2 8 0 --tes-layout 8 8
```

Both local tests are registered in `scripts/test_driver.py`; the production
objects are registered in `driver/Makefile`. No Scala/JVM/RTL, kernel module
build, actual TCS/TES CU execution or complete protected submit was performed.
The disabled Scala regression workflow must not be enabled or triggered.
