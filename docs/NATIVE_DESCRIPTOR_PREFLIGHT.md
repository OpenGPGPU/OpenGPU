# Native descriptor preflight

GNP1 describes optional hardware metadata admission for GNJ1 binned jobs. It
checks every GND and each referenced GFS before offering the frame's first
`begin`, so a malformed later record cannot follow an earlier vertex launch.
Advanced binned transport enables this scan automatically. Ordinary transport
can select `NativeBinnedGraphicsConfig.preflightDescriptors=true` independently.
The default remains off; the frozen alignment v2 target remains 72 words and
GCR flags `0x7fff`.

This is a component contract, not completed protected Linux submission, GLES
conformance, or product performance qualification. Driver feature bit 40 is not
advertised. Raw callers must publish immutable descriptors/resources and retain
code, mappings, allocations and address-space ownership through completion and
all drain. Scanning then re-reading does not protect memory from CPU writes.

## Admission and ownership

The scanner retains the full GNJ/GNF snapshots, fetches GND records with the
existing four line tags, and uses the production `TypedFragmentStateReader`
for GFS header, canonical padding, identity, versions, blend controls, shader
metadata and scratch checks. Shared pure validators preserve the existing
vertex input, shader, viewport/raster, topology and bin-role admission rules.
GFS is also checked against the frame's enabled typed attachments, sample mode,
engine count and each draw's varying/scratch assignment. Integer advanced
attachments fail before any frame begin. Every draw/reference is checked;
there is no reduced draw/state cache capacity or shader/interface limit.

GFS reads use previously unused descriptor IDs 4..7. GND reads retain IDs 0..3;
renderer IDs start at 16 as before. Only the enabled scanner instantiates the
additional reader and request queue. Ordinary off-profile reader states, tag
width and renderer capacities remain unchanged. The scan adds descriptor
traffic and latency; it is not a throughput qualification.

Offered reads and controls survive backpressure and a single-cycle cancel.
Cancellation stops further admission, drains accepted descriptor/GFS reads and
held child ownership, and retains the completion tuple until consumption.
Faulted/cancelled GFS does not publish a partial cache entry. After successful
preflight, execution re-reads records under the caller's immutable publication
contract. Existing indirect argument contents, attribute/index values, page
permissions and resource execution faults remain checked by their execution
owners; GNP does not claim to pre-execute those resources or arbitrary shaders.

## Safe discovery

ABI >= 2 and GCR1 bit 16 are required before probing GNP1. The independent
read-only block occupies `[0x4e0,0x500)`, immediately after GAB1; no existing
window moves. All older native/command/resource blocks end at or below GAB1's
`0x4e0` end. Storage `[0x420,0x440)`, uniform `[0x440,0x464)`, texture
`[0x480,0x498)` and counters `[0x4a0,0x4c0)` retain their addresses. Frozen GDB1
and the GAB1 advanced transport record are unchanged.

The eight exact words are:

| Word | Value | Meaning |
| --- | --- | --- |
| 0 | `0x474e5001` | GNP1 |
| 1 | `8` | words |
| 2 | `512` | GNJ bytes |
| 3 | `2048` | GNF bytes |
| 4 | `2048` | GND bytes |
| 5 | `512` | GFS bytes |
| 6 | `15` | all draws, referenced states, typed compatibility, complete drain |
| 7 | `0` | reserved |

The driver rejects any changed/unknown field or reserved bit. Missing presence
and older ABI paths make no optional MMIO reads. All writes, including zero
strobes, fail; held reads, burst boundaries, aliases, reset and protected VM
state do not mutate discovery. GNP presence is independent of advanced blending.
A custom integrated configuration with preflight alone has 80 discovery words
and flags `0x17fff`; with advanced blending it has 88 words and flags `0x1ffff`.
Neither custom configuration is the frozen v2 product qualification.

## Validation and reproduction

The two enabled reader RTL cases cover a three-draw job with malformed final
GND/GFS, strict advanced/integer admission, reordered tagged replies, descriptor
and GFS memory backpressure, single-cycle cancellation, late memory faults,
held begin/completion and healthy reuse. Negative cases observe zero child
begin/draw offers accepted. Four AXI cases cover enabled/off and ABI1/2 native
profiles. Two pure ABI/configuration cases and actual generator checks pass.
The C discovery test checks the literal record and all 256 single-bit changes.
Eight existing regression cases also pass: three off-profile native reader
cases (snapshots/4 GiB, malformed admission, held cancellation), three vertex
input cases (16 attributes, invalid layouts, 4 GiB), and the unchanged vertex
shader/bin admission cases. The latter use existing actual 2CU, 4-lane/2-warp
component fixtures; they are not full 2CU, 16-lane/8-warp product qualification.
The 16 Scala cases and C tests do not demonstrate a protected GNJ ioctl.

Use a short GPU-local temporary path for sbt's Unix socket. Cold compilation of
the complete test sources needs 3 GiB; the selected simulations use 2 GiB.

```sh
task_tmp=$(mktemp -d /Users/duckdonald/workspace/gpu/.artifacts/nXXXXXXXX)
trap 'rm -rf "$task_tmp"' EXIT
export TMPDIR="$task_tmp"
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-Djava.io.tmpdir="$task_tmp" 'Test/compile'
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -J-Djava.io.tmpdir="$task_tmp" \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.NativeBinnedGraphicsJobReaderSpec -- -z "preflight every"' \
  'testOnly opengpu.system.NativeDescriptorPreflightCapabilitiesAxiSpec opengpu.graphics.NativeAdvancedBlendAbiSpec'
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -J-Djava.io.tmpdir="$task_tmp" \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.NativeBinnedGraphicsJobReaderSpec -- -z "snapshot three canonical"' \
  'testOnly opengpu.graphics.NativeBinnedGraphicsJobReaderSpec -- -z "reject malformed envelopes"' \
  'testOnly opengpu.graphics.NativeBinnedGraphicsJobReaderSpec -- -z "retain offered memory begin"' \
  'testOnly opengpu.graphics.VertexInputFetcherSpec -- -z "fetch all sixteen attribute slots"' \
  'testOnly opengpu.graphics.VertexInputFetcherSpec -- -z "reject invalid layouts"' \
  'testOnly opengpu.graphics.VertexInputFetcherSpec -- -z "retain a 4 GiB"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "reject invalid layouts before input access"' \
  'testOnly opengpu.graphics.VertexBinPipelineSpec -- -z "reject draw identity raster viewport topology"'
python3 scripts/test_driver.py
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The remaining production contract includes kernel-owned GNJ/GNF/GND/GFS and
RX code/uniform publication, complete original-GEM dependency/VA/PA ownership,
submit/retire integration, frontend submission and fresh enabled-product
qualification. Ordinary native geometry shader admission and the remaining H8
shader/resource/API contracts remain separate open work.
