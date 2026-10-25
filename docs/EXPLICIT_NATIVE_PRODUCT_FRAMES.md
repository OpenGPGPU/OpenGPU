# Explicit sparse native frame driver

`QualifyNativeProductFrames` exercises one mixed-MRT/repeated-texture rectangle
workload with complementary GPU-shaded triangles, eight fragment texture
instructions and both 1x/4x MSAA. It retains full 128-thread tasks on all four
CUs, exact stored samples/resolves, Sv32, same-address GPU fill-to-sample
publication, payload drain and rejected-header peak reset. It is one workload,
not the complete agreed product workload suite.

The smoke mode renders 32×32 on the complete 640×480-capacity SRAM host. Product
mode renders every pixel of 1280×720 on the matching product-capacity host.
Both retain the complete FPU/cache/queue/payload configuration and both pinned
SRAM models. Neither suite participates in automatic discovery.

Disjoint image allocations and streamed image/reference checking use the
[frame-memory helpers](PRODUCT_FRAME_QUALIFICATION_HARNESS.md). The driver maps
all image superpages plus a 64 MiB bin pool and a separate tile directory.
Physical image/bin ranges differ between frames to avoid unacknowledged host
mutation of cached backing bytes. Large image/bin traffic is counted rather
than retained as an unbounded per-transfer address diary.

Fifteen bounded checks cover the memory/oracle helpers, sample ownership against
independent rasterization, mixed-format blend/resolve values, entry-point flags
and original ordinary fixture defaults. The ordinary local/global cases retain
their fixed small backing, both sample modes and one-million-cycle guard.
Smoke retains that guard. Product mode has a separate one-billion-cycle
functional guard; it is not an FPS deadline or a clock-qualification claim.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -J-XX:+ExitOnOutOfMemoryError \
  'testOnly opengpu.graphics.NativeRectangleFrameReferenceSpec opengpu.testutil.NativeFrameMemorySpec opengpu.testutil.NativeProductFrameDriverContractSpec'
# Complete host, bounded image, local-only:
sbt -J-Xmx6G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'Test / runMain opengpu.system.QualifyNativeProductFrames --smoke --reports .artifacts/native-sparse-smoke-reports'
# Explicit local product qualification, outside PR/nightly lanes:
sbt -J-Xmx6G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'Test / runMain opengpu.system.QualifyNativeProductFrames --product --reports .artifacts/native-product-frame-reports'
```

Require the final `NATIVE_SPARSE_FRAMES PASS` marker and a passing one-case XML
report. The report must contain both sample-mode image checks and command-scoped
four-full-CU peaks. Build/host setup are excluded from service counts; all frame
service phases remain included. A smoke result is not a product-resolution result.
A product result alone is not 30 FPS: it still needs source-matched whole-host
clock/resources, the complete workload suite and the agreed frame-budget check.

The bounded full-host smoke has passed one case containing both 32×32 frames,
with four simultaneous full 128-thread CUs, the current UNORM encoder and both
pinned SRAM models. Exact mixed-MRT/depth samples, resolves, padding and guards
pass at 142,302 command-service clocks for 1x and 300,300 for 4x. These counts
include geometry inside the render command. They exclude the preceding GPU
texture-publication commands, control submission and completion inspection.
Fragment occupancy peaks exclude geometry; this restriction does not apply to
the command-service clock count. These are bounded smoke results, not product
frame measurements or qualified FPS.

`NATIVE_SPARSE_FRAME_CLOCK` separately measures the existing free-running GPU
clock from before the two texture-publication commands through render completion
inspection. Its three disjoint phases are publication/inspection, render
submission, and render service/inspection; their sum must equal the total.
CPU preparation, image verification and later completion-pop checks are outside
that interval. Snapshot peeks add no DUT clocks or hardware. The passing 32×32
smoke measured 142,531 total GPU clocks for 1x (182 publication, 21 submission,
142,328 service/inspection) and 300,529 for 4x (182, 21, 300,326). The measured
interval includes geometry; it does not qualify 1280×720, a host clock or FPS.
