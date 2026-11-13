# Texture address definition reuse

Texture, image and region-copy owners instantiate cached Chisel definitions of
`TextureImageAddressUnit`. The cache key includes the complete address limits
and both optional pipeline cuts. Each use retains a separate physical pipeline,
request queue, valid state and held result. Address arithmetic, fault priority,
latency, throughput and resource limits are unchanged.

The explicit raw elaboration comparison instantiates four production address
pipelines with both cuts enabled. It reduces address definitions from four to
one and recursive commands from 62,512 to 15,727 (46,785 fewer, 74.8%). All four
physical instances remain. These counts concern this wrapper, not the whole
host, synthesized area or product frame rate.

The complete integrated two-CU 640×480 v2 host also passed raw elaboration and
FIRRTL conversion in a single JVM capped at 10 GiB. Address-definition reuse
reduced module definitions from 19,771 to 16,831 and recursive commands from
13,122,056 to 10,938,756 (2,183,300 fewer, 16.64%). Both checks retained the
full configuration, including sixteen transactions per CU, four graphics
engines, 65,536 directory entries, a 512 KiB payload pool, a 128 KiB L2,
black boxes and performance ports. Conversion consumed the same elaborated
annotations and exited zero. This precedes PR #769 and establishes Chisel/
FIRRTL construction only; no SystemVerilog, executable model or physical
result was produced. The full-target reproduction API is in
[the product qualification guide](COMPILED_NATIVE_PRODUCT_QUALIFICATION.md).

The cached-instance test exercises all four pipeline-cut combinations. Two
equal configurations and one smaller configuration execute concurrently with
identical tags and different bindings. One result remains held while both peers
retire faults and healthy requests without reset. The independent host oracle
checks every response, including configured dimension/layer limits,
full-width coordinate rejection and allocation overflow.

All 44 RTL cases across the address, grouped-texture, shader-image,
GPU-record address and region-copy suites pass. The four new cached-instance
cases check 384 responses in total; the existing streaming cases retain
three/four/five-cycle latency and one address per cycle without backpressure.

Reproduce the raw comparison explicitly:

```sh
sbt -J-Xmx2G -Dopengpu.textureAddressDefinitionQualification=1 \
  'Test / runMain opengpu.graphics.QualifyTextureAddressDefinitions'
```

Run the address and production-owner regressions with serial compilation:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TextureImageAddressUnitSpec opengpu.graphics.TextureRgba8GroupPlannerSpec opengpu.graphics.ShaderImageBindingAddressSpec opengpu.graphics.TextureImageResourceAddressUnitSpec opengpu.graphics.TextureImageRegionCopyUnitSpec'
```

This change does not qualify the complete enabled host, API limits, full-frame
execution, physical timing or the two-CU 640×480/60 FPS target.
