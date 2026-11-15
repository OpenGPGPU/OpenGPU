# Ordinary fragment PrimitiveID input

Internal fragment ABIs8/9 carry the original rasterized quad's32-bit
`primitiveId` without requesting GS layer/viewport authority. They are accepted
only by an explicitly constructed `primitiveIdInput=true` stage/reader; ABI9
also requires `sampleShading=true`. Both flags default false. This source block
changes no factory, discovery word, kernel admission, compiler output or public
Mesa capability. The frozen GSS1/112 profile still does not authorize8/9.

With `V` active varying slots and `S=4*physical-CU-thread-capacity` bytes, the
first six arrays remain FragCoord.xyzw, flags and coverage. The next `4*V`
arrays contain all raw varying components. PointCoord.xy occupy arrays
`6+4*V` and `7+4*V`. ABI8/9 place PrimitiveID at `8+4*V`. ABI9 then appends
SampleID and SamplePosition.xy. Ordinary bin records retain their common
primitive ID and `geometry.valid=false`; no GS record/header is manufactured.

At the full128-thread,16-varying,4-MRT capacity:

| FS ABI | Input arrays | PrimitiveID | Layer | SampleID/Position | Prefix bytes |
| --- | ---: | ---: | ---: | --- | ---: |
|1/3|70|absent|absent|absent|45056|
|2/4|72|absent|absent|absent|46080|
|5|74|72|73|absent|47104|
|6|75|absent|absent|72/73/74|47616|
|7|77|72|73|74/75/76|48640|
|8|73|72|absent|absent|46592|
|9|76|72|absent|73/74/75|48128|

Outputs remain four raw components per active MRT, depth and control. The
prefix excludes any following uniform tail. ABI8 uses pixel-rate invocation
coverage as SampleMaskIn; ABI9 uses the actual sample-plane invocation's
coverage. ABIs6/7/8/9 permit control bit6 (mask written) and bits10:7 (mask),
intersected with invocation coverage. ABI8 does not request sample planes.
Helper flags, point/centroid inputs, depth override checks and all four MRTs
retain their existing meaning. Reserved control bits remain invalid. Nothing
in this contract grants shader image, sampler or uniform resources by itself.

ABI1..7 offsets and acceptance rules remain fixed. GS ABI5/7 still require
explicit geometry permission and actual captured geometry metadata. New
ordinary permission does not imply GS or sample permission. Every changed
module preserves its old full JVM constructor, and old full-arity descriptor
helpers delegate with new permission false. Current new callers still require
fresh compilation; these overloads do not establish whole-source qualification.
The new flag is passed through the real fragment state readers, prepared and
ordinary fragment pipeline, typed image/replay pipelines and three tessellation
image pipelines. Existing TF, image, advanced blend, payload and sampler
configuration is retained. The input coalescer remains bounded at77 arrays;
when both metadata families are available their maximum is used, not their sum.
No payload-owner index or pin layout changes. The existing69632-byte full VS
payload remains larger than the new FS prefixes, so the five-owner512KiB pool
requires no capacity reduction.

## Verification scope

`FragmentPrimitiveInputAbiSpec` exercises the actual descriptor and replay
admission helpers against independent literal layouts, all old/new capability
combinations, geometry metadata presence, sample-plane selection, output mask
bounds and exact scratch extents. The separate local-only
`FragmentPrimitiveInputPackingSpec` retains the complete real stage,128 lanes,
16 varying slots,4 MRTs and a two-CU shared interface. It checks acknowledged
quad packing with a tagged completion peer that executes no shader ISA. Shader
load/readback and whole renderer correctness cannot be inferred from that peer.

`TypedFragmentImageStateReaderSpec` includes ABI1/6/8/9 publication, cache,
backpressure, fault, cancel/drain/reuse and independent permission rejection.
The full-CU cases in `FragmentShaderStageQualificationSpec` are explicitly
selected by `-Dopengpu.nativePrimitiveInputQualification=1`; they preserve two
physical CUs,16lanes×8warps,16varyings,4MRTs and local/global payload variants.
They load the PrimitiveID/sample/varying arrays in real shader code and check
raw output readback. Those full-CU cases have not been executed for this block.

Bounded local results: all12 changed production files and four test/helper
files passed fresh Scala2.13.18/Chisel7.15.0 plugin compilation in separate
128MiB groups. This used explicit earlier read-only dependencies, with the new
classes first; it is not a fresh whole-source factory build. The previous
public JVM constructor/method descriptors were retained. The lightweight
admission case and all four actual state/image reader cases passed Verilator
RTL. Two independently compiled production mutations were rejected by exact
hardware expectations: substituting GS permission for primitive permission,
and treating ABI8 as a sample plane (each observed1 where0 was required).
Normal CI selection includes the lightweight ABI/admission and state/image
reader suites. Full-stage packing remains in the separate local qualification
suite; selecting it still runs the bounded CI checks. All161 selector CPU
tests passed.

The complete stage packing attempt exceeded128MiB during Chisel-to-FIRRTL
conversion before behavior simulation. The identical ABI9 stage closure was
not retried. Capacity and texture functionality were retained. Both packing
cases and the four full-CU shader cases remain pending. Splitting the packing
suite from the lightweight admission suite changed only test registration and
was followed by fresh test compilation; successful admission/reader behavior
was not inferred for the full stage.

For the bounded local checks, compile the listed current source groups using
the Chisel plugin, then put those outputs before the matching read-only
dependencies on `CHECK_CP`. Each Java invocation uses:

```sh
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=0 -Dopengpu.simCache=off \
  -Dopengpu.simWorkspace="$PRIVATE_SIM_DIRECTORY" -cp "$CHECK_CP" \
  org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.FragmentPrimitiveInputAbiSpec
```

Select `opengpu.graphics.TypedFragmentImageStateReaderSpec` with
`-z 'publish ABI1 state'` (or6/8/9) for each reader case. Bound every process
group to180seconds, verify it exited before removing its private simulation
and compiler outputs, and check source/class/compiler inputs before and after.
Full-stage packing and full-CU qualification require a separately permitted
resource window; they are not substitutions for these small cases. No SBT,
full model or disabled Scala regression workflow was launched for this block.
Actual Mesa emitter/reflector and protected ABI8/9 RX admission, source/profile
identity, same-host ordinary/tess replay shader execution, full product images
and GNY2 qualification remain pending. This is not an end-to-end PrimitiveID or
GLES qualification.
