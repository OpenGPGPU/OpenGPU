# Concurrent native texture binding capacity

Four explicit local hardware cases pass: two retain the original unbanked
concurrent-stage path, and two exercise the production banked pool. Each stage
executes a real CU program with 128 invocations, sixteen lanes and eight warps.
Vertex and fragment tasks overlap on separate CUs and retain independent roots
containing 32 distinct bindings each.

The shader derives its binding index from the invocation ID, covering all 32
indices four times in each healthy task, and samples every RGBA component.
Every result matches the independent image-format reference. Distinct stage
colors and actual texel-line reads check all 64 native binding records.
The vertex shader also exports its selected binding ID.

| Qualification boundary | Result |
|---|---|
| Original namespace path | Two CUs; 128 invocations per stage; concurrent disjoint ownership and both root reads |
| Banked path | Four configured CUs, two physical clusters, five pool clients, 128 response rows per cluster and 28 characterized SRAMs; both native stages require successful nonfallback banked replies for all 32 bindings |
| Local payloads | Fixed 64 KiB pool, 16 KiB lease ceiling; one local batch per healthy stage task |
| Controlled spill | The same pool and lease ceiling; one spilled batch per healthy stage task with larger private prefixes |
| Address boundary | Two full binding tables occupy the final 4 KiB of the 32-bit address space; the fragment table ends at exclusive 4 GiB |
| Invalid index and recovery | Vertex index 32 and fragment index 32 independently fault without exports; the peer completes and subsequent healthy tasks succeed |
| Lifetime | Submitted root mutations cannot replace captured resources; completion values remain held; memory, payload reservations and task ownership drain |

Each payload profile executes five draws per stage: healthy, vertex fault,
healthy reuse, fragment fault and healthy reuse. Each pair of local/spill cases
executes 20 native stage tasks, including four expected faults, 1,024 vertex
exports, 256 fragment quads and 8,192 checked RGBA component results. Both pairs
finish with zero failures, errors or skips.

The banked fixture uses aligned RGBA8 views with bilinear sampling. A real
compute loop occupies CU0 so the dispatcher places the graphics tasks on CU1
and CU2, reaching opposite physical clusters without forged ownership masks.
Public pool handshakes check selected binding, tag, identity and exact RGBA;
a fallback response cannot count as banked coverage. Both cluster data ports
must issue reads. The blocker is reset only after all five draws and every
graphics, texture, payload and external-memory owner have drained.

This qualifies the native stage namespace through actual CU execution. It does
not declare 64 combined API texture units: API unit sharing, active-sampler
accounting and linker/binding rules remain separate contracts. The fixtures use
small caches, bare translation and synthetic fragment inputs. The banked check
configures four CUs but runs two graphics owners alongside its compute blocker;
it does not qualify four simultaneous graphics tasks, a full host framebuffer,
general resource visibility,
whole-host timing or 720p frame rate. H6 remains open.

Run each qualifier and payload profile in a fresh JVM outside PR/nightly lanes:

```sh
git submodule update --init depends/asap7_sram_0p0
for qualifier in NativeTextureBindingCapacity NativeBankedTextureBindingCapacity; do
  for profile in local spill; do
    sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 \
      -Dopengpu.productQualification=0 -Dopengpu.simJobs=1 \
      -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
      -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
      -Dopengpu.simHierarchy=GpuComputeUnit \
      -Dopengpu.textureBindingPayload="$profile" \
      "Test / runMain opengpu.graphics.Qualify${qualifier} --reports .artifacts/${qualifier}/${profile}" || exit
  done
done
```

Keep `opengpu.simHierarchy=GpuComputeUnit`; the banked pool and its assertions
remain together in the top model. Its fixture includes the pinned SRAM models.

Each entry point checks exact selected test names/counts and rejects failed,
errored or skipped XML cases. Each suite is excluded from automatic discovery.
Raw logs, XML and source/class inventories remain local under `.artifacts/`.
