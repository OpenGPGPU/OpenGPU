# Gallium global texture bindings

The explicit compiler/state-tracker profile accepts texture units 0–47 and
maps the shader's active resources to the existing 32-slot native compute
namespace. Real Mesa `gl_nir_lower_samplers`, `st_update_compute_textures`,
`st_get_sampler_views` and buffer sampler-view creation/refcounts perform this
mapping. Global indices are not placed in a 32-bit native activity mask.
Product capability reporting and the native 32-slot capacity are unchanged;
this profile does not enable a GLES compute API.

The CPU qualification compiles actual ESSL310 and submits through the real
Gallium callbacks, userspace library and extracted kernel resource publication
path. It tests sixteen active buffer textures selected from the low, middle,
high and mixed parts of the 48-unit namespace, including unit 47. A separate
32-active program queries every view with a position-weighted size checksum
and fetches native slot 31. A four-component RGBA32UI program also reads unit
47. Every accepted job holds its original view records and backing objects while the
real state tracker replaces the context bindings with another generation.
Missing active views reject before submission; unused global views are not
retained. Changing from 32 to sixteen and then one active view clears stale
native bindings. Unit 48 and a 33-active-sampler program reject.

## Bounded texture result lowering

Mesa NTT can produce a four-component TXF destination for a scalar GLSL use.
The emitter tracks subsequent TEMP source swizzles until each component is
overwritten. Every source swizzle is conservatively treated as a read, even
when its consumer only writes a subset of components. Straight-line full-TEMP
definitions can omit unobserved repeated component fetches and copies.
Control flow, unknown destination shapes and other resources keep their
existing admission rules. Invalid, empty and undeclared destinations reject
without modifying the output program.

Each retained native function-5 instruction performs a complete checked typed
texel fetch; component selection follows full texel validation/read/decode.
Even a completely overwritten result retains one real fetch. Removing unused
return channels therefore does not remove the original descriptor, full
format, bounds or data-fault checks. Six direct TGSI cases cover full RGBA,
coordinate/destination aliasing, partial overwrite followed by a full read,
reversed and repeated swizzles, and an entirely overwritten result.

The instruction ceiling remains 512 words. The actual sixteen-fetch shaders
use 474 native words, the 32-view query/fetch shader uses 430, and the full RGBA
shader uses 99. This is a bounded shader contract, not arbitrary 32-fetch GLSL
support. Default/native resource capacities have not been reduced to make the
fixtures fit.

## Validation and limits

The actual Mesa 22.3.6 compiler/state-tracker/callback/kernel-publication CPU
run passes ASan/UBSan with six selections, two generations, 192 output words,
complete output guards, held-job rebinding and missing/unused binding checks.
The component-use cases and malformed destination/no-partial-output cases pass.
The existing complete buffer-resource corpus, compute callbacks (57
submissions), checked storage atomics (96 old words), full 16 KiB shared-memory
frontend and CI test-selection catalog also pass.

The actual published programs and immutable descriptors execute on two real
CUs through Sv32 and the shared four-bank L2: 15 commands produce 416 checked
output words and 416 lower-memory writes, with four precise texture traps.
Lower reads are delayed seven cycles and write acknowledgements 37 cycles.
The full RGBA results, output guards, overlapping CU execution, held traps
and completions, and healthy reuse without reset pass. A discarded-result
fetch still traps on both bounds and a backing-memory fault: the tail-fault
view begins at byte 60, so component zero fits in the first line while the
remaining components require the faulting second line. The fixture uses four
lanes, two warps per CU and a small cache; it establishes this execution and
fault contract rather than full product capacity or throughput.

This scope is buffer textures, with R32UI and RGBA32UI execution in these
fixtures. General image allocation and non-buffer sampling entry points are
rejecting unused wrappers; the actual buffer mapping, view ownership and
format conversion functions are retained from Mesa. Wider sampler/format
coverage, linked vertex/fragment/compute aggregate accounting, all legal API
active sets, full product capacity/performance and general API visibility
remain open. The existing UBO binding-24–35 compiler fixtures do not close
cross-stage ownership or the complete 36-entry UBO namespace contract. H7
remains open.

## Reproduction

Use Linux, configured matching Mesa 22.3.6 objects and the checked uniform-index
lowering source. The build selects original reachable Mesa state-tracker
sections privately; it does not modify the shared Mesa source or build:

```sh
export OPENGPU_MESA_DIR=/path/to/mesa-22.3.6
export OPENGPU_MESA_BUILD=/path/to/matching-mesa-build
export OPENGPU_DRM_INCLUDE=/path/to/drm-headers
python3 scripts/test_gallium_texture_namespace.py \
  --build .artifacts/texture-active --sanitize

sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.galliumTextureNamespaceQualification=1 \
  -Dopengpu.galliumTextureNamespaceInputs=.artifacts/texture-active/glsl \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyGalliumTextureNamespace.scala").contains(f.getName))' \
  'Test/runMain opengpu.system.QualifyGalliumTextureNamespace'

python3 scripts/test_gallium_buffer_flow.py --build .artifacts/buffers-active --sanitize
python3 scripts/test_gallium_compute.py
python3 scripts/test_gallium_compute_atomics.py --build .artifacts/atomics-active --sanitize
python3 scripts/test_gallium_compute_shared.py --build .artifacts/shared-active --sanitize
python3 scripts/test_test_selection.py
```

The RTL qualification is excluded from default discovery. It requires the
explicit property and actual compiler/publication inputs; missing files and
failed assertions fail the command. Delete generated inputs, private builds,
simulation models and logs after their active validation use ends.
