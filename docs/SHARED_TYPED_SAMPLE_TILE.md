# Canonical typed sample tile storage

`SharedTypedSampleTileStore` connects the generic typed tile policy and native
RGBA8+D24S8 sample pipeline to one canonical 32 KiB SRAM. Eight generic word
ports use the resident pipeline's maintenance service after complete native
drain. Native eligibility requires a single RGBA8 target and D24S8 with
clear/discard starts; other bindings retain generic depth/color and refill
ownership. Both paths share valid/dirty state and the same attachment contents.
Accepted native and generic writes forward pending row metadata until bitmap
commit.
Generic full-word initialization holds its row through the canonical write
response, preventing independent maintenance queues from executing younger
packed writes first. Cohort changes wait for request, reply and metadata
ownership to drain.

The enclosing pixel owner validates and orders quads, selects drained cohorts
and aggregates packet retirements. Native binding must equal the captured tile
descriptor. Read-only virtual depth initialization materializes real SRAM words
without counting a logical depth write. Cancellation retains offered work and
accepted replies until drain before either path can reuse the array.

Three integration tests pass with zero skipped or pending cases. They check
ordered native aliases followed by generic masked updates, all MSAA modes,
read-only clear materialization, cancellation and cold-load fallback reuse.
After warm-up, 128 native quads take 185/313/569 clocks at 1x/2x/4x, including
pipeline fill and drain. Their sample packets retire consecutively at four
sample updates per clock, with exact tags, masks, counters and selected final
canonical words. Lower SRAM tests cover every physical word. These integration
tests use behavioral SRAM; the resident subsystem separately checks macro models.

Recursive emitted RTL contains one banked memory and sixteen SRAM arrays, 100
FP32 FMA lanes, one generic streaming blend/encoder and four native RGBA8 blends.
There is no second attachment array for generic fallback. These are component
structure and functional results; enclosing pixel/renderer timing, arbitrary
format rates, attachment publication and product frame rates need their own
qualification.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.SharedTypedSampleTileStoreSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-shared-typed-sample-policy .artifacts/shared-typed-sample-policy-rtl'
```
