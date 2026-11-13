# Native protected source profile discovery

GCR1 bit 17 (`OPENGPU_COMPUTE_RESOURCE_NATIVE_PROTECTED_SOURCES_V1`)
is an explicit, default-off presence contract. It guarantees GNP1 complete
static descriptor preflight, GUC1 native uniform loads, GSL1 instruction quotas,
GNX1 completion details and protected native routing together. GNP1 alone and
compute uniform transport do not guarantee the optional GUC1/GSL1 windows.
This bit describes hardware services. It does not advertise Linux submission,
CPU immutability, GLES/KHR support or product qualification.

`GpuHostAxi.nativeProtectedSources` requires native descriptor preflight,
uniform resources, shader execution limits, completion details, native vertex
input, external native routing, unified commands, protected VM state and
ABI >= 2. The integrated system additionally requires the actual translated,
preflight-enabled binned renderer on the shared shader path. The alignment
configuration carries the same explicit flag into its factory and discovery;
no frozen product target enables it implicitly.

With advanced transport and preflight enabled, the complete custom profile has
96 distinct discovery words and GCR flags `0x3ffff`. Its first 88 words retain
the existing advanced/preflight composition, with only the presence bit added.
The final eight words at `0x300 + 4*i` describe GSL1:

| Word | Value |
| --- | --- |
| 0 | `0x47534c01` |
| 1 | `8` |
| 2 | `31` |
| 3 | `32` |
| 4 | `1048576` |
| 5 | `0x04051918` |
| 6 | `0x320` |
| 7 | `0x138` |

The live nonzero quota is separately readable at `0x320`. GUC1 retains its
sixteen-word record at `0x3a0`. No address, wire-record capacity or legacy
feature bit moves. The frozen alignment-v2 target remains 72 words/`0x7fff`;
the earlier advanced/preflight custom configuration remains 88/`0x1ffff`.
Neither is relabeled as this protected source profile.

Complete test-source compilation, two configuration/ABI cases and five small
AXI cases pass. They check exact 72/88/96 composition, missing prerequisite
rejection, the new presence bit, literal GSL1/GUC1 reads, held results, reset,
write rejection and the existing off/old-ABI profiles. These control tests do
not execute a captured Linux GNJ job or qualify product throughput.

```sh
task_tmp=$(mktemp -d /Users/duckdonald/workspace/gpu/.artifacts/nXXXXXXXX)
trap 'rm -rf "$task_tmp"' EXIT
export TMPDIR="$task_tmp"
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-Djava.io.tmpdir="$task_tmp" \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'Test/compile' \
  'runMain opengpu.elaboration.EmitComputeResourceAbi --check' \
  'testOnly opengpu.graphics.NativeAdvancedBlendAbiSpec' \
  'testOnly opengpu.system.NativeDescriptorPreflightCapabilitiesAxiSpec'
```

Production admission still requires kernel-owned immutable metadata/code and
uniform publication, original-GEM dependencies, complete VA/PA ownership,
verified native enqueue and retirement, frontend integration and fresh
qualification of the enabled complete product. Driver bit 40 remains disabled
until that route is implemented and validated.
