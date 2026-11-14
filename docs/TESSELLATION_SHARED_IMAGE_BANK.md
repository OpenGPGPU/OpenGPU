# Shared tessellation image engines

The three-domain native tessellation owner shares one image-engine bank between
isoline, triangle and quad jobs. Its domain router already serializes jobs.
The four-engine product configuration therefore uses four tessellation image
engines instead of twelve. Ordinary native graphics retains its separate four
engines.

Each domain retains its job reader, sequence and input pipelines, bin frontend,
directory, memory translation and feedback state. The product configuration
retains two CUs, 16 lanes, eight warps, four engines per active domain, 65,536
directory entries per domain, the 2048-axis API extent and 512 KiB payload pool.
Sharing adds no arbitration or buffering between consecutive quads of a job.
The classified job acquires the bank one cycle before child admission.

One retained domain selects the complete engine interface: begin, primitive,
end, pixel and completion channels, all memory IDs, shader tasks, payload
control and data responses, pins, cancellation and performance events. The
owner survives cancellation and faults. It releases only when the public job
completion retires after image, memory, shader, payload and feedback traffic
drain. Backend state is not reset between domains; each new tile begin
invalidates its state-reader cache. Physical-bank performance accounting keeps
idle/collect cycles between jobs.

Feedback transfer drain is distinct from its sticky poisoned status. A held
switch reply or pending memory response prevents retirement; a consumed failed
transfer can drain while further work remains prohibited until feedback reset.
Cancellation before feedback admission consumes the existing classified offer
and returns status 4 without offering a render child or feedback switch.

Twenty-three local interface, ownership, structural, feedback and host-profile
checks pass. The structural case retains all three domain frontends and
feedback while counting exactly four physical image engines. The new
96-word protected-owner factory also converts with its enabled preflight and
advanced execution connections. Both existing
two-CU host integration oracles also pass: native-sample quad/triangle/isoline
images, faults and no-reset recovery; and serial-storage three-domain live
transform feedback with visibility/primitive queries and final raster output.
The native-sample run uses the unchanged product 32 KiB storage and 64 sample
owners. It additionally holds an accepted uniform-memory reply during RESET,
checks protected SATP writes until drain, and renders a healthy cross-domain
quad afterward. These host fixtures use two image engines and 4×4 frames;
they do not exercise the final protected-owner profile's enabled advanced
blend or shader-image service combination.

These changes alter RTL and require fresh model and physical qualification.
They do not establish a clock, area reduction or 640×480 at 60 FPS. Source and
mapped SRAM counts must be obtained from the new hierarchy; previous emitted
profiles retain their original source identity and qualification limits.

Reproduce the interface, owner, structural and feedback control checks with a
single JVM and serial suites:

```sh
JAVA_TOOL_OPTIONS='-Xmx3G -XX:ActiveProcessorCount=2' MAKEFLAGS=-j1 \
  sbt -batch -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationImageBankOwnerSpec opengpu.system.TessellationImageBankMuxSpec opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec opengpu.system.TessellationThreeDomainFeedbackAdmissionSpec opengpu.graphics.TransformFeedbackThreeDomainTransferSpec opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec opengpu.system.NativeAlignmentHostProfileSpec'
```

Run the three protected-owner profile checks in a separate JVM:

```sh
JAVA_TOOL_OPTIONS='-Xmx6G -XX:ActiveProcessorCount=2' \
  sbt -batch 'testOnly opengpu.system.NativeOwnerHostProfileSpec'
```

The combined 3 GiB process exhausts its heap in that profile's Chisel
conversion. The fresh 6 GiB process passes without changing the host, storage
or owner capacities. This is an elaboration memory limit, not a product
configuration limit.

The validated two-CU integration oracles are
`TessellationNativeQuadHostAxiImageSpec`
and `TessellationThreeDomainFeedbackQueryHostAxiSpec`. Run them serially with the
same simulation options. Add `-Dopengpu.tessellationNativeStorage=1` when running
`TessellationNativeQuadHostAxiImageSpec` to select the unchanged product
32 KiB parallel/native-sample storage and its 64 sample owners. The default
serial-storage oracle and the dedicated owned-resource modes remain available.
`TessellationNativeThreeDomainFeedbackHostAxiSpec` remains an additional
integration oracle; it was not rerun for this change.
The small fixtures do not establish four-engine parallel throughput or a
complete product frame. Point `java.io.tmpdir` and `TMPDIR` at a unique short
directory directly under the main repository's `.artifacts/`. On macOS its
absolute path must be at most 52 bytes to leave room for sbt's socket suffix;
do not use a long worktree path. Leave
`opengpu.rootedHostEvidence` unset. Remove temporary models, fixtures, logs and
caches as soon as their validation use ends.

## Opt-in fragment sample/image route

The 112-word sample-stage factory explicitly enables
`tessellationFragmentImages` and `tessellationSampleShading`. Both default to
false, preserving the 96-word and 104-word factories. The host passes them
through all three domain owners into the shared physical replay bank. Image
publications are captured with the fragment task through the domain and host
routers; control/evaluation tasks explicitly publish an empty image binding.
The 112 discovery words and full product capacities are unchanged. The sample
manifest now requires both flags; source/manifest guards reject models prepared
before this routing change even when their discovery words match.

Tessellation bin records currently publish `geometry.valid=false`. The new
route accepts ordinary sample ABI6. ABI7 still requires a real GS primitive/layer
owner and is rejected on this route; zero metadata does not authorize it.
The bounded component selector starts at a trusted internal patch descriptor,
not the protected native job envelope. It executes control/evaluation and ABI6
fragment code on two 16-lane/eight-warp CUs with 16 varying slots, four raw MRTs,
and 1x/2x/4x output in a 4x4 frame. Expected coverage comes from an explicit TES
line and the independent integer line reference. Every MRT/sample and all
allocation guard bytes are checked, including clear pixels and an interior
witness for each sample. Image publication fetch and task routing are checked;
the shader contains no image opcode, so image load/store execution remains an
open qualification item.

The 25 Python preparation/executor/product guard tests pass. The new Scala
sources and RTL tests have not been compiled or simulated in this source-only
change; full-host, clock and FPS qualification remain open. Reproduce the CPU
checks with:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_prepare_native_sample_owner.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_prepare_native_owner_flashsim.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_prepare_owner_executor.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_qualify_native_sample_layered_product.py
```

After a fresh caller compile, run the router, bank and GFS ownership tests
serially, then select the real-CU component explicitly in a separate resource
window:

```sh
sbt -batch -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostShaderRouterSpec opengpu.system.TessellationImageBankOwnerSpec opengpu.system.TessellationImageBankMuxSpec opengpu.graphics.TypedFragmentImageStateReaderSpec'
sbt -batch -Dopengpu.simJobs=1 -Dopengpu.tessellationSampleImageQualification=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.tessellation.TessellationSampleImageSharedSystemSpec'
```

`TessellationNativeThreeDomainJobImageElaborationSpec` also checks both legacy
and sample/image-enabled three-domain hierarchies with exactly one shared bank.
These tests do not inherit results from an earlier factory or model.
