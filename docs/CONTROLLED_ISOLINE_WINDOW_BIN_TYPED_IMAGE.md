# Controlled isoline window-bin typed images

`ControlledIsolineWindowBinTypedImagePipeline` owns an ordered frame from
controlled-isoline window-bin storage through independent typed image engines.
Isoline evaluation and fragment replay share one external CU pool through
`GeometryFragmentShaderRouter`. It is an internal physical-address component.
Native descriptor submission and MMU/host integration remain separate work.

The version-1 frame descriptor reuses the typed vertex/bin image binding:
version-3 bin directory, whole-frame image views, immutable fragment-state
table and per-engine scratch arenas. Resume is disabled; every tile, including
empty tiles, receives a terminal report and image publication. Whole-grid
admission checks every partial edge tile before exposing bin or image traffic.

`ControlledIsolineWindowBinPass` accepts optional renderer credits so each
engine retains its dispatch credit through image publication. Prepared
isoline records leave geometry invocation/layer metadata unset; typed replay
uses the public fragment ABI through prepared geometry mode.

Bounded empty-frame checks run in CI:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The local shared-CU qualification shades controlled isoline patches into typed
images through the same owner:

```sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ControlledIsolineWindowBinTypedImageSharedSystemSpec'
```

Other tessellation domains, native draw submission and product qualification
remain open. This does not establish product frame rate or physical timing; H8
remains open.
