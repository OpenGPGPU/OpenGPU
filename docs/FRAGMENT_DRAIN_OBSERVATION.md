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
Nominal two-CU local/global fixtures retain 5,917/7,563 clocks. The complete
fragment-stage synthesis timing gate remains required.

Reproduce the native suite with the commands in `FragmentShaderStageSpec` and
`-Dopengpu.productQualification=0 -Dopengpu.simHierarchy=GpuComputeUnit`; emit
`mobile-native-fragment-shader` using `EmitPpaRtl` and qualify `FragmentShaderStage`
with `--stage synthesis --period-ps 1000 --io-delay 0.25`.
