# Compute resource discovery

The [GLES 3.1 resource map](GLES31_RESOURCE_LIMITS.md) separates required API
limits from the implemented GCR1 quantities and records the open compute and
aggregate binding contracts.

GCR1 is a read-only sixteen-word record at MMIO0x360–0x39f. The actual
`GpuHostSystemAxi` supplies its executing compute configuration, physical CU
count and memory-AXI width. This also fixes GCI1's local workgroup limit when a
legacy graphics configuration differs from the compute pool. A standalone
`GpuHostAxi` keeps GCR1 unmapped unless an explicit compute profile is supplied.
Legacy shader capability words and feature bits retain their existing meaning.
The complete host defaults to device ABI v2, which guarantees GCR1 discovery.
See [bounded compute atomic admission](COMPUTE_WORD_ATOMIC_ADMISSION.md) for
word-atomic discovery and ABI-v1 fallback, and [compute image submission](COMPUTE_IMAGE_SUBMISSION.md)
for image-atomic admission and its separate driver feature query. The GCR1
image-atomic bit reports hardware support, not API conformance.

| Offset | Field | Meaning |
|---|---|---|
| 0x360 | MAGIC | 0x47435201 |
| 0x364 | WORDS | 16 |
| 0x368 | FLAGS | Baseline checked local product, uint32 grid axes, one resident workgroup per CU and word atomics including CAS; optional image, storage, uniform, texture and atomic-counter commands, image atomics and storage atomics |
| 0x36c | COMPUTE_UNITS | Physical shared compute CU count |
| 0x370 | LANES | Shader lanes per warp |
| 0x374 | WARPS | Resident warp slots per CU |
| 0x378 | LOCAL_ITEMS | Maximum local work-items per workgroup: lanes × warps |
| 0x37c | LOCAL_AXIS | Minimum of LOCAL_ITEMS and65535 |
| 0x380 | GRID_AXIS | 0xffffffff workgroups per axis |
| 0x384 | SHARED_BYTES | Shared-memory bytes per CU |
| 0x388 | SHARED_BANKS | Shared-memory banks per CU |
| 0x38c | SIMT_STACK_ENTRIES | Physical entries per warp; each divergent branch needs two |
| 0x390 | SHARED_BASE | Shader shared-memory base address |
| 0x394 | AXI_DATA_BYTES | Actual memory-AXI beat width in bytes |
| 0x398 | L2_DATA_BYTES | Shared L2 data capacity, excluding metadata |
| 0x39c | L2_BANKS | Shared L2 bank count |

The generated [header](../driver/opengpu_compute_resource_abi.h) and
[ABI JSON](abi/compute-resource-v1.json) distinguish the constructor profile
(one CU, eight-byte AXI) from the four-CU/32-byte mobile candidate. Both use the
configured shader and cache quantities; these are architectural capacities,
not measured rates or API conformance claims. Physical CUs are shared with
graphics. The [grid distributor](COMPUTE_GRID_DISTRIBUTION.md) can use multiple
available CUs for one compute grid while keeping each workgroup on one CU.

Launch admission retains exact48-bit local-size multiplication and complete
unsigned32-bit grid axes. Zero local axes, over-capacity products, zero grids,
misaligned shader PCs and kernarg addresses reject before CU dispatch. Indirect
zero-count commands retain their separate successful no-work contract. Query
writes with any byte strobe fail without reaching legacy or unified registers;
held FIXED/INCR reads, reset and VM/quota protection retain their old ownership.

## Linux direct-compute workgroups

The driver previously rejected local products above 32 even when GCR1 reported
128. Direct-compute ioctl preparation and the hardware submission entry point
now use the same checked local-product and per-axis limits. ABI v2 reads GCR1's
lane, warp, local-item and axis fields, rejects inconsistent records, and caps
the driver limit at 255 because of its shader validator. The default 16-lane,
eight-warp profile admits 128; a four-lane, two-warp profile admits eight.
ABI v1 preserves the former 32-item submission guard without assuming that it
describes the hardware's actual capacity.

`OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS` and `OPENGPU_PARAM_COMPUTE_LOCAL_AXIS`
report the effective driver limits when GCR1 is known and unified compute is
available. They return zero when unavailable or unknown. Older drivers may
reject the queries; software must not treat that as a discovered hardware
capacity. The `opengpu_compute_local_limits` userspace helper returns both
values together. These are direct-compute limits, not an API conformance claim.

Host tests run the production ioctl and hardware-submit checks with mocked DRM
and MMIO services. They accept 128-item one- and three-dimensional shapes,
reject 129 and short kernarg bounds, check a smaller profile, malformed records
and legacy behavior, and exercise the public query. The previous ioctl fails
the 128-item regression. A separate local test runs the same validator-approved
program on a frozen 16-lane/eight-warp CU model from `7617dcf4`: 128×1×1,
8×4×4 and 33×1×1 workgroups write each local ID to its own word, with inactive
lanes and adjacent guards unchanged. An incorrect-output control fails as
expected. The CU RTL matches the later main emission used for comparison.
A Linux/ARTI guest using an existing GCR1-enabled QEMU model also reported
128 items and passed these three shapes through the real DRM ioctl, checking
every output and guard and rejecting axis 129. The guest model was not freshly
bound to the current RTL; this is not full-resource or current-hardware H7
qualification.

```sh
python3 scripts/test_driver.py
python3 scripts/test_compute_feature_query.py
# Optional: use an already built 16-lane/eight-warp product model.
python3 scripts/test_compute_workgroup_cu.py --model-dir .artifacts/native-compiled-product \
  --output .artifacts/compute-workgroup-cu
# Optional Linux guest with an existing GCR1-enabled ARTI/QEMU model.
ARTI_DIR=/path/to/arti ARTI_WORK=/path/to/arti-work QEMU=/path/to/qemu-system-aarch64 \
  ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 scripts/run_arti_small_smoke.sh compute-workgroup
```

The initial actual-host mismatch was128 advertised local items against8
implemented items for a4-lane/two-warp compute profile with legacy16-lane/eight-
warp graphics. Its failing baseline is retained. Fresh composed host discovery,
AXI/admission, ABI, native execution/reset and synthesis results must accompany
main promotion. Complete permissions, scratch, branch execution, coherence,
product budgets and the full H0–H8 target remain open.

## Main integration

The [50-check compute-grid qualification](performance/mobile-compute-grid-main-2026-10-22.json) integrates GCR1 with actual default four-CU workgroup distribution, coenabled capability records, indirect/Sv32/AXI/reset execution and native shader fault/interruption recovery. Three ABI checks, strict C validation and three complete grid component synthesis screens pass. The larger control/host clock, general storage/image/barrier contracts and all remaining H0-H8 requirements remain open.
