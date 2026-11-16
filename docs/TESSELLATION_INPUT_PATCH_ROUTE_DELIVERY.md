# Original input-patch identity route

The optional, default-off language service now carries the original input-patch
identity from the accepted typed range through each isoline, triangle and quad
parent to actual TCS/TES launch acceptance. The shader value is the original
slice ordinal divided by input vertices, independently of ownership task IDs,
generated TES primitive sequences and raster primitive IDs. Ordinals reset with
the original instance. Aligned complete patches support 1..32 input vertices
and an exclusive ordinal end of 2^32. Invalid, absent, stale-version or
wrong-task metadata retires locally without opening patch memory or launching
a kernel. Cancellation follows the actual owner; ordinary VS/FS/compute launches
clear the tessellation binding.

All 788 production and 1130 test sources compiled freshly with Scala 2.13.18
and the Chisel 7.15.0 plugin. Only fresh GPU classes and 38 third-party JARs
without GPU classes supplied the runtime. Production/test compilation took
94.352/179.651 seconds, with sampled aggregate peaks 3,617,232/4,115,376 KiB,
within separate 600-second/6144-MiB group ceilings and 4-GiB JVM heaps.

Ten component RTL cases passed with assertions enabled, caching disabled and
one unoptimized Verilator job:

| Suite | Cases | Seconds | Peak group KiB |
| --- | ---: | ---: | ---: |
| `TessellationInputPatchBindingSpec` | 3 | 104.203 | 652656 |
| `TessellationVertexTypedRangeSpec` | 7 | 226.396 | 522400 |

They cover full-width IDs, original-instance resets, 3/32-vertex patches,
misalignment, zero/oversized vertex counts, end-address and cursor boundaries,
held launch snapshots, invalid bindings, cancellation, report consumption and
healthy reuse. The executor/client use full 16-lane/eight-warp interfaces with
two CU protocol endpoints, not two physical CUs. Group ceilings were 600 seconds
and 2048/1536 MiB respectively; JVM heap/metaspace were 384/256 and 256/192 MiB.

All three actual typed parents also passed CIRCT lowering with the language
service explicitly enabled. Isoline/triangle/quad took 18.829/19.563/19.104
seconds and peaked at 1,352,448/1,424,208/1,360,480 KiB. Each retained the binding
ports, owner cancellation and assertion layers at the full 512-KiB payload pool,
five owners, twelve ports, 69632-byte maximum reservation and 65536 paged
entries. Each group had a 600-second/3072-MiB ceiling with 1024/256-MiB
heap/metaspace. External image engines and the CU pool remain interfaces in
this entry; no whole host or original shader executed.

Two independently compiled source-copy controls each failed exactly one
behavior expectation, without production changes or timeouts. Substituting
`saved.evaluation.sequence` for the TES language ID returned 23 instead of
2309737967 (25.903 seconds, 409968 KiB). Substituting stage 5 for the TCS stage
returned 5 instead of 4 (72.931 seconds, 612240 KiB). Each control had a
240-second/2048-MiB ceiling. All 28 changed constructor classes retained all
31 preceding public primary/auxiliary JVM constructor descriptors. The 167
selector checks and integration/large-PR catalogs passed. Source membership,
classes, ABI, toolchain, HEAD and index guards surrounded actual consumers.

Reproduce the focused checks and structural qualification:

```sh
sbt -J-Xmx4g -J-XX:ActiveProcessorCount=1 compile 'Test / compile'
sbt -J-Xmx384m -J-XX:ActiveProcessorCount=1 -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 -Dopengpu.simCache=off 'testOnly opengpu.graphics.TessellationInputPatchBindingSpec'
sbt -J-Xmx256m -J-XX:ActiveProcessorCount=1 -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 -Dopengpu.simCache=off 'testOnly opengpu.graphics.TessellationVertexTypedRangeSpec'
sbt -J-Xmx1g -J-XX:ActiveProcessorCount=1 'Test / runMain opengpu.system.QualifyTessellationVertexParents isoline .artifacts/input-patch-isoline --tessellation-info'
sbt -J-Xmx1g -J-XX:ActiveProcessorCount=1 'Test / runMain opengpu.system.QualifyTessellationVertexParents triangle .artifacts/input-patch-triangle --tessellation-info'
sbt -J-Xmx1g -J-XX:ActiveProcessorCount=1 'Test / runMain opengpu.system.QualifyTessellationVertexParents quad .artifacts/input-patch-quad --tessellation-info'
python3 -B scripts/test_test_selection.py
git diff --check
```

Use new output directories and remove generated models after their consumers
finish. The original two-argument parent entry still leaves the language
service disabled. Source-copy controls replace only the expressions above and
select `capture TES input identity` or `capture TCS input identity` from the
original binding suite. These results do not close P04, P39, P40 or protected
three-domain execution and do not change public capabilities or frozen bytes.
