# Independent tessellation language binding

`TessellationShaderTaskBinding` is an internal, captured language-input record.
Its binding-format version is 1; GPU stage is exactly 4 (TCS) or 5 (TES).
`taskId` is the 32-bit ownership crosslink. The separate 32-bit
`inputPatchPrimitiveId` is the shader-visible input-patch ID. Neither the task
ID nor the generated TES primitive sequence supplies that language value.
This binding version does not authorize a native program-profile version.

`GpuComputeUnit.tessellationInfoAvailable` defaults to false. When explicitly
present, the CU captures the prospective `tessellationTask` only on actual
`kernel.fire`, tracks actual `core.launch.fire` for every physical warp, and
retains its lease until kernel completion transfers. Each worker supports the
full 16-lane, eight-warp configuration. Invalid binding versions/stages do not
enable the instruction. Unlaunched warps and cancelled or malformed requests
produce failed scalar resource commits, using the existing scalar fault path.
An already offered commit remains immutable through cancellation and must drain
before the binding can retire. Trap observation also stops further successful
language reads.

The read is CUSTOM0 (`0x0b`), function 37, funct3 0, selector 6 in the scalar
rs1 value, with instruction rs2 field zero. The new decoder permission grants
only function 37. It grants no geometry input/output, layer, emit/end or
primitive-ID output operations. TCS unsupported selectors fail locally. TES
selectors 0..5 retain the existing `GeometryScalarWorker` meanings only while
that actual geometry owner is active; otherwise they fail locally rather than
escaping to an unserviced external texture port. Selector 6 always uses the
independent tessellation binding. `GeometryInvocationLaunch` and its existing
selectors remain unchanged.

The optional enable is propagated through Core, Frontend, DecodePipe and the
unified decoder. Shared graphics ports and `SharedShaderTaskRouter` capture the
binding with launch acceptance and route cancellation by actual CU task
ownership. Previous full JVM constructor signatures are retained through
auxiliary constructors, with the added feature disabled.

## Validation and limits

The nine production source files and five focused test sources passed grouped
Chisel-plugin compilation with Scala 2.13.18 / Chisel 7.15.0. Each group used
128 MiB heap, 128 MiB metaspace, 32 MiB code cache, one processor and a 180-second
limit, with current providers ahead of the read-only prerequisite classpath.
Eight previous full JVM constructor descriptors were checked as retained.
Normal affected-test selection includes the four focused worker, mux, decoder
and router suites. The full-CU suite remains a separate local qualification;
changing it still selects those focused CI checks. All 160 selector CPU tests
passed. This routing does not replace full-CU qualification.

Seven focused RTL tests passed: worker (3), decoder (2), the production scalar
mux with real language/geometry workers (1), and the router with two CU ports
(1). They cover all eight physical warps, captured full-width IDs independent
of task IDs, unsupported selectors, cancellation and held commits, no-reset
reuse, old geometry selector meanings, default-off permissions, queued binding
snapshots and CU-owner-directed cancellation. Four independent source-copy
negative controls failed at the intended assertions: removing launched-warp
checks, reading prospective rather than captured IDs, restoring the TES
external-port fallback, and granting all geometry operations through the new
language permission. Production sources were unchanged by these controls.

`GpuComputeUnitTessellationInfoSpec` was attempted with full 16x8 capacity and
default caches at the same 128 MiB heap limit. It aborted after 14.82 seconds
with Java heap exhaustion; the reported stack was in JAR manifest/class loading.
It produced no shader-execution result. Full-CU execution, actual trap/kernel
retirement, two physical CUs and complete parent/product qualification remain
pending. The small mux fixture explicitly does not replace that qualification.

The three-domain executors/clients and range producer are not connected by this
block. Native program version 3, its validator and real language-value producer
are not implemented here. No factory, capability, probe or production admission
is enabled. The complete 2-CU 640x480/60 FPS target is unchanged.

## Focused reproduction

Compile current source groups into a private classes directory with the Chisel
plugin, then put those classes before the verified prerequisite classpath. The
order is: binding/worker/mux + SharedCuGraphicsPort + DecodeTypes;
VectorDecoder + SharedAtomicVectorExecuteUnit; FullInstructionDecoder +
DecodePipe; GpuFrontend; GpuComputeUnitCore; GpuComputeUnit;
SharedShaderTaskRouter; the five test sources. The two vector sources refresh
actual decoder dependencies absent from the older prerequisite binaries.
Use the JVM property `-Dscala.usejavacp=true` before invoking
`scala.tools.nsc.Main`, with
`-language:reflectiveCalls -Ymacro-annotations` and the matching Chisel plugin.
Do not substitute an old same-named provider for changed current source.

Run each suite separately, applying an external 180-second owned-process-group
limit and deleting its private classes/simulation output after its last consumer:

```sh
for tess_suite in \
  opengpu.graphics.TessellationScalarInfoWorkerSpec \
  opengpu.core.frontend.decode.TessellationInfoDecodeSpec \
  opengpu.graphics.TessellationInfoMuxSpec \
  opengpu.graphics.TessellationInfoRouterSpec
do
  java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
    -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
    -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
    -Dopengpu.simCache=off -Dopengpu.simWorkspace="$TESS_SIM_OUTPUT/$tess_suite" \
    -cp "$TESS_CURRENT_CLASSES:$TESS_VERIFIED_CP" org.scalatest.tools.Runner \
    -oD -s "$tess_suite" || exit
done
```

For the pending CU check, select only
`opengpu.core.GpuComputeUnitTessellationInfoSpec` with the same limits. Any later
qualification must state its actual resource budget and preserve full hardware
capacities. The failed bounded attempt remains a failure at its stated limit.
Scala regression must remain disabled and must not be dispatched.
