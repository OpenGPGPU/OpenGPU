# Native tessellation host integration (experimental)

The opt-in `tessellationNative` host connects GNQ1 tessellation jobs to the
native binned host's translated AXI memory path, shared shader pool and payload
ownership. It requires a native binned configuration. This is an internal
integration and is not a product capability claim.

A two-CU, two-engine, local-payload host passed malformed-command admission
and a 4x4 translated image using actual tessellation-control, isoline-evaluation
and fragment shaders. The image contained both expected shaded and clear
pixels. Reproduce the admission run with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.TessellationNativeHostAxiAdmissionSpec'
```

The host also passed local and spilled-payload compatibility cases that place
a real native binned image between malformed tessellation commands and check
payload release. Reproduce those cases with the same sbt options and
`testOnly opengpu.graphics.TessellationNativeHostLegacyCompatibilitySpec`.
Both payload modes also passed a translated descriptor-read fault, reset with
an accepted read outstanding, and two subsequent successful 4x4 image jobs.
Run those cases with the same options and
`testOnly opengpu.graphics.TessellationNativeHostAxiImageSpec`.

Host query and transform-feedback coexistence is disabled in this profile.
The experimental GNZ1 completion snapshot records reader status, stage,
address and draw, nested image/bin statuses, work identity, and optional tile
and input-fault details. It uses the existing twelve-word completion window;
software must distinguish it from GNX1 by its magic. The legacy counters are
not a qualified tessellation diagnostic ABI. Translated
physical alias provenance remains the caller's responsibility. Broader fault
and reset coverage, successful tessellation/legacy alternation and
product-profile validation are required before
this integration can be advertised; the broader mobile GPU alignment goals
remain open.

GNZ1 uses magic `0x474e5a01` in word 0. Word 1 holds the job status in bits
2:0, reader stage in 4:3, image stage in 6:5, input-fault validity in bit 7,
and tile validity in bit 8. Words 2–4 hold the reader address, draw index and
valid tile. Word 5 packs input/image/bin status values in three-bit fields;
words 6–8 hold context, pass and generation. Words 9–10 hold input-fault address
and task ID when valid, and word 11 is reserved zero. These are recorded
snapshots, not a guarantee that every failure has an address.
