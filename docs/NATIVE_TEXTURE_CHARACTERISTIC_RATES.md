# Native texture characteristic rates

The alignment throughput gate requires separate measurements for trilinear
sampling, misses, bank conflicts and dependent samples. All four explicit local
cases pass with the existing native endpoint/pool RTL: four-CU capacity, two
physical clusters, sixteen lanes, eight resident warps and three configured
clients. Two endpoints use opposite CU pairs. Texture dimensions are at most
128x128; no frame is rendered.

Each cluster produces the same counts below. Native clocks span the first
measured vector admission through the final vector result, after setup and one
warm vector. They include pipeline overhead and dependent parameter updates.
They are hardware clocks, separate from simulator wall time.

| Case | Samples per cluster | Native clocks | Bilinear groups | Descriptor read lines | External texel read lines |
|---|---:|---:|---:|---:|---:|
| Trilinear | 512 | 185,345 | 1,024 | 1,536 | 0 |
| Miss pressure | 512 | 11,389 | 512 | 0 | 1,024 |
| Same-bank taps | 512 | 2,148 | 512 | 0 | 0 |
| Dependent samples | 256 | 2,216 | 256 | 0 | 0 |

Trilinear uses two real mip levels, two banked bilinear groups per sample and
the native FP32 mip mix. Its general owner still rereads metadata and has low
throughput. Two groups per sample is a work count, not a two-clock result rate.
It does not use the single-level streaming shortcut.

The miss case cycles a 256-line working set through each 128-line/8 KiB cache,
with 17-clock data-response delay. The four unique taps in the bank case all
select bank zero through odd-size repeat wrapping. Its central 448 groups take
1,789 clocks. The dependent case feeds the preceding actual red result into
the next coordinate and verifies changing results; each of its fifteen tuple
updates resubmits all eight parameter pairs. Its clocks include those updates.

Every vector word matches the independent image-format/FP32 filtering oracle.
Checks also require private reply order, exact physical read charges, delayed
memory/drain correctness and zero generic texel-read bypass. Bare translation
is deliberate here; translated faults, cold ownership and renderer binding/
visibility have separate qualifications.

These measurements complement the separate conflict-free resident-data II1
gate. They characterize native endpoints, without a CU dependency scoreboard,
full renderer or product frame. No whole-host clock, 720p FPS, routed timing or
power result follows from them. Current complete component timing and the
remaining H4/H6 acceptance requirements still apply.

Run each profile in a fresh JVM, outside PR/nightly lanes:

```sh
git submodule update --init depends/asap7_sram_0p0
for profile in trilinear misses bank-conflicts dependent; do
  sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 \
    -Dopengpu.productQualification=0 -Dopengpu.simJobs=1 \
    -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
    -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
    -Dopengpu.textureRateProfile="$profile" \
    "Test / runMain opengpu.graphics.QualifyNativeTextureOtherRates --reports .artifacts/native-texture-characteristics/$profile" || exit
done
```

The entry point requires the selected actual test name/count and rejects failed,
errored or skipped XML cases. The suite is excluded from automatic discovery.
Keep raw logs, XML, source/class manifests and fixture-failure evidence local
under `.artifacts/`; this guide records final measurements and their limits.
