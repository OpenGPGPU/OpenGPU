# Four-engine native host profile

`NativeBinnedGraphicsConfig.fourEngine` selects four tile engines, eight generic
word ports per engine, native sample processing, grouped texture service and a
512 KiB payload pool with five owners and twelve data ports. Each pixel owner
shares one canonical 32 KiB tile memory between native and generic work.

The `mobile-native-four-engine-host` emission uses four CUs with sixteen lanes
and eight warps each, scalar floating-point execution, a logical 128 KiB L2 with
eight store entries, a 256-bit external AXI port and a 640×480 viewport. This is
an explicit integration profile; it does not change constructor defaults or
advertise a qualified device capability.

`mobile-native-four-engine-product-host` retains the same execution, storage and
queue settings with a 1280×720 viewport. It requires the explicit JVM property
`-Dopengpu.productQualification=1` and is intended for separate product
qualification. Routine PR/nightly fixtures remain at or below 640×480. Emission
alone establishes neither functional qualification nor 30 FPS at 720p.

## SRAM capacity

Both viewport profiles instantiate the following characterized SRAM macros
before synthesis pruning. These disjoint totals include physical replicas and
padding to fixed macro dimensions:

| Owner | Macro capacity |
|---|---:|
| Four CU integer/vector register files | 2,176 KiB |
| Shared L2, including tags and padding | 272 KiB |
| Shared shader payload pool | 512 KiB |
| Four tile memories | 128 KiB |
| Other macro-backed storage | 94 KiB |
| **Total** | **3,182 KiB = 3,258,368 bytes ≈ 3.11 MiB** |

There are 618 instances of the 256×32-bit macro and 1,282 instances of the
256×64-bit macro. Their capacities sum to
`618 * 256 * 32 / 8 + 1282 * 256 * 64 / 8 = 3,258,368` bytes. The L2's logical
data capacity remains 128 KiB; its macro total includes implementation overhead.

The CU register-file mapping has the following logical slot and macro capacities:

| Register block | Logical slots per CU | Macro capacity per CU | Macro capacity, four CUs |
|---|---:|---:|---:|
| Vector | 16 KiB | 528 KiB | 2,112 KiB |
| Integer | 1 KiB | 16 KiB | 64 KiB |

The logical integer count includes fixed-zero `x0`; 31 registers per warp are
writable. Each warp-local bank has 32 rows but uses 256-row macros. Vector
storage has four full-width operand copies and an additional predicate-read
copy; integer storage has two read copies. The predicate view adds no logical
vector-register capacity. Scalar FP registers use ordinary register state and
are outside these macro totals. Any mapping revision must preserve operand and
predicate reads, write forwarding, warp isolation and latency. These counts
describe configured capacity; occupancy requires workload measurements.

The hierarchy additionally declares 3,158,486 bits (about 385.56 KiB) in inferred
arrays, including queues, cache storage and CU shared memory. These are separate
from the characterized macros and may map to registers or memories. Ordinary
registers remain outside both counts. Neither inventory establishes runtime
occupancy, final mapped storage, silicon area or power.
The four pixel stores plus payload alone require 640 KiB; the complete profile
therefore needs a separate resource budget from the initial proposal. Whole-host
timing, bandwidth, resource-budget acceptance and 720p/30 FPS remain unqualified.

The separate [full native SRAM qualification](FULL_NATIVE_SRAM_QUALIFICATION.md)
records bounded compute and rendering through the complete development host.
Those explicit commands are outside routine discovery and do not establish
product-resolution frame rates or whole-host timing.

[Vector response byte assembly](VECTOR_RESPONSE_BYTE_ASSEMBLY.md) preserves the
full line-request capacity while retaining only returned lane bytes. It changes
register/multiplexer state without changing characterized SRAM macro capacities.

## Frame validation contract

`NativeFourEngineDefaultHostAxiSpec` retains two cases, one for local payloads and
one for global staging. Each renders both 1x and 4x MSAA on a 32×32 image with
16×16 tiles, two complementary draws, eight fragment texture instructions,
four mixed color targets and D32F depth. Exact stored samples and resolves,
Sv32 translation, DMA publication and payload drain are checked.

Successful frames must reach four simultaneously owned fragment CUs, each with
a full 128-thread task. Command-scoped occupancy peaks exclude geometry and
ordinary compute; a rejected subsequent command must observe zero peaks. The
original one-million-cycle service guard remains in force.

The SRAM-timing-aligned reference run passes all four combinations below with
exact stored samples and resolved images. These counts cover frame service,
excluding build and host setup:

| Payload mode | 1x clocks | 4x clocks |
|---|---:|---:|
| Local | 143,056 | 300,466 |
| Global staging | 175,417 | 330,581 |

That run used the preceding prepared encoder. The current bounded UNORM
increment carries the frame evidence through verified numeric equivalence and
unchanged pipeline/control timing; the other 193 emitted pixel-owner files are
unchanged after removing comments and whitespace. This is carried integration
evidence, not a fresh product-resolution frame measurement.

The bounded frame fixture uses the default CU cache configuration, leaves the
scalar FPU backend disabled and limits bin pages and transfer credits. Passing
it does not qualify the larger emission profile above, its whole-host clock or
product frame rates. Component clock screens also do not establish those claims.

Run each payload mode in a fresh JVM to bound elaboration heap usage. Each case
still renders both sample modes with the original image checks and cycle guard.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-host .artifacts/native-four-engine-host-rtl'
# Separate product qualification, outside routine test lanes:
sbt -J-Xmx6G -Dopengpu.productQualification=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-product-host .artifacts/native-four-engine-product-host-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/native-four-engine-product-host-rtl GpuHostSystemAxi \
  --stage synthesis --clock io_s_axi_aclk --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/native-four-engine-product-host-synthesis
for local in true false; do
  sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
    -Dopengpu.simLowMemOpt=1 \
    -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
    "testOnly opengpu.graphics.NativeFourEngineDefaultHostAxiSpec -- -t \"should sample shared RGBA8 in both native binned stages and observe coherent DMA publication across four image engines local=$local\"" || exit $?
done
```
