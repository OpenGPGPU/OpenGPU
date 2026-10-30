# Native compute storage commands

`GpuHostSystemAxi(storageAvailable=true)` connects task-owned storage words to
unified direct and indirect compute commands. It requires 16 transaction IDs per
CU and protected VM state. The default profile leaves the transport disabled;
this flag does not advertise GLES support.

Host ABI version 2 exposes GCR1 bit 5 for this transport. Software must check
that bit before reading the optional GSM1 block. When the bit is absent the
block is unmapped. The kernel-free discovery helper checks the MMIO aperture
and magic before reporting support.

| Address | Register | Contract |
|---|---|---|
| `0x420` | CONTROL | Bit 0 enables storage; bits 9:8 select the table version. |
| `0x424` | TABLE_BASE | GPU address of the GSB1 table. |
| `0x428` | TABLE_LIMIT_LO | Low 32 bits of the exclusive limit. |
| `0x42c` | TABLE_LIMIT_HI | Bit 0 is limit bit 32. |
| `0x430` | CONTEXT | Publication context. |
| `0x434` | PASS | Publication pass. |
| `0x438` | GENERATION | Publication generation. |
| `0x43c` | MAGIC | Read-only `0x47534d01`. |

Strobes merge staged values; reserved-bit, read-only and misaligned writes fail
without changing the staged record. SUBMIT captures the record for each direct
or indirect compute command. Software retains the table, backing allocations,
VM and coherence ownership until the software completion is popped. Reset
clears staged storage permission and drains accepted traffic; earlier stores
are not rolled back. Graphics commands cannot enable this compute resource.

`opengpu_storage_command.h` checks discovery and encodes the staging words;
`opengpu_storage_descriptor.h` encodes one checked 64-byte GSB1 record. The
caller still owns publication and ordering. This transport is not a Linux/API
implementation, a general storage-atomic implementation or a cross-domain
coherence contract. Full resource coexistence, physical qualification and H7
acceptance remain open.

A compact two-CU AXI fixture exercised independent direct and indirect
publications, four bindings per task, 96 acknowledged initial stores and
32 KiB of guarded backing. It checked delayed acknowledgements, VM protection
through software completion POP, malformed-table rejection, reset drain and
subsequent command reuse. The storage/image MMIO and host fixture run completed
seven cases with no failures or skips; it did not render a frame.

A follow-up AXI fixture uses two sparse 128 MiB views, one ending exactly at
4 GiB, and executes stores and loads at their last words through direct and
indirect commands. Each loaded value is stored to a second guarded word and
checked. It preserves the other bindings and the same completion, fault, reset
and reuse checks. This is compact two-CU Bare-mode evidence;
translated host boundaries and full-profile resource coexistence remain open.

```sh
python3 scripts/test_driver.py
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GpuCommandMmioStorageSpec opengpu.graphics.GpuCommandMmioImageSpec opengpu.system.GpuHostStorageCommandSpec'
```
