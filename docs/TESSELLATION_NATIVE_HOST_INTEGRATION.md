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

Host query and transform-feedback coexistence is disabled in this profile.
The tessellation completion detail registers are not implemented, and its
legacy counters are not a qualified tessellation diagnostic ABI. Translated
physical alias provenance remains the caller's responsibility. Full reset,
fault, local/spill handoff and product-profile validation are required before
this integration can be advertised; the broader mobile GPU alignment goals
remain open.
