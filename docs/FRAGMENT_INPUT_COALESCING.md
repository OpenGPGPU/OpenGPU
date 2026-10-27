# Coalesced fragment input staging

`FragmentShaderStage` groups up to four raster quads before writing the shared
shader input payload. Four 128-bit banks transpose each field into one 64-byte
request. Partial groups and fields crossing a payload line retain exact byte
masks. Shader-visible structure-of-arrays strides, metadata, helper values,
capacity checks and input/output ownership are preserved. Accepted writes must
be acknowledged before execution or cancellation can finish.

Each worker adds 8 KiB of logical buffer storage. The maximum live input group
uses 4,736 bytes; characterized macro padding occupies 16 KiB per worker, or
64 KiB across four workers. These are separate logical, live and physical
capacities. They do not describe total GPU SRAM occupancy.

Eight buffer checks and all 29 fragment-stage checks pass. The latter include
two real shared-CU cases covering resident and global payloads: each submits six
quads in a full and a partial group, using 28 input requests instead of the
84 required by the prior per-quad protocol. Both transfer the same 1,344 enabled
bytes and check exact exports and guards. Other checks cover bounded non-power
of two batches, stalls, task reuse, cancellation and fault drain.

The six native development scenes pass at 32×32 with four CUs and the full
execution/storage profile. They check stored color/depth/stencil, requested
resolves, allocation guards and drained traffic. GPU-cycle counts below compare
the same workload inputs and profile before and after input coalescing:

| Scene | Before | Coalesced | Reduction |
| --- | ---: | ---: | ---: |
| Desktop composition | 82,042 | 75,508 | 7.96% |
| Textured 3D | 108,249 | 104,731 | 3.25% |
| Opaque overdraw | 177,894 | 152,726 | 14.15% |
| Transparent layers | 177,126 | 151,952 | 14.21% |
| Small triangles | 43,926 | 43,871 | 0.13% |
| 4× MSAA | 159,693 | 155,405 | 2.69% |

The complete worker extracted from the full product elaboration passes 1,000 ps
synthesis at TC/SLVT with 25% I/O budgets: +61.950 ps core and +50.283 ps I/O
setup margin. All 122 reachable RTL modules and 14 characterized SRAM macros
are included, with zero skipped operators or unresolved instances. The four
product worker closures are identical under module names and source comments.
Retiming, false paths and memory mocks are disabled.

These results qualify this staging change. They do not qualify enclosing
CU/interconnect/host timing, concurrent publication, 720p FPS or H0–H8 completion.

Run the focused checks locally:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentInputCoalescerSpec opengpu.graphics.FragmentShaderStageSpec'
```

The [quad export guide](FRAGMENT_QUAD_EXPORTS.md) gives full-product emission,
complete worker extraction, synthesis and the six-scene development command.
Product emission retains 720p capacities; routine rendered tests remain at or
below 640×480. Full 720p rendering requires explicit product qualification and
runs outside ordinary PR/nightly CI. Raw logs and qualification records stay
local under `.artifacts/`.
