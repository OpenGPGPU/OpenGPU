# Selected-layer replay image

The optional geometry profile of `TypedReplayTileImagePipeline` snapshots a
selected layer at tile admission. Geometry records matching that layer retain
their layer through typed fragment shading and image publication. Records for
other layers complete with `layerSkipped` set before state fetch or shader
launch; legacy records belong to layer zero. Record identity, ordering and
reference checks still run for every accepted record. The geometry fragment
ABI is admitted only in the explicit geometry profile.

The local 4×4 real-CU qualification replays two records into two separately
bound image views. It checks selected-layer snapshotting, explicit nonmatching
record completion, exact shader ID/layer bytes and store drain:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TypedReplayLayerSelectionSpec opengpu.graphics.LayeredReplayImageQualificationSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The full layered and legacy replay-image suites run locally. CI selects the
layer, fragment-state reader and image-transfer checks.

The enclosing scheduler must provide the correct single-layer attachment
view and replay the records for every required layer. Automatic layer discovery,
native draw submission and full H8 qualification remain open.
