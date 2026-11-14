# Protected tessellation ownership (P16)

The first P16 block supplies the production
`opengpu_tessellation_uniform_snapshot_append` encoder in
`driver/opengpu_tessellation_uniform_snapshot.c`. It creates independent checked
TCS/TES GUR3/GUB3 private copies. It does **not** provide protected program
validation, GNQ/GNT admission, an ioctl, capability discovery, actual CU execution
or a qualified full host. P16 remains incomplete. The existing trusted GNQ/GNT/GNF
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

The remaining P16 chain needs a separate TCS/TES program validator, complete
untrusted GNQ/GNT/GNF admission, original patch/frame resource ownership,
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

The new test is registered in `scripts/test_driver.py`; the production object is
registered in `driver/Makefile`. No Scala/JVM/RTL, kernel module build, actual
TCS/TES CU execution or complete protected submit was performed for this block.
