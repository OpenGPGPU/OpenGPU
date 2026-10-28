# Native render-to-texture visibility

The explicit local qualification passes both 1x and 4x MSAA cases: six native
GPU frames in total, each 25x4 pixels. Four tile workers use four shared CUs
(four lanes, two warps), two physical texture clusters and an 8 KiB payload pool.
This bounded result covers the native render-to-texture part of H4.

Each case first warms both texture clusters with the old image. An executable
GPU vertex/fragment draw then renders a different RGBA8 image into that same
physical backing. The fixture queues a textured consumer while a producer store
still awaits its AXI B acknowledgement. Physical bytes change only on accepted
B replies; descriptors and resource identity stay unchanged. No CPU upload
supplies the new texture color.

Both clusters must fetch refreshed contents. Every stored sample, 4x resolve and
pitched guard byte matches the independent color oracle. The check also rejects
early completion or texture sampling, retains VM ownership across delayed stores,
and requires drained AXI traffic, payload allocations and task pins. Actual XML
results must contain every selected case without failures, errors or skips.

Run each mode in a fresh JVM to bound elaboration memory. The compiled model is
reused between modes. This `DoNotDiscover` suite stays outside PR/nightly lanes:

```sh
for mode in 0 2; do
  sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
    -Dopengpu.productQualification=0 -Dopengpu.renderTextureSampleMode=$mode \
    -Dopengpu.simJobs=1 \
    -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
    -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
    -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
    "Test / runMain opengpu.graphics.QualifyNativeRenderTextureVisibility --reports .artifacts/native-render-texture/mode-$mode" || exit
done
```

H4 remains open until its current cache-hit rate, miss/translation stress,
multiple bindings and physical evidence are accepted together. This result
qualifies neither full product capacity nor whole-host clock or 720p frame rate.
Raw logs, XML and source/emission inventories remain local under `.artifacts/`.
