# Joint GNQ empty-frame qualification

`TessellationJointEmptyFrameSpec` exercises the actual isoline GNQ/GNF owner,
input memory fabric, four image banks, clear, backing stores and resolves. Its
only peer is physical memory. It supplies no shader result, patch contents,
frame completion or retirement completion.

The supported graphics bounds are 640×480 through `GraphicsTestResolution`;
`productQualification=1` is rejected. Runtime frames are 4×4 then 16×16 in the
same model without reset. Configuration retains 16 lanes × 8 warps, two shared
CU interfaces, all 16 attributes/bindings/varyings, four RGBA32F attachments,
D32F, four samples, four instantiated banks and the original 32 KiB native tile
storage with vendor SRAM models. The 4×4 case uses one bank; the 16×16 case must
retire one actual tile from each bank. Neither case measures VGA throughput.

The independent byte oracle checks every image allocation, row padding,
resolve allocation and unused directory byte. Expected useful stored/resolved
bytes are 4,352/1,088 for 4×4 and 69,632/17,408 for 16×16. The zero-spill version-2
pool covers its complete original reservation. CPU checks reject the former
truncated initial interval, a nonzero spill count and nonzero unused spill
metadata. Unexpected actual completion is reported immediately with status,
fault stage/address and read-byte count.

## Verified scope and remaining limit

The final Spec freshly compiles with the Chisel plugin. The CPU encoder checks
both runtime recipes and rejects the 720p support-profile selection before
elaboration. These are not hardware execution results. Test selection has 171
passing checks, with catalog selection and shell syntax also passing.

The earlier composed model with static 4×4 bounds failed after 2,733.64 seconds
(sampled peak 8,016,048 KiB) while waiting for a backing store. Its limit was
3,600 seconds/10 GiB; this was not a resource-limit failure. Same-binary diagnosis
observed actual status 5 after exactly 2,560 metadata bytes and no writes: the
fixture's zero-spill pool did not cover its reservation. Changing only that
input's initial limit reached a real directory write, then triggered the
unchanged outer fabric's `unowned translated input` assertion. That diagnostic
input is not qualification of the revised Spec.

Two real component tests cover the exact outer fabric configuration
(16×8, render IDs 256, one staging engine, four staging IDs), including the actual
owned-source input wrapper. Each sends 40 metadata reads on physical tag 260,
then a directory write on tag 16 with a held response and exactly one ACK.
The final two-case run passes in 23.60 seconds with a sampled peak of 409,856 KiB,
with all production assertions enabled. They have not reproduced the
old composed-model assertion and do not establish full-parent clear success.
Production fabric logic and assertions were not weakened. The revised VGA-bound
composed model, its 4×4→16×16 execution, other domains and actual CU execution
remain pending.

## Reproduction

Use a fresh current-source test classpath as `CURRENT_TEST_CP`; bind every actual
loaded `SourceFile` to its real compiler producer/current source before native
generation. An old same-named class is not a valid substitute. No SBT or Scala
regression workflow is required or enabled.

```sh
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Dopengpu.productQualification=0 \
  -cp "$CURRENT_TEST_CP" opengpu.system.TessellationJointEmptyFrameRecipeCheck
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 -Dopengpu.simCache=off \
  -cp "$CURRENT_TEST_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.system.TileBinHostMemoryFabricJointClearSpec
```

The small component/CPU groups use a 180-second deadline and 512 MiB sampled
aggregate process-group limit. The complete qualification is explicitly local
only, outside routine PR/nightly execution. For a separately reviewed full run,
use the same freshly bound classpath, the actual vendor SRAM source directory,
`productQualification=0`, `simJobs=1`, `simSeparateBuild=1`, `simReleaseHeap=1` and
`simHierarchy=TypedTileImagePipeline`, and run
`org.scalatest.tools.Runner -oD -s opengpu.system.TessellationJointEmptyFrameSpec`
under an owned process-group guard. Preserve inline assertions and verify the
actual emitted hierarchy before Verilator. The prior full-run resource envelope
was 2 GiB Java heap/256 MiB metaspace/32 MiB code, 10 GiB aggregate and 3,600
seconds; a future run must report its own limits and outcome. Retain no ended
models or raw diagnostic outputs after their consumer finishes.
