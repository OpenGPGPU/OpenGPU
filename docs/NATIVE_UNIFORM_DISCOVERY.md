# Native uniform word-load discovery

GUC1 is a read-only sixteen-word record at `0x3a0` through `0x3dc`.
Native typed and binned hosts expose it when both shader stages implement the
[checked uniform binding path](NATIVE_UNIFORM_BINDINGS.md). Generic and legacy
profiles reject this range with AXI SLVERR, including writes with zero strobes.
Existing GTC1/GNC2/GTO1 words and feature masks are unchanged. GUC1 describes
native word loads and resource bounds; it does not declare GLES/API conformance,
compiler lowering, throughput, coherence outside the stated ownership contract,
or an enclosing GPU clock.

| Words | Meaning / value |
| --- | --- |
| 0–3 | Magic `0x47554301`, sixteen words, ABI version 1, features `0x7f` |
| 4 | Stage mask 3: vertex bit 0, fragment bit 1 |
| 5–7 | Twelve blocks per stage, twenty-four bindings, 16,384 bytes per block |
| 8–9 | Default vertex/fragment components: 1,024 / 896 |
| 10–11 | Block base alignment 256 bytes; GUR1 record size 64 bytes |
| 12–14 | Scalar `0x0e00000b`, vector `0x1c00002b`, unmasked vector `0x1e00002b` |
| 15 | Default logical block 31 |

Feature bits 0–6 describe scalar loads, vector loads, equal-active-lane
coalescing within one instruction, private-prefix bounds checks, read-only
views, cancellation drain, and native draw/state roots. They do not describe
a persistent uniform cache or coalescing across warps. Stage-mask bits differ
from the existing root stage IDs 0 (vertex) and 1 (fragment).

The generated [C header](../driver/opengpu_uniform_abi.h) names every word and
feature. Existing uniform record fields, sizes and instruction constants retain
their bytes. Software must check magic, word count and version before using
the reported interface, and pin roots, table, data and VM context through task
retirement. An available provider does not make an absent draw root valid.
Discovery remains readable while the current VM owner is busy.

Eight directed AXI checks pass across enabled compact/default providers,
native profiles without the service, generic/legacy profiles and existing
compute discovery. They use literal expected words and cover held reads,
INCR/FIXED bursts, all write strobes, aliases, unsupported sizes/bursts,
address overflow and both neighboring record boundaries. The C header compiles
with the typed, binned and fragment-state ABI headers; generated ABI checks and
CI suite-selection checks pass.

Four additional production-host checks pass at four lanes, two warps and two
shared CUs: twenty-four completed typed frames, twelve typed reset cuts, and
six binned frames across local/global payload modes. Typed frames retain
GPU-produced root/view/data publication and exact images, remap both stages'
uniform data under another ASID and restore it, and drain accepted root/view/data
reads on reset before healthy reuse. Binned frames carry GND1 vertex and cached
GFS1 fragment roots through both tile engines, using scalar vertex and vector fragment loads from bindings
0 and 23 at the final block word. Both paths reject denied Sv32 data pages
without physical reads, retain VM ownership through held completion and recover
for the following healthy frame. Separate [shared-stage interface checks](NATIVE_UNIFORM_CONCURRENT_CAPACITY.md)
cover concurrent translated publication at 128 threads per stage.
[Typed renderer capacity checks](NATIVE_UNIFORM_RENDERER_CAPACITY.md) add both
full-interface payload profiles and sixty-four-component shader consumption at
two CUs; four-CU product and full-capacity binned runtime remain required.

The complete default `GpuHostAxi` control profile, with co-enabled native/query,
indirect-compute and compute-resource records, passes 1000 ps TC/SLVT synthesis:
core/I/O slack +71.387/+170.257 ps, 68,103 cells and library area 9,131.42.
This uses 25% I/O, standard ABC, no retiming/false paths, zero skipped operators
and no SRAM. Its active Module clock port is `clock`; `s_axi_aclk` is a shell
input on this standalone component. Whole shader/render engines are external
to this control profile and retain their own timing gates.

```sh
sbt 'runMain opengpu.elaboration.EmitNativeUniformAbi --check' \
  'testOnly opengpu.system.NativeUniformCapabilitiesAxiSpec opengpu.system.ComputeResourceCapabilitiesAxiSpec'
sbt 'runMain opengpu.elaboration.EmitNativeUniformHost .artifacts/uniform-host-control'
sbt -J-Xmx6G -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeTypedGraphicsHostAxiSpec -- -z "native uniform table ownership"' \
  'testOnly opengpu.graphics.NativeBinnedGraphicsHostAxiSpec -- -z "native uniform roots"'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/uniform-host-control GpuHostAxi --stage synthesis --clock clock \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/uniform-host-control-synthesis
```

Driver/compiler API integration, four-CU product and full-capacity binned runtime,
concurrent reset/remapping, general context/cache coherence, resource/rate/product budgets
and the remaining H0–H8 gates remain required. The compact typed remapping/reset
checks do not establish those broader requirements.
