# Streaming typed color contexts

`StreamingTypedTileColorLanes` snapshots one quad and its attachment descriptor
into 64 sample/location jobs: four pixels, four samples and four color outputs.
The jobs share one streaming typed blend and one streaming typed encoder.
Unblended samples of a location reuse their packed source value. Four word
ports provide a comparison profile; eight ports split each pixel's even and
odd color locations so their word operations can progress independently.

The caller validates layouts and quads, starts all four lanes together, supplies
initialized/coherent old words and orders overlapping quads. Each word port
retains one offered request and one accepted owner through its reply. Masked
writes preserve packed neighbors. Successful acknowledgements determine write
and written-bit counts. The parent SRAM service must merge simultaneous masked
updates atomically and retain its clear/load/refill and pass-lifetime policy.

Cancellation and faults stop new effects while offered word requests and
arithmetic/encoder work drain. Held offers remain immutable. Faults retain the
original word offset, and a healthy quad may start only after complete drain.
Already acknowledged effects remain committed.

Three tests pass with zero skipped or pending cases. Four- and eight-port models
each check all 36 formats with and without blending across four color outputs,
sparse samples, all three MSAA modes and absent channel masks. Exact final words,
read counts, successful write acknowledgements, written bits and logical
sample/location counts match independent references. A separate case checks
held-write cancellation, a write fault, reference data under committed masks
and healthy reuse after each drain. These component tests do not establish
whole-renderer throughput, attachment publication or a product frame rate.

Complete synthesis of the eight-port context owner passes at 1,000 ps with
25% I/O delay: core slack +9.209 ps, I/O slack +565.929 ps, zero skipped or
unresolved mappings. Four-port timing and the enclosing tile policy/renderer
require their own qualification.

```sh
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.StreamingTypedTileColorLanesSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-streaming-color-contexts .artifacts/streaming-color-contexts-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/streaming-color-contexts-rtl StreamingTypedTileColorLanes \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/streaming-color-contexts-synthesis
```
