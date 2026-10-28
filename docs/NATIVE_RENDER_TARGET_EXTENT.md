# Native render-target maximum and display extent

`NativeBinnedGraphicsConfig.renderTargetExtent` optionally sets the native
bin/raster/image maximum independently of the host display extent. `None`
retains the display bounds. Each axis must be positive, fit 16 bits and
accommodate the display. GNC2 discovery reports the same native maximum used
by `MemoryNativeBinnedGraphicsPipeline`; legacy/display storage keeps its
original dimensions.

The explicit `mobile-native-four-engine-api-extent-host` emission profile uses
a 640×480 display and 2048×2048 native bounds, with the complete four-CU,
16-lane/eight-warp, four-engine configuration and real SRAMs. It requires
`-Dopengpu.apiExtentQualification=1`. The ordinary and 720p product profiles
retain their existing maxima. The 720p performance target remains required.

Three configuration checks pass. One hardware admission case passes 975
bounded descriptor checks with the production 32 KiB resident capacity:
36 color formats × 1x/2x/4x × nine backing, corner, resolve and exclusive-4-GiB
checks, plus three layouts with four RGBA32F colors, depth/stencil and all six
resolves. The checks verify twelve physical origins, held results and captured
inputs. They render no pixels and do not traverse a large frame.

Both complete-host emissions pass with 710 reachable definitions and four
physical CUs. Their real-SRAM inventories match. Default logic matches the
retained production host apart from Scala source-location comments. The
explicit profile changes actual native dimension admission and GNC2 discovery
to 2048×2048 while the display remains 640×480.

This does not establish a general 2048×2048 renderbuffer capability. The bin
directory still admits at most 16,384 tiles and its count word is 16 bits;
a 2048×2048 grid of 8×8 tiles needs 65,536. Format/sample combinations that
need smaller resident tiles therefore require further grid support. Native
frame execution, extreme viewport/scissor behavior and the remaining H6
requirements must also pass before the product maximum is promoted. These
results establish neither whole-host clock nor product FPS.

```sh
sbt 'testOnly opengpu.graphics.NativeRenderTargetExtentSpec'
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.graphics.StagedTypedImageAdmissionSpec -- -z "bound full 2048-axis image metadata"'
sbt -J-Xmx6G -Dopengpu.apiExtentQualification=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-api-extent-host .artifacts/api-extent-host'
```
