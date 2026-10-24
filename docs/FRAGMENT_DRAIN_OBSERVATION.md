# Fragment drain observation

The fragment owner registers complete drain quiescence before advancing its
shared phase register. Quiescence includes EOF, staging memory queues/replies,
allocation and invalidation controls, shader/texture instruction ownership and
held output. No new batch work is admitted after this observation. An assertion
checks that the predicate stays true until the drain transition.

This removes the direct sampler-request-ready path through the full phase mux.
Fault/cancellation drain adds one observation clock; ordinary quad/batch work
adds none. Sixteen executed native fragment checks pass with zero skipped or
pending tests, including held staging, EOF cancellation, late faults, non-power
12-lane batches, captured texture policies and per-sample depth metadata.
Nominal two-CU local/global fixtures retain 5,917/7,563 clocks. Complete fragment-stage synthesis passes at 1,000 ps with 25% I/O delay:
+41.534 ps core/+8.259 ps I/O slack, with zero skipped or unresolved mappings.
The actual combined vertex/fragment shared-CU integration also passes. These
component results do not qualify whole-GPU clock or frame performance.

Reproduce all sixteen bounded native cases and the complete stage emission:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "deliver FP32 and typed raw fragment quads"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "reject descriptor, quad and EOF errors"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "count held global staging requests"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "require EOF while canceling an accepted staging offer"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "drain staging, shader and malformed export faults"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "transpose a non-power-of-two12lane batch"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "attribute a late input write fault"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "carry coherent covered sample locations"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "execute captured native texture policies and trap coordinate faults"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "retain protected per-sample raster depths"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "reject invalid active sample depths"'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-native-fragment-shader .artifacts/fragment-drain-rtl'
python scripts/qualify_ppa.py .artifacts/fragment-drain-rtl FragmentShaderStage \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/fragment-drain-synthesis
```
