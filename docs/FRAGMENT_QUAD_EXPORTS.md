# Quad-word fragment exports

`FragmentShaderStage` stores and reads complete four-invocation words in its
128-bit metadata and export SRAMs. Consecutive metadata rows and export fields
use the synchronous read pipeline, with the physical bank tag following each
issued metadata address. The shader input/output ABI and four-pixel quad grouping
are preserved.

Prepared four-color quads retire every 32 clocks in all four rate fixtures:
128-invocation batches with all 64 varying components, using resident payloads
and controlled spills, plus 12-invocation batches crossing staging-line
boundaries in resident and global modes. The checks cover 76 quads and 56
intervals within captured transpose windows. The first quad of each window
waits for payload reads and is excluded from this prepared-data rate; full
batch/frame throughput requires those transfers and other pipeline phases.

The metadata and export arrays retain their logical capacities. Minimum macro
depth padding adds 2 KiB per worker to the export cache, or 8 KiB across the
four product workers. The emitted full host declares 3,266,560 bytes of SRAM
macro capacity before synthesis pruning. This is structural capacity, not
runtime occupancy or a final whole-GPU mapped resource budget.

The single-task `KernelShaderStage` routes texture requests by its retained
one-hot CU ownership. Request selection and return routing use the same owner;
the existing assertion forbids multiple owners. This removes request-valid
arbitration from the peer-ready path without adding a request queue or a
handshake cycle. Ownership must persist through texture completion and drain.

37 current-source checks pass: 27 fragment cases, including the four rate
fixtures, and ten ownership/router cases. They cover scalar/vector packets,
returns and stalls across 1/2/4 CUs, task reuse, raw varying/color values,
sample metadata, held exports, spill pressure, cancellation and fault drain.
The rate fixtures are part of the 27 cases, not four additional tests.

Six current-source native 32×32 scenes also pass: desktop composition,
textured 3D, opaque overdraw, transparent layers, small triangles and 4× MSAA.
They use all four CUs and the full execution/storage profile, check exact
stored color/depth images and guards, and drain external traffic. Each frame
starts after reset; this does not qualify concurrent publication or 720p FPS.

Complete components extracted from the actual full-product elaboration pass
1000 ps synthesis at TC/SLVT with 25% input/output budgets:

| Component | Core / I/O setup margin (ps) | Reachable SV modules |
| --- | ---: | ---: |
| Fragment worker | +71.822 / +49.357 | 121 |
| Vertex stage | +68.111 / +46.169 | 130 |

All four complete product fragment-worker hierarchies are identical under
module naming and generated source comments. Mapping contains no skipped
operators or unresolved instances; retiming, false paths and memory mocks are
disabled. These component screens do not qualify enclosing CU/interconnect/host
timing, full-product resource use, concurrent publication, 720p FPS or H0–H8.

Run the component checks locally:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.KernelShaderTextureOwnershipSpec opengpu.graphics.SharedShaderTaskRouterSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' 'testOnly opengpu.graphics.FragmentShaderStageSpec'
```

Component timing uses the modules after full-product elaboration, which
determines their ports and constant pruning. Extract their complete reachable
RTL without changing bytes, then run each synthesis separately with sufficient
local memory:

```sh
sbt -J-Xmx6G -Dopengpu.productQualification=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-product-host .artifacts/product-host-rtl'
python3 scripts/extract_emitted_rtl.py .artifacts/product-host-rtl FragmentShaderStage .artifacts/fragment-worker-rtl
python3 scripts/extract_emitted_rtl.py .artifacts/product-host-rtl VertexShaderStage .artifacts/vertex-stage-rtl
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/fragment-worker-rtl FragmentShaderStage --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 --io-delay 0.25 \
  --timeout 604800 --output .artifacts/fragment-worker-synthesis
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/vertex-stage-rtl VertexShaderStage --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 --io-delay 0.25 \
  --timeout 604800 --output .artifacts/vertex-stage-synthesis
```

The emission command sets 720p capacity; it does not render a frame. The
explicit six-scene development qualifier runs 32×32 frames outside ordinary CI:

```sh
sbt -J-Xmx6G -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'Test / runMain opengpu.system.QualifyNativeProductRecipes --smoke --reports .artifacts/native-product-reports'
```

Raw logs, source manifests and qualification reports remain local under
`.artifacts/`. Whole-host and 720p product qualification remain separate gates.
