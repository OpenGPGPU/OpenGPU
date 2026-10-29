# Compute resource discovery

GCR1 is a read-only sixteen-word record at MMIO0x360–0x39f. The actual
`GpuHostSystemAxi` supplies its executing compute configuration, physical CU
count and memory-AXI width. This also fixes GCI1's local workgroup limit when a
legacy graphics configuration differs from the compute pool. A standalone
`GpuHostAxi` keeps GCR1 unmapped unless an explicit compute profile is supplied.
Legacy shader capability words and feature bits retain their existing meaning.
The complete host defaults to device ABI v2, which guarantees GCR1 discovery.
See [bounded compute atomic admission](COMPUTE_WORD_ATOMIC_ADMISSION.md) for
the word-atomic flag, driver feature query and ABI-v1 fallback.

| Offset | Field | Meaning |
|---|---|---|
| 0x360 | MAGIC | 0x47435201 |
| 0x364 | WORDS | 16 |
| 0x368 | FLAGS | Exact checked local product, uint32 grid axes, one resident workgroup per CU, word atomics including CAS |
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

The initial actual-host mismatch was128 advertised local items against8
implemented items for a4-lane/two-warp compute profile with legacy16-lane/eight-
warp graphics. Its failing baseline is retained. Fresh composed host discovery,
AXI/admission, ABI, native execution/reset and synthesis results must accompany
main promotion. Complete permissions, scratch, branch execution, coherence,
product budgets and the full H0–H8 target remain open.

## Main integration

The [50-check compute-grid qualification](performance/mobile-compute-grid-main-2026-10-22.json) integrates GCR1 with actual default four-CU workgroup distribution, coenabled capability records, indirect/Sv32/AXI/reset execution and native shader fault/interruption recovery. Three ABI checks, strict C validation and three complete grid component synthesis screens pass. The larger control/host clock, general storage/image/barrier contracts and all remaining H0-H8 requirements remain open.
