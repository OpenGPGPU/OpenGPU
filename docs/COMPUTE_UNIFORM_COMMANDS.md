# Native compute uniform commands

`GpuHostSystemAxi(computeUniformAvailable=true)` connects compute uniform
publications to unified direct and indirect commands. It requires 16 transaction
IDs per CU and protected VM state. The default profile leaves this transport
disabled; its discovery bit is not a GLES capability claim.

Host ABI version 2 exposes GCR1 bit 6 for the transport. Software checks that
bit before reading the optional GUM1 block; when absent, the block is unmapped.
The C discovery helper checks both the MMIO aperture and the magic.

| Address | Register | Contract |
|---|---|---|
| `0x440` | CONTROL | Bit 0 enables compute uniforms; bits 9:8 select root version. |
| `0x444` | ROOT | GPU address of the GUR1 root record. |
| `0x448` | CONTEXT | Publication context. |
| `0x44c` | PASS | Publication pass. |
| `0x450` | GENERATION | Publication generation. |
| `0x454` | PRIVATE_BASE | Start of the task's private interval. |
| `0x458` | PRIVATE_LIMIT_LO | Low 32 bits of the exclusive private limit. |
| `0x45c` | PRIVATE_LIMIT_HI | Bit 0 is limit bit 32. |
| `0x460` | MAGIC | Read-only `0x47554d01`. |

Strobes merge staged words. Reserved-bit, read-only and misaligned writes fail
without changing the record. SUBMIT captures the complete publication for each
compute command. Software pins the root, binding records, backing data and VM
until software completion POP. Reset clears staged permission and drains
accepted traffic. Graphics tasks cannot inherit the compute publication.

`opengpu_compute_uniform_command.h` validates discovery and stages the command;
`opengpu_compute_uniform_records.h` encodes GUR1 and GUB1 records with compute
stage 2 and stage mask bit 2. These records use the graphics word offsets, with
wider stage fields. The encoders return word values; the caller serializes them
in little-endian order into GPU-visible memory and owns publication, non-overlap
with the private interval, and cross-client visibility.

The interface does not implement Linux/API submission, compiler lowering,
cross-domain barriers or full-resource qualification. H7 remains open.

Local checks pass for MMIO staging and malformed writes, generated ABI and
graphics layout compatibility, C encoders, and a two-CU AXI host exercising
12 bindings, direct and indirect commands, faults, completion ownership and
reset recovery. These checks do not establish the remaining H7 requirements.

Local validation can be reproduced with:

```sh
python3 scripts/test_driver.py
python3 scripts/test_test_selection.py
sbt 'testOnly opengpu.graphics.GpuCommandMmioComputeUniformSpec opengpu.graphics.ComputeUniformCommandAbiSpec opengpu.graphics.ComputeUniformRecordAbiSpec opengpu.system.GpuHostComputeUniformCommandSpec'
```
