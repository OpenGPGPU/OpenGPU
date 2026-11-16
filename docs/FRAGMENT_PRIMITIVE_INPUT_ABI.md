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
helpers delegate with new permission false. All changed callers are freshly
compiled for the checks below; overload retention alone is not hardware qualification.
The new flag is passed through the real fragment state readers, prepared and
ordinary fragment pipeline, typed image/replay pipelines and three tessellation
image pipelines. Existing TF, image, advanced blend, payload and sampler
configuration is retained. The input coalescer remains bounded at77 arrays;
when both metadata families are available their maximum is used, not their sum.
No payload-owner index or pin layout changes. The existing69632-byte full VS
payload remains larger than the new FS prefixes, so the five-owner512KiB pool
requires no capacity reduction.

## Final local results

All **788 production and 1129 test Scala sources** were freshly compiled with
Scala 2.13.18 and the Chisel 7.15.0 plugin. Runtime used only those classes and
38 third-party JARs checked to contain no GPU classes, with no historical GPU
class provider. Production/test compilation took 91.0/176.0 seconds, with sampled
aggregate peaks 3,619,456/4,063,312 KiB. Each compile group had a 600-second,
6144-MiB bound and a 4-GiB heap. Ten previous full constructor descriptors and
three old full-arity helper method descriptors were independently derived from
the preceding source and found in the new class files.

All **seven focused RTL cases** passed with assertions and caching disabled:

| Suite | Cases | Group seconds | Sampled aggregate peak KiB |
| --- | ---: | ---: | ---: |
| `FragmentPrimitiveInputAbiSpec` | 1 | 9.3 | 373520 |
| `TypedFragmentImageStateReaderSpec` | 4 | 41.7 | 439072 |
| `FragmentPrimitiveInputPackingSpec` | 2 | 229.5 | 1292560 |

The ABI case checks independent literal layouts for all nine versions, every
old/new permission combination, real GS-metadata presence, sample-plane
selection, output-mask bounds and exact scratch extents. The reader cases use
ABI1/6/8/9 with actual state/image record responses, including publication,
cache, held offers/reports, fault, cancellation, drain and no-reset reuse.

The packing cases instantiate the complete real `FragmentShaderStage` at
16 lanes × 8 warps (128 threads), 16 varying slots and four MRTs, with a two-CU
shared interface. Actual accepted memory writes/replies precede the held launch;
independent checks inspect all 64 varying components, PointCoord, PrimitiveID
and ABI9 sample arrays across 32 quads. Each ABI covers 1×/2×/4× sample modes,
a wrong sample-plane rejection, held output/report and healthy reuse. The
completion endpoint is an explicit tagged protocol peer, not a CU or shader
interpreter. No shader load, output color/mask computation or two-physical-CU
execution is inferred from that peer. Each packing elaboration emitted five
warnings in the retained texture/service closure; that closure was not removed
to fit the test.

Each simulation group had a 600-second deadline, one CPU, one unoptimized
Verilator job and 64-MiB code cache. ABI/reader groups used 256-MiB heap,
192-MiB metaspace and a 1536-MiB aggregate bound; full-stage packing used
1024/256 MiB and a 3072-MiB bound. No interface capacity was reduced. Scala/ABI
sources, compiler/plugin/JAR inputs, Java/firtool/Verilator executables and
compiled classes were checked around their consumers.

Two separately compiled production-source copies each ran the original ABI
case and failed an explicit hardware expectation:

- Substituting `geometryInput` for the ordinary permission admitted ABI8 through
  GS permission alone (observed 1 instead of 0, 9.3 seconds).
- Including ABI8 in `hasSample(UInt)` classified a pixel-rate draw as a sample
  plane (observed 1 instead of 0, 9.1 seconds).

Both failures were behavior assertions, not timeouts, OOMs or compilation
failures; production source remained unchanged. Each control had a
240-second/1536-MiB bound and the component JVM settings above. All 166 selector
unit checks, integration/large-PR catalog validation and `git diff --check`
passed. Existing scoped qualifiers are retained; packing stays explicitly local
while ABI/admission and state/image-reader checks run in normal CI. Owned
consumers exited, then generated classes, models, caches, controls and logs
were removed after their final use.

## Reproduction

Use a fresh topic checkout with the supported simulation toolchain:

```sh
sbt clean 'Test / compile'
sbt -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off \
  'testOnly opengpu.graphics.FragmentPrimitiveInputAbiSpec opengpu.graphics.TypedFragmentImageStateReaderSpec'
# Explicit local full-stage packing, outside regular CI:
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off \
  'testOnly opengpu.graphics.FragmentPrimitiveInputPackingSpec'
python3 scripts/test_changed_test_suites.py
bash scripts/ci_shard_tests.sh suites system-core ALL
bash scripts/ci_shard_tests.sh suites system-core-large-pr ALL
git diff --check
```

For each control, use a separate temporary source copy, make exactly one
change above, freshly compile it ahead of the normal classes, then select
`opengpu.graphics.FragmentPrimitiveInputAbiSpec`. Require exactly one executed
test and the corresponding explicit mismatch. Remove owned outputs only after
consumers exit. Scala regression remains disabled.

## Remaining qualification

The four explicit `FragmentShaderStageQualificationSpec` shader cases selected
by `-Dopengpu.nativePrimitiveInputQualification=1` have not been executed for
this delivery. Their local/global-payload paths and actual shader-array loads,
raw output readback, full native 512-KiB/five-owner resource factory, original
linked programs and protected submit remain outside these seven checks.
Existing compiler/validator source does not establish that complete GPU path.
Full product images, resource coexistence and the 2-CU 640×480 at 60-FPS target
remain unfinished; this stage enables no public capability and keeps alignment
acceptance at 3/40.
