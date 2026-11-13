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
  'testOnly opengpu.system.TessellationImageBankOwnerSpec opengpu.system.TessellationImageBankMuxSpec opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec opengpu.system.TessellationThreeDomainFeedbackAdmissionSpec opengpu.graphics.TransformFeedbackThreeDomainTransferSpec opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec'
```

The existing two-CU integration oracles are
`TessellationNativeQuadHostAxiImageSpec`,
`TessellationNativeThreeDomainFeedbackHostAxiSpec` and
`TessellationThreeDomainFeedbackQueryHostAxiSpec`. Run them serially with the
same simulation options. Point `java.io.tmpdir` and `TMPDIR` at a unique short
directory directly under the main repository's `.artifacts/`. On macOS its
absolute path must be at most 52 bytes to leave room for sbt's socket suffix;
do not use a long worktree path. Leave
`opengpu.rootedHostEvidence` unset. Remove temporary models, fixtures, logs and
caches as soon as their validation use ends.
