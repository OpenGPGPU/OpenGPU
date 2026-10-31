# Layered window bins

The optional layered profile snapshots a nonzero layer count with the bin
descriptor. It assigns a primitive's tile to `layer * tilesPerLayer + tile`,
where `tilesPerLayer` is the screen grid size. Legacy records address layer
zero. The complete layer grid must fit the directory capacity and the producer's
65,535-tile bound; a geometry layer outside the configured count fails before
record storage. Existing single-layer profiles retain their interfaces and
tile numbering. A local real-CU geometry test also checks that layer 9 reaches
tile range 9 and that a record-store fault drains before reuse. When optional
renderer credits are enabled, the geometry pass forwards each engine's permit
to the replay dispatcher. The image owner must retain that credit until its
tile stores and resolves are acknowledged, and return credits while cancelling
so terminal reports can drain.

Run the bounded isolation, rejected-layer and recovery test:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredWindowPrimitiveBinReplayPassSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The real-CU qualification stays outside CI:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryWindowBinPassSpec -- -z "layered=true"'
```

An enclosing owner still has to map each layer/tile to the correct attachment
view and submit all of them. This internal profile does not extend the native
host ABI or complete H8.
