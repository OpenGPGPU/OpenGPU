# Original VS memory on the owned tessellation input path

`TessellationVertexMemoryAdapter` serializes the original `VertexShaderStage`
line port onto tessellation's existing input transaction zero. It preserves
local tags 0 (vertex input), 1 (scratch/transport) and 2/3 (texture), and echoes
the captured local tag with the unchanged fault and 512-bit data. Only local
tag 1 may write. This does not authorize a sampler, program or GEM view.

The adapter captures the complete request, holds its downstream offer through
backpressure, waits for the actual reply and then retains that reply until the
original local client accepts it. That final delivery releases the single
credit. A queued next local request stays unaccepted until then. A wrong or
unowned reply is not consumed and asserts the transport protocol. Cancellation
has no queue-flush shortcut: the real VS must retain its offered requests and
accept replies before releasing its resources.

## Explicit fabric mode

`TessellationInputMemoryFabric(config, renderIds, ownedVertexSource=true)` adds
four-tag `sourceRequest/sourceResponse` ports, selection and local drain signals:

- `sourceLive` requests source ownership; it is not memory authority.
- `sourceSelected` is the registered actual selection. It changes only after
  both adapter and the underlying staging slot have drained. Unrelated render
  traffic does not prevent VS/legacy handoff.
- `sourceDrained` proves stable drain of this serialized VS/legacy input slot,
  including held requests/replies, grant debts and selection changes. It does
  not authorize frame retirement, a SATP change or a global flush.

The existing `inputRequest/inputResponse` remains the legacy single-tag,
read-only DMA entry, including when the new mode is enabled. The default and
old two-argument constructor retain the old interface and behavior. No default
factory, caller, native getter, serialized ABI or discovery bit enables this
mode.

A pending request from an unselected source may wait for its grant without
preventing that grant. A selected or already captured offer prevents switching,
even before downstream acceptance. An unselected offer observed while its
owner requests selection also creates a retained grant debt. Withdrawing
`sourceLive` cannot revoke that offer: it must be granted, captured and drained
once before the new intent takes effect. The same rule applies to legacy
handback. An unrelated unselected valid without selection intent does not
create a grant debt. Pending replies retain their original owner
through cancellation intent or an early request to hand ownership back. The
public `drained` also remains false for unselected waiting offers, selection
changes or retained flushes, so it cannot authorize releasing their original
VM/resources.

`TileBinHostMemoryFabric.stagingDrained(e)` counts each staging translator's
accepted input through its final local reply delivery. The bounded per-client
credit includes translation, page walks, arbitration, queued external requests,
late responses and local faults; simultaneous accept/retire is supported.
Underflow and capacity assertions apply independently of render/DMA credits.
Held local/physical offers, full-width data/walker tags at the external queue
head and responses targeting that client also prevent its drain. The renderer
and other staging clients do not enter this per-slot predicate. The original
whole-fabric credit and `drained` contract are unchanged.

The renderer and selected input still use the original `TileBinHostMemoryFabric`
and `TranslatedLineClient`. Scratch writes therefore undergo the real Sv32
write permission checks; the option does not bypass translation or make a
read-only mapping writable. The external transaction inventory is unchanged.
The enclosing owner must retain SATP, original views and physical allocations
through all requests, local deliveries and the final drain.

Owned mode captures valid-only TLB flushes until both adapter and fabric drain,
including actual local response delivery. Different overlapping flush scopes
may safely coalesce into a full TLB invalidation. Neither a pending flush nor
`sourceLive` withdraws an already offered request. No extra memory transaction
ID is used. The parent must stop issuing new old-owner work for handoff/flush;
already offered work still drains. The legacy mode keeps its original direct
flush protocol. A pending global flush intentionally prevents local handoff and
keeps `sourceDrained` false until the entire fabric drains and applies it.

## Focused validation and limits

The component tests exercise all four tags, full request fields, held external
offers, late replies, held local results and a queued next offer. The translated
fabric tests request VS before grant, relinquish it during a held page walk,
queue overlapping flushes and hold its final response. They check original
scratch writes against real read-only and read/write PTEs: a denied store faults
without a physical data write, and a writable store receives its real memory
ACK. A subsequent legacy read walks again after flush, then VS can reacquire
the port after a held legacy response. Specific protocol assertions reject
legacy writes in both modes and unowned/nonzero serialized replies.

The focused cases include both legacy assertion modes, two invalid-reply cases
and the full-width staging-tag narrowing check. A queued-offer case
withdraws `sourceLive` in exactly the cycle the next original VS request is
offered; that request and its local reply drain before the grant is released.
An additional case withdraws selection intent before an offered VS request
can acquire the port, then repeats the symmetric legacy handback. Both requests
are granted and delivered before final drain. With the retained grant debt
removed, this same case fails its bounded VS-grant assertion after the actual
legacy reply is delivered.

The independent-drain case holds the renderer's actual local page-fault reply
while VS receives its grant, performs a real Sv32 walk and read, and delivers
its original local tag/data. `sourceDrained` becomes true while the renderer
keeps whole-fabric `drained` false. A second VS request and reply retain their
grant through stalls and cancellation intent; legacy handback and delivery also
finish while the render fault stays held. A requested global flush still waits
for that render reply. Restoring the former global-drain handoff condition
fails the same test's fixed eight-cycle grant assertion, without a process
timeout or a changed resource budget.

## Staged source validation

The exact P39 memory-routing delivery freshly compiled all 769 production
Scala sources with Scala 2.13.18/Chisel 7.15.0 in 88.354 seconds, then four
exact test/helper sources against that output in 5.042 seconds. No existing
GPU class provider was used. All thirteen actual RTL cases passed across
`TessellationInputMemoryFabricSpec` and `TileBinHostMemoryFabricSpec` in
137.863 seconds, including the original multi-client routing regression.
Assertions were enabled, native builds used one job, and executable/object
caches were disabled. All 155 affected-suite selector checks and the
integration/large-PR catalog checks also passed.

Two independently compiled source-copy controls failed only their bounded
behavioral assertions: restoring global-drain handoff failed the eight-cycle
source-grant check; dropping the ungranted VS debt failed `grant/drain wait 5`.
Both compiled successfully and terminated with exactly one failed selected
test, not a process timeout. The unchanged source passed the complete suite.

Production/test compilation had separate 600-second/6144-MiB sampled aggregate
bounds (observed 3523280/442736 KiB). The complete RTL run had a
900-second/1536-MiB bound (observed 599952 KiB). Each control had a separate
180-second/1536-MiB RTL bound; observed maxima were 459056/460624 KiB.
Sampled RSS is not an absolute peak. Source/tool/HEAD/index checks passed
before and after each command; all consumers exited before private classes,
mutants, models and logs were removed.

These tests use modeled physical memory peers and execute the real adapter,
translator, page walker and fabric RTL. They do not run a VS/TCS/TES shader,
prove protected GEM admission or qualify a complete owner. Connecting this
port to the versioned VS-to-patch sequencer and native owned-source contract,
actual shared-CU execution and same-host product qualification remain pending.

Reproduce with the repository JDK/Scala/Chisel and Verilator toolchain:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=1 compile
sbt -J-Xmx768m -J-XX:ActiveProcessorCount=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simCcache=off \
  -Dopengpu.productQualification=0 \
  'testOnly opengpu.system.TessellationInputMemoryFabricSpec opengpu.system.TileBinHostMemoryFabricSpec'
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
git diff --check
```

Keep the existing protected PR CI and the intentionally disabled Scala
regression workflow. This stage does not qualify a complete factory or
actual CU execution and does not close P39/P40 or the product target.
