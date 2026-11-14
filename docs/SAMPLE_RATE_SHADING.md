# Sample-rate fragment invocation scheduling

Status: standalone integrated component with directed tests on local main. It
is not yet wired into the fragment shader owner; interpolation at the emitted
positions, shader execution and per-sample output merging remain open.

`FragmentSampleShadingScheduler` turns one covered pixel into the fragment
shader invocation sequence required by GLES 3.2 sample shading.

## Contract

- A request carries the pixel coordinate, its `maxSampleCount`-bit coverage
  mask, the sample mode (1x, 2x or 4x), a sample-shading enable and the
  32-bit minimum sample shading value.
- Sample shading is active when the enable is set and the minimum value is
  nonzero. The owner then emits one invocation per covered sample in
  ascending sample order. Each invocation carries the sample id, the
  programmed sample position for that mode (one of the four quarter-pixel
  corners), a mask containing only that sample and `perSample=true`.
- Otherwise it emits one invocation at the pixel centre with sample id zero,
  the complete request mask and `perSample=false`.
- Zero coverage, coverage bits at or above the sample count and reserved
  sample modes are rejected before any invocation. A completion record always
  reports the status and emitted count; invocations and completion are held
  under backpressure and cancellation publishes status 4 after draining.
- The minimum value is consumed only as zero versus nonzero. This standalone
  block conservatively selects every covered sample for any nonzero minimum;
  fractional granularity is not scheduled. ES 3.2 requires at least
  `max(ceil(minimum * samples), 1)` unique input sets, rather than requiring
  full sample rate for every nonzero minimum.

## Results

Status is 0 success, 1 invalid coverage or mode, and 4 cancelled. Sample
positions match `Msaa.positions`: `(0,0)` for 1x, `(-1,-1)` and `(1,1)` for
2x, and the four corners in sample-id order for 4x.

## Directed evidence

`FragmentSampleShadingSchedulerSpec` passes three cases covering 58
scheduler configurations:

- every nonzero coverage mask for 1x, 2x and 4x modes, with sample shading
  disabled, enabled with a zero minimum value, and enabled with a nonzero
  minimum value (ordering, sample ids, positions, per-sample masks, emitted
  counts and completion);
- rejection of zero coverage, high coverage bits and a reserved mode without
  emitting an invocation;
- held invocation ownership and cancellation into a drained status-4
  completion.

```sh
sbt 'testOnly opengpu.graphics.FragmentSampleShadingSchedulerSpec'
```

## Remaining work

Fragment-owner integration, interpolation at sample positions, per-sample
output merging, `gl_SampleID`/`gl_SamplePosition` delivery outside the
invocation record, API/compiler exposure and product qualification remain
open.

## Per-sample colour merge

`FragmentSampleColorMergeUnit` resolves four per-sample UNORM8 colours into
one RGBA8 pixel using the covered samples under the active 1/2/4 sample count.
Each channel is summed over covered samples and divided by that count with
round-half-up; uncovered samples are ignored and an empty coverage produces
zero. Noncanonical coverage above the sample count and unsupported counts are
rejected. 300 directed cases plus invalid/empty coverage cover the merge
(`FRAGMENT_SAMPLE_MERGE`).

## Default-off native layered source implementation

`NativeBinnedGraphicsConfig.sampleShading` is a development constructor option,
false by default and restricted to the complete native layered route. Fragment
versions 6 (ordinary) and 7 (internal geometry) request full sample-plane shading;
versions 1–5 retain their input/output offsets and control-bit checks. Existing
capability words still advertise versions 1–4. This source is unfinished and has
not passed a full caller compilation, RTL execution or protected-host/Mesa
qualification. It inherits no model, source/RTL/assertion or timing result.

The native replay owner retains the original primitive. Before interpolation,
`FragmentSamplePlaneSequencer` owns each original raw four-pixel quad, emits its
covered sample planes in order, and makes absent lanes helpers. Posted planes
stay immutable through cancellation until their actual handshake. The original
streaming FP32 preparation uses its full-width sample-edge arithmetic to
recompute weights, reciprocal W, depth and all smooth varying components for
the selected sample. Point coordinates use the same sample location. Flat
components keep the existing rule; no post-interpolation value duplication is
used. Completion waits for the sequencer and all arithmetic/output ownership
to drain. Per-primitive plane counters include two additional bits; public
`VertexRasterCompletion`/GNY2 summary fields and serialized layouts are unchanged.

The sample ABI appends SampleID and SamplePosition.xy to the existing input
prefix after point/geometry inputs; the coverage array supplies SampleMaskIn.
At 1x the logical sample identity remains valid while FragCoord.xy is packed at pixel center, consistent with
SamplePosition=(0.5,0.5), depth and varying evaluation.
Actual shared-CU output readback accepts control bit 6 as mask-written and bits
10:7 as its low four mask bits only for versions 6/7. It intersects that mask with
the invocation's own coverage and applies discard before the existing typed
sample/depth/stencil/MRT backend. Old versions still reject bits 31:6. Different
sample colors are stored independently and resolved by the existing final image
resolve. No preliminary UNORM8 average is inserted.

At the default 128-lane CU capacity, the largest sample FS prefix is
`(77 + 16 + 2) * 512 = 48640` bytes. The existing full VS prefix remains the
maximum 69632-byte reservation; four fragment slots use 278528 bytes and five
owner slots use 348160 bytes, fitting the existing 512 KiB pool without changing
slot/handle/owner namespaces. The input coalescer admits 77 input arrays while
keeping its existing 128-row physical banks, sixteen slots and four MRTs.

Source fixtures include an independent geometric triangle oracle for 1x/2x/4x
sample interpolation with 64 varying components and helper lanes; real two-CU
fragment input/mask readback under local and spilled staging, including actual
shader loads of FragCoord.xyzw at zero, negative and positive origins for every
1x/2x/4x ordinary/GS sample; and a
mixed ordinary/GS, four-image-owner layered renderer fixture with 4-lane/1-warp
CUs and complete interface/storage capacities. The latter feeds
SampleID, SamplePosition and recomputed pointCoord into actual shader outputs,
retains only samples 0/2 with a shader mask, checks every raw sample and final
resolve, and retains the real held-allocation cancel/no-reset reuse case. These
fixtures have not executed. The layered fixture uses physical memory services;
it does not substitute for original ESSL capture and protected-host execution.
A restricted 384 MiB Scala/Chisel-plugin typecheck of the 38 changed/new
production and test files passes, using read-only dependency classes. This is
not a full caller rebuild or an executable class set. CPU test-selection and
the existing published layered C/kernel ABI regression also pass, together
with the diff whitespace check. The C check validates the unchanged checked-in wire layout; it is not fresh RTL/ABI emission.

After a safe memory window, a fresh complete caller rebuild is mandatory:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.nativeLayeredSamplePlaneQualification=1 \
  clean 'Test / compile' \
  'testOnly opengpu.graphics.FragmentSamplePlaneSequencerSpec opengpu.graphics.Fp32FragmentInterpolatorSpec' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "real sample inputs"' \
  'testOnly opengpu.graphics.NativeLayeredSamplePlaneSpec'
python3 -B scripts/test_test_selection.py
python3 -B scripts/test_native_layered_abi.py
```

Remaining E32-04l/m/n work includes actual execution of the complete profile,
protected driver/compiler publication and builtin lowering, capability exposure,
API enable/minimum sample fraction and sample-qualifier/static-use linkage,
coverage/alpha-to-coverage API state, and original Mesa ESSL end-to-end cases.
Dynamic `interpolateAtSample`/`interpolateAtOffset` still need a real shader
ISA/service with retained primitive/attribute identity and dynamic operands;
the standalone location selector does not close that requirement.

## Complete owner sample transport source

`OwnerResourceProfile.sampleStage` composes that same sample-plane pipeline with
the complete geometry/compute/feedback host, retaining two 16×8 CUs, shared
atomics, four image engines, sixteen varying slots, four MRTs and the original
512 KiB payload pool. Its identity is `two-cu-vga60-owner-v2-sample-stage`, stage
`geometry-compute-sample`. The frozen 104-word factory remains unchanged. The
new stage requires 112 exact discovery words, GCR bit20 (`0x1fffff` full flags)
and the separate eight-word GSS1 record at `0x520`. GSS1 reports raw ABI6/7
inputs, output-mask control, full-plane execution and the actual maxima. It
advertises no API state, GLSL compiler lowering or dynamic interpolation.

`NativeAlignmentHostConfig` derives presence from the actual sample pipeline;
`GpuHostSystemAxi` rejects disagreement between presence and execution. Its
real `GpuHostAxi` register decoder supplies GSS1 and GCR presence together.
The public `opengpu_native_sample_transport_features.h` composes independently
checked GSS1 and GGS1 without masking flags or widening the old discovery
entry point. Errors preserve all output caches, an absent bit20 avoids GSS1
reads, and ABI6/7 remain unauthorized on older factories. The compiler/native
assembler helper gives the actual SoA input/output array indices and bounded
control-word encoding; colors precede depth/control in shader storage.

The new ASan/UBSan CPU regression checks every GSS1 bit, prerequisite/BAR
rejection before optional reads, frozen GGS1 behavior, atomic errors, full
resource consistency and all bounded sample SoA/control combinations:

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_transport.py
```

The checked-in GSS1 header matches its freshly compiled standalone Scala
capability object; kernel-header compilation passes. The new full-factory and
real AXI discovery tests are source fixtures awaiting fresh complete caller
compilation and RTL execution. No prior full RTL, source/assertion, execution
or timing qualification transfers to this combined host. Protected Linux
sample shader admission and compiler/API policy must use this new discovered
identity; ordinary ESSL sample builtins, sample-enable/minimum/static-use
publication, coverage state and dynamic interpolation remain separate open
end-to-end requirements.

The ordering and minimum-rate contract follow
[OpenGL ES 3.2 §13.4.1 and §14.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf#page=374)
and the [GLSL ES 3.20 fragment builtins](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html#fragment-shader-special-variables).
Static use of SampleID or SamplePosition forces per-sample execution independent
of the sample-shading enable/minimum. Shader sample-mask bits only clear samples
belonging to that invocation.
