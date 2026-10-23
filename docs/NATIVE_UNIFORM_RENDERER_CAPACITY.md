# Native uniform and varying capacity through the typed renderer

`NativeUniformRendererCapacityHostAxiSpec` passes four cases through the actual
typed `GpuHostSystemAxi`: two uniform-ownership cases and two full-width varying
consumption cases. They use sixteen lanes, eight warps, two shared CUs, sixteen
attribute bindings and sixty-four user varying components. Each frame renders
one draw of 128 identical points on an 8×8 surface. The small image keeps routine
validation independent of display resolution while exercising full shader
thread and interface capacities.

Both payload profiles pass. The enabled profile retains its fixed 64 KiB pool
and 16 KiB reservation bound. The 69,120-byte vertex and 45,056-byte fragment
private prefixes spill; capacity is not increased to make the checks fit. The
other profile stages payloads directly in global memory.

Each ownership case completes four submissions: healthy, GPU-published
rebinding, a denied fragment uniform-data page, and healthy reuse. Both shader
stages share one immutable twenty-four-binding table with twelve mappings per
stage and 16 KiB views. Scalar/vector loads read every binding's final word and
the first/last default words. A real CU producer updates roots, view bounds and
data before event-based publication releases native admission. The rebound
allocation supplies the changed exact image values; the obsolete 16 KiB
allocation receives no physical reads or writes. A denied page produces the
expected frame error without a physical data read.

Healthy frames compare 27,264 scratch words against independent expectations:
all sixteen input attributes, invocation IDs, positions, all sixty-four vertex
outputs and fragment inputs, and all four MRT exports across 128 lanes. Distinct
attribute bindings carry distinct component markers. The image oracle checks
MRT, depth, store/resolve results and allocation guards. All default tails remain
unchanged. Both Sv32 instruction/vector contexts remain pinned while completion
is held; payload reservations and pins drain before reuse.

The two additional consumption cases each run one healthy frame. Their fragment
shader explicitly loads all sixty-four varying rows and folds them with a
32-bit rotate, XOR and row-dependent addition. A separate Scala reference
checks the resulting checksum in every one of 128 output lanes. The raw depth
output word carries this checksum with depth override disabled, preserving the
independent image oracle. The original ownership cases remain unchanged; the
extra checksum cases establish shader consumption beyond staging the interface.
All four capacity cases pass with zero failed, canceled, ignored or pending
tests. The original two compact ownership cases also pass separately, retaining
their ASID-remapping and twelve accepted-read reset cuts.

`NativeUniformRendererFourCuHostAxiSpec` repeats all four capacity cases with
four CUs and `MobileGpuConfig.execution`: sixteen lanes, eight warps, a 128 KiB
L2 and four store entries. Instruction and vector caches retain their default
sixty-four sets and two ways. Both payload profiles pass with the same fixed
64 KiB pool and 16 KiB reservation bound, exact scratch/image oracles, publication,
permission failure/recovery and full-width shader consumption. All four cases
finish with zero failed, canceled, ignored or pending tests. The original compact
and two-CU profiles retain identical configurations, shader programs and case
registrations.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'set Test / logBuffered := false' \
  'testOnly opengpu.graphics.NativeUniformRendererCapacityHostAxiSpec -- -z "native uniform table ownership"'
sbt -J-Xmx6G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'set Test / logBuffered := false' \
  'testOnly opengpu.graphics.NativeUniformRendererCapacityHostAxiSpec -- -z "consume all sixty-four varying components"'
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' 'set Test / logBuffered := false' \
  'testOnly opengpu.graphics.NativeUniformRendererFourCuHostAxiSpec'
```

This qualifies the stated typed renderer configurations at two and four CUs.
It does not establish four-way occupancy/scaling, complete product or
full-capacity binned runtime, nonunit-W full-width perspective, mixed centroid/flat qualifiers, concurrent reset/remapping,
general context/cache coherence, rate budgets or an enclosing renderer clock.
[Full-width native affine checks](NATIVE_FULL_VARYING_INTERPOLATION.md) separately
cover nonconstant triangle values in every fragment lane at 1×/4× MSAA.
The [concurrent stage checks](NATIVE_UNIFORM_CONCURRENT_CAPACITY.md),
[compact translated host checks](NATIVE_UNIFORM_HOST_OWNERSHIP.md) and
[component timing](NATIVE_UNIFORM_BINDINGS.md) retain their separate scopes.
API compiler/driver integration and the remaining H0–H8 requirements stay open.
