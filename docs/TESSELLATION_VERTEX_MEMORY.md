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
four-tag `sourceRequest/sourceResponse` ports and two selection signals:

- `sourceLive` requests source ownership; it is not memory authority.
- `sourceSelected` is the registered actual selection. It changes only after
  both adapter and underlying translated fabric have drained.

The existing `inputRequest/inputResponse` remains the legacy single-tag,
read-only DMA entry, including when the new mode is enabled. The default and
old two-argument constructor retain the old interface and behavior. No default
factory, caller, native getter, serialized ABI or discovery bit enables this
mode.

A pending request from an unselected source may wait for its grant without
preventing that grant. A selected or already captured offer prevents switching,
even before downstream acceptance. Pending replies retain their original owner
through cancellation intent or an early request to hand ownership back. The
public `drained` also remains false for unselected waiting offers, selection
changes or retained flushes, so it cannot authorize releasing their original
VM/resources.

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
flush protocol.

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

Final local results: seven focused ScalaTest cases passed (nine component
simulations, including both legacy assertion modes, two invalid-reply cases and
the full-width staging-tag narrowing check). A separate queued-offer case
withdraws `sourceLive` in exactly the cycle the next original VS request is
offered; that request and its local reply drain before the grant is released.
The four current adapter/fabric/helper/test sources passed Chisel-plugin
incremental compilation, and 147 CPU suite-selector tests passed.

These tests use modeled physical memory peers and execute the real adapter,
translator, page walker and fabric RTL. They do not run a VS/TCS/TES shader,
prove protected GEM admission or qualify a complete owner. Connecting this
port to the versioned VS-to-patch sequencer and native owned-source contract,
actual shared-CU execution and same-host product qualification remain pending.

Focused reproduction in an explicitly permitted resource window:

```sh
sbt 'testOnly opengpu.system.TessellationInputMemoryFabricSpec'
python3 -B scripts/test_changed_test_suites.py
```

Do not dispatch or enable Scala regression. The local bounded check compiles
current adapter/fabric/test sources with the Chisel plugin and a read-only
frozen dependency classpath. Each separate simulation process has a 180-second
limit, one CPU, 128MiB Java heap/128MiB metaspace/32MiB code cache, one Verilator
job, optimization zero and simulation cache disabled. This incremental component
check does not replace a complete current-source build or actual CU execution.
