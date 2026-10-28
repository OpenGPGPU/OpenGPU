# Concurrent native texture binding capacity

Two explicit local hardware cases pass on the existing concurrent native-stage
harness. Each stage executes a real CU program with 128 invocations, sixteen
lanes and eight warps. Vertex and fragment tasks overlap on separate CUs and
retain independent roots containing 32 distinct bindings each.

The shader derives its binding index from the invocation ID, covering all 32
indices four times in each healthy task, and samples every RGBA component.
Every result matches the independent image-format reference. Distinct stage
colors and actual texel-line reads check all 64 native binding records.
The vertex shader also exports its selected binding ID.

| Qualification boundary | Result |
|---|---|
| Execution | Two CUs; 128 invocations per stage; concurrent disjoint ownership and both root reads |
| Local payloads | Fixed 64 KiB pool, 16 KiB lease ceiling; one local batch per healthy stage task |
| Controlled spill | The same pool and lease ceiling; one spilled batch per healthy stage task with larger private prefixes |
| Address boundary | Two full binding tables occupy the final 4 KiB of the 32-bit address space; the fragment table ends at exclusive 4 GiB |
| Invalid index and recovery | Vertex index 32 and fragment index 32 independently fault without exports; the peer completes and subsequent healthy tasks succeed |
| Lifetime | Submitted root mutations cannot replace captured resources; completion values remain held; memory, payload reservations and task ownership drain |

Each payload profile executes five draws per stage: healthy, vertex fault,
healthy reuse, fragment fault and healthy reuse. Across both profiles this is
20 native stage tasks, including four expected faults, 1,024 vertex exports,
256 fragment quads and 8,192 checked RGBA component results. Both selected
ScalaTest cases finish with zero failures, errors or skips.

This qualifies the native stage namespace through actual CU execution. It does
not declare 64 combined API texture units: API unit sharing, active-sampler
accounting and linker/binding rules remain separate contracts. The fixture has
two CUs, small caches, bare translation and synthetic fragment inputs; it does
not qualify the full four-CU host, a framebuffer, general resource visibility,
whole-host timing or 720p frame rate. H6 remains open.

Run each profile in a fresh JVM outside PR/nightly lanes:

```sh
git submodule update --init depends/asap7_sram_0p0
for profile in local spill; do
  sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 \
    -Dopengpu.productQualification=0 -Dopengpu.simJobs=1 \
    -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
    -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
    -Dopengpu.simHierarchy=GpuComputeUnit \
    -Dopengpu.textureBindingPayload="$profile" \
    "Test / runMain opengpu.graphics.QualifyNativeTextureBindingCapacity --reports .artifacts/native-texture-binding-capacity/$profile" || exit
done
```

The entry point checks exact selected test names/counts and rejects failed,
errored or skipped XML cases. Its suite is excluded from automatic discovery.
Raw logs, XML and source/class inventories remain local under `.artifacts/`.
