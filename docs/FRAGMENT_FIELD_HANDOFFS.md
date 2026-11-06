# Fragment field handoffs

`FragmentOutputReader` now publishes only the active complete-quad rows of a
field. `FragmentShaderStage` asserts that every transpose-cache read stays
inside that captured window. `FragmentInputCoalescer` issues the next field's
synchronous read on the preceding field's final accepted write, while retaining
the registered data/address through held and split offers.

The physical 128-thread ABI, 512-byte field stride, 18 output fields, SRAM
capacities and ports remain unchanged. External request masks, ownership of the
two accepted read IDs, fault/cancel drain and output ordering remain unchanged.
Padding rows outside a declared output window are not initialized for consumers.

## Component measurements

The Reader probe retains all 256 combinations of 64 byte offsets and four
legal widths, plus same-beat replies, held cancellation, reordered split
faults, complete drain and healthy reuse. Each 18-field timing probe uses a
controlled two-cycle memory latency, 18 requests/replies and two credits. A/B
accepted address/mask sequences and active byte hashes match exactly.

| Active lanes | Control clocks | Candidate clocks | Saved | Private 128-bit writes |
| --- | ---: | ---: | ---: | ---: |
| 4 | 87 | 58 | 29 | 72 → 18 |
| 8 | 87 | 67 | 20 | 72 → 36 |
| 12 | 87 | 77 | 10 | 72 → 54 |
| 16 | 87 | 87 | 0 | 72 → 72 |

The Coalescer probe checks 16 groups across two quad counts, two base offsets
and four field counts. With `N = inputWords + 2`, every group saves exactly
`N - 1` clocks. Request sequences, masks and effective bytes remain exact;
cancellation, final addresses and held/split offers also pass.

| N | Unsplit control → candidate | Split control → candidate | Saved |
| --- | ---: | ---: | ---: |
| 8 | 24 → 17 | 32 → 25 | 7 |
| 12 | 36 → 25 | 48 → 37 | 11 |
| 72 | 216 → 145 | 288 → 217 | 71 |
| 76 | 228 → 153 | 304 → 229 | 75 |

Coalescer timing starts after the flush helper returns and ends at the first
`done`; it excludes filling, the initial handshake and consuming `done`. Reader
and Coalescer figures measure different component intervals. At an assumed
1 GHz, one clock is 1 ns; these savings are not added into a frame estimate.

## Combined Stage and production synthesis

One real Stage model consumes 16/4/8/12/16 active lanes without reset, with all
16 varyings, four render targets, 18 independent nonzero output fields and the
full logical SRAM capacities. All five windows pass exact values and metadata
ordering, nine clocks of held export, a 64-byte guard and final drain. The
strengthened cache-read assertion is enabled in actual native execution. A
bounded shared-completion peer supplies shader results; this fixture runs no
CU/ISA and uses full-capacity behavioral SRAM.

Production emission uses the stock `mobile-native-fragment-shader` target:
default native configuration, 16 KiB maximum payload with owner 1, all 1376
ports, 122 reachable functional modules and 14 physical 256x64 SRAM instances.
Stock full hierarchical synthesis/setup passes at 1000 ps, SLVT/TC and 25% I/O
delay: core setup slack is +22.387 ps and virtual-I/O setup slack is +72.591 ps.
Mapping skips no operators or instances, and linked `check_setup` coverage is
complete. No retiming, false-path exception or memory abstraction is used. This
profile differs from `mobile-public-frame-fragment-shader` with grouped textures
and its other metadata/payload settings. The full public-frame profile and
whole host have not been requalified by this change.

## Manual reproduction

These small qualifications add no routine PR/nightly rendering case. Use fresh
disposable workspaces with JDK 21, Scala 2.13.18, Chisel 7.15.0, firtool 1.158.0,
Verilator 5.050, GNU make 4.4.1 and Espresso on PATH. Native runs used one CPU,
Xmx1GiB, owned RSS at most 2 GiB, make j1, 300 seconds per qualification and no
automatic retries. Repository Main compilation is normal; each command compiles only GpuSim and
its selected Test source. Build time is outside the measured component-clock
interval. The actual measurements used focused compiled classpaths; these
source-based reproduction commands have not been rerun as an extra qualification.

```sh
git submodule update --init depends/asap7_sram_0p0
command -v espresso
field_workspace="$PWD/.artifacts/fragment-field-repro"
test ! -e "$field_workspace"
sbt -J-Xmx1G -J-XX:ActiveProcessorCount=1 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simCcache=off -Dopengpu.simReleaseHeap=0 \
  -Dopengpu.simCache="$field_workspace/reader" \
  'set Test / sources := Seq(baseDirectory.value / "src/test/scala/opengpu/testutil/GpuSim.scala", baseDirectory.value / "src/test/scala/opengpu/graphics/FragmentOutputReaderSpec.scala")' \
  'testOnly opengpu.graphics.FragmentOutputReaderSpec -- -z "form exact masks at every byte offset for all legal quad window widths"'
sbt -J-Xmx1G -J-XX:ActiveProcessorCount=1 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simCcache=off -Dopengpu.simReleaseHeap=0 \
  -Dopengpu.fragmentInputNextFieldQualification=candidate \
  -Dopengpu.simCache="$field_workspace/coalescer" \
  'set Test / sources := Seq(baseDirectory.value / "src/test/scala/opengpu/testutil/GpuSim.scala", baseDirectory.value / "src/test/scala/opengpu/graphics/FragmentInputCoalescerSpec.scala")' \
  'testOnly opengpu.graphics.FragmentInputCoalescerSpec -- -z "qualify next-field input reads"'
sbt -J-Xmx1G -J-XX:ActiveProcessorCount=1 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simCcache=off -Dopengpu.simReleaseHeap=0 \
  -Dopengpu.simCache="$field_workspace/stage" \
  'set Test / sources := Seq(baseDirectory.value / "src/test/scala/opengpu/testutil/GpuSim.scala", baseDirectory.value / "scripts/qualification/FragmentOutputRowsConsumerQualificationSpec.scala")' \
  'testOnly opengpu.graphics.FragmentOutputRowsConsumerQualificationSpec -- -z "consume only initialized quad rows at all partial default-capacity output widths"'
```

For control A/B measurements, restore only the corresponding Main source from
`9e884ce41c6f9c0323bab1c5041738cb50a72add` in a second disposable checkout. For
the Reader control only, change the test's `publishedRows` helper from
`lanes/4` to `4`; leave the byte, mask, fault and drain assertions intact. Use
separate fresh simulation caches. The Coalescer test is identical at both
endpoints; set `opengpu.fragmentInputNextFieldQualification=control` for its
control run and `candidate` for its candidate run.

Production PPA uses the stock native target, not a reduced graphics preset:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx1G -J-XX:ActiveProcessorCount=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-fragment-shader generated/ppa-fragment-fields'
python scripts/qualify_ppa.py generated/ppa-fragment-fields FragmentShaderStage \
  --stage synthesis --clock clock --period-ps 1000 --io-delay 0.25 \
  --utilization 25 --density 0.6 --synthesis-engine yosys \
  --yosys-preserve-hierarchy --timeout 3600 --output .artifacts/ppa-fragment-fields
```

The measured qualification uses a 3600-second total bound and three sequential
containers, each limited to one CPU and 6 GiB memory with equal memory-swap and
network disabled; automatic retries are disabled. Apply those bounds when
replaying the stock flow. This is component synthesis/setup only;
no place/route, hold, whole-GPU clock, whole-host performance, 720p or FPS result
is established.
