# Typed image transfer in the full host

This manual qualification measures the combined identity, store-tail and
gather-tail changes in `TypedTileImageTransfer`. Both endpoints use the same
non-TTI Main sources from `f58edaca48ee910d461b1a030d11d215a992c305`:
control restores only TTI from `1734c580d132fd031d7a5d71d35e2e88d8e800af`,
and candidate uses TTI from `42e1eace153a7715f5da20beb9adb71d07d6ac27`,
byte-identical to its merged source at `6b1fc038b97d68731a57ed6c36de44f6f2ae4f6f`.

The fixture renders an active 4x4 image in guarded 7x5 views through the complete
host with default capacities at two CUs. `GraphicsConfig()` retains its default
128x128 maximum; this is the two-CU/default graphics profile. `GpuHostSystemAxi`
uses 16 lanes x 8 warps, the 128-thread shader ABI, full compute services,
default caches and 32768-byte TTI, 16 attribute/varying slots and four render
targets. Product RTL capacities remain unchanged. Actual captured RTL has one
TTI with 12-bit local addresses and 264 native32 plus 700 native64 SRAM instances. Shader cases cover
triangle/line/point draws, four color formats and D24S8, at 1x and 4x sampling.

| Probe | Control host clocks (1 GHz µs) | Candidate host clocks (1 GHz µs) | Saved |
| --- | ---: | ---: | ---: |
| Clear, 1x | 1063 (1.063) | 903 (0.903) | 160 / 15.05% |
| Shader, 1x | 17400 (17.400) | 17000 (17.000) | 400 / 2.30% |
| Shader, 4x | 27311 (27.311) | 27167 (27.167) | 144 / 0.53% |

All six cases passed. Control service clocks are 1060, 17397 and 27308;
candidate service clocks are 900, 16997 and 27164 respectively. Each case checks
exact source/resolved images and untouched guards, balanced
memory credits, held completion and a 16-cycle quiet tail. A/B comparison also
requires exact traffic counts and per-address writes, and the same physical
read-address multiset; all matched. Each endpoint built one native model and
reused it twice through the normal content-derived cache.

Host timing starts before the SUBMIT control-write call and ends at IRQ with
accepted R/B drained; it includes the control-write call, rather than starting
at the accepted SUBMIT handshake. Service clocks are separate. At an assumed
1 GHz, host microseconds equal clocks x 0.001. These small fixtures establish no
720p/FPS estimate or full-host timing closure. The four-CU/720p product target
requires its own qualification.

## Manual reproduction

The [fixture](../../scripts/qualification/H6TypedWholeHostPerfSpec.scala) is a
byte-identical copy of the qualified source (SHA256
`2c3e6c1610e6e63e52abfa4d0d7b98e97c0dfc869e4eb4637eb0fb26b63f20b2`).
It reuses the repository's twelve Test driver/observer/reference sources from
`f58edaca`, plus existing GpuSim, FP32 and typed-color helpers. It is outside
the normal Test source directories and adds no PR/nightly case.

Use a clean disposable checkout of this qualification commit. The recorded
toolchain was Scala 2.13.18 / Chisel 7.15.0, JDK 21.0.7, firtool 1.158.0,
Verilator 5.050 and GNU make 4.4.1 on arm64 macOS. Espresso must be on PATH;
the measured binary SHA256 was
`9416c0c80c016d54fc40ae8de8517fefaa01d43cb05a7e818845088c71fb1ef6`.
Recorded limits were CPU2, Xmx3G, owned RSS 10 GiB including coordinator,
make j1, 1800 seconds per model with its three cases, and zero retries.

Run the following separately for each endpoint with a fresh workspace. Use
`variant=control` and `ref=1734c580d132fd031d7a5d71d35e2e88d8e800af`
for control; the defaults below select candidate. Repository compilation is
outside the measured host-clock interval.

```sh
set -eu
git submodule update --init depends/asap7_sram_0p0
command -v espresso
variant=candidate
ref=6b1fc038b97d68731a57ed6c36de44f6f2ae4f6f
tti=src/main/scala/opengpu/graphics/TypedTileImageTransfer.scala
git show "$ref:$tti" > "$tti"
workspace="$PWD/.artifacts/tti-whole-host-$variant"
test ! -e "$workspace"
for title in \
  'should measure complete host clear publication at 1x with exact 7x5 guards' \
  'should measure complete host shader publication at 1x with exact 7x5 guards' \
  'should measure complete host shader publication at 4x with exact 7x5 guards'
do
  sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
    -Dopengpu.productQualification=0 -Dopengpu.simJobs=1 \
    -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
    -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
    -Dopengpu.simCcache=off -Dopengpu.simCache="$workspace" \
    -Dopengpu.simWorkspace="$workspace" \
    'set Test / unmanagedSources += baseDirectory.value / "scripts/qualification/H6TypedWholeHostPerfSpec.scala"' \
    'set Test / parallelExecution := false' \
    "testOnly opengpu.graphics.H6TypedWholeHostPerfSpec -- -t \"$title\""
done
git restore -- "$tti"
```

The actual qualification used focused compiled classpaths and a process-group
RSS/time watchdog. The commands above rebuild from repository sources without
depending on temporary `.artifacts` classes. Use the same resource limits and
keep heavy qualification explicitly scheduled outside routine CI.
