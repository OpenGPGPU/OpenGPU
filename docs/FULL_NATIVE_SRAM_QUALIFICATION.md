# Full native host SRAM qualification

These explicit commands exercise the complete `mobile-native-four-engine-host`
development configuration with both pinned SRAM models. The host retains four
16-lane/eight-warp CUs, scalar floating point, 128 KiB logical L2 with eight store
entries, a 256-bit AXI port, four native image engines, a 512 KiB payload pool,
4 KiB bin pages, eight write credits, eight tile slots and 16,384 page records.
The viewport capacity is 640×480; rendering uses the original bounded 32×32 scene.

The compute command completes four concurrent 128-thread groups in 1,085 service
clocks. It checks all 512 vector words, 32 distinct per-warp floating-point
results, output guards, sixteen resource words and held completion. Distinct
operands expose CU/warp aliasing. Service counts exclude build, MMIO and host setup.

The rendering command completes one case containing 1x and 4x MSAA frames in
142,362 and 300,275 service clocks. Both retain full 128-thread fragment tasks on
all four CUs, two complementary draws, eight texture instructions per fragment,
four mixed color attachments plus D32F, exact stored/resolve images, Sv32 and
both shader stages. Same-address GPU fill-to-sample publication, payload leases,
rejected-header peak reset and the original one-million-cycle guard are checked.
The production configuration uses local payloads; this command does not claim
full-profile global-staging coverage.

Both runs include the repaired eight-entry L2 store transaction-ID namespace.
They used the preceding prepared encoder. The current UNORM implementation
carries the rendering result through the source-checked all-input numeric proof
and unchanged control/latency described in
[the pixel policy](PARALLEL_TYPED_TILE_POLICY.md). This is carried evidence,
not a fresh replay of the current encoder. The source changes no production RTL.

The rendering suite is excluded from automatic discovery. The ordinary two
local/global AXI cases and their default parameters remain unchanged; that
full-host suite is also local-only under [the CI policy](CI_TEST_GATES.md).
Run these qualifications locally, separately from PR/nightly tests, with a fresh
JVM and explicit success checking; a process exit alone is insufficient after an OOM.
Use the SRAM revision pinned by this repository:
`9f5af0939e8dd3cc1a9693a50b23441691dd7d25`.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx6G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'Test / runMain opengpu.system.QualifyNativeFourEngineSramHost'
sbt -J-Xmx6G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.fullHostQualification=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'Test / runMain opengpu.system.QualifyNativeFourEngineSramRendering --reports .artifacts/full-native-sram-rendering-reports'
```

Require `NATIVE_FOUR_ENGINE_SRAM_HOST PASS` for compute and
`FULL_NATIVE_SRAM_RENDERING PASS` plus a passing one-case XML report for rendering.
Optional `--emit DIR` on the compute command emits this same simulation host.

These are bounded full-profile functional results. They do not qualify 720p
images, whole-host timing, mapped resource budgets, general cache coherence,
product workload throughput or 30 FPS. The broader H0–H8 requirements remain open.
