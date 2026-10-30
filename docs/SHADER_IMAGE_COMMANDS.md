# Native compute image commands

`GpuHostSystemAxi(imageAvailable=true)` connects image commands from AXI MMIO
through the unified command queue to the shared CUs. It requires at least eight
transaction IDs per CU. The default and product profiles keep images disabled.
This transport does not advertise an API image capability.

Host ABI version 2 exposes GCR1 at `0x360`. Flags bit 4 reports image-command
transport only when enabled. Software must validate GCR1 before reading the
optional GIM1 block; an unadvertised block remains unmapped. The kernel-free
`opengpu_read_image_command_support` helper avoids unadvertised reads and rejects
an advertised block with insufficient MMIO backing or an incorrect magic.

| Address | Register | Contract |
|---|---|---|
| `0x3e0` | CONTROL | Bit 0 enables images; bits 15:8 select the table version. Other bits are zero. |
| `0x3e4` | TABLE_BASE | GPU address of the GIU1 table. |
| `0x3e8` | TABLE_LIMIT_LO | Low 32 bits of the exclusive allocation limit. |
| `0x3ec` | TABLE_LIMIT_HI | Bit 0 is limit bit 32; other bits are zero. |
| `0x3f0` | CONTEXT | Full 32-bit publication context. |
| `0x3f4` | PASS | Full 32-bit publication pass. |
| `0x3f8` | GENERATION | Full 32-bit publication generation. |
| `0x3fc` | MAGIC | Read-only `0x47494d01`. |

The block fits between uniform discovery and blend discovery. Byte strobes merge
staged values. Misaligned, reserved-bit and read-only writes return an error
without changing the staged record. The C encoder checks version 1, 64-byte-aligned
table bounds, integral 256-byte records and a non-wrapping exclusive limit up to 4 GiB
before writing its seven output words. C headers are checked against Scala ABI
generators.

SUBMIT captures the complete staged table descriptor and identity for direct or
indirect compute. Later register writes cannot replace queued publications.
The native binned command port preserves this context when forwarding compute;
graphics descriptors continue to disable image execution. Table contents remain
GPU-memory records: software must retain their publication, backing allocations,
VM root and coherence ownership through software completion POP. VM mutations
are rejected while queued work, executing work or held software completions own
the context. Uncached image transport does not establish cross-client coherence.

Safe reset drains offered requests and memory acknowledgements, discards the
aborted completion stream and clears staged image permission. Earlier stores
are not rolled back. A later image command must explicitly restage permission.

Twenty-two distinct checks pass with no failures or skips: twenty execute
hardware and two check ABI/precondition contracts. The actual two-CU, four-lane,
two-warp AXI fixture uses 256-bit memory, direct and indirect kernels, independent
tables, six initial workgroups, thirty acknowledged split-line stores, one
indirect argument read and 8 KiB of guarded image backing. It also checks held
software completions, VM protection, invalid-table rejection, reset drain and
healthy ID reuse. Separate tests cover MMIO staging, native-port arbitration
and unchanged default/absent capability profiles. C discovery and encoding
checks pass under the full script suite. No fixture renders a frame.

```sh
bash scripts/test_scripts.sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GpuCommandMmioImageSpec opengpu.graphics.GpuCommandMmioSpec opengpu.graphics.TileBinHostCommandPortSpec opengpu.system.GpuHostImageCommandSpec opengpu.graphics.TileBinHostImageCommandPortSpec opengpu.system.ComputeResourceCapabilitiesAxiSpec opengpu.graphics.ShaderImageCommandAbiSpec'
```

Linux/API submission, compiler lowering, graphics image admission, image atomics,
cross-domain visibility/barriers, complete resource limits, full native binned
capacity/runtime and physical qualification remain open. H7 is not closed.
