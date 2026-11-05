# Native tessellation command transport

The opt-in three-domain native host reports its internal GNQ1/GNQ2/GNQ3
command transport in GCR1 bit 11. Bit 12 additionally reports transform
feedback for that transport. The bits require the protected native host and
are clear on the default product profile. They do not advertise a userspace
submission interface or GLES conformance. The driver rejects feedback without
the command bit and rejects either advertised transport on an unprotected or
non-unified host. When the command bit is present, it also validates the GNC
native-host record and records its actual engine count. All current GNQ1,
GNQ2 and GNQ3 image views use the context VM. The shared GNC1/GNC4 physical
image setting applies to GNJ jobs; it does not change tessellation image
translation. An absent command bit does not probe the optional record.

`GpuHostSystemAxi` publishes the GNJ image mode from
`NativeBinnedGraphicsConfig.translateImageViews`. Its three-domain GNQ owner
always routes renderer requests through `TessellationInputMemoryFabric` and
the `TileBinHostMemoryFabric` Sv32 render client. The driver therefore checks
GNQ image allocations against binding VAs for all four GNC profiles while
retaining the shared record and engine-count validation.

`opengpu_hw_tessellation_async` requires driver-owned GNQ, GNF1 and, for a
nonempty job, GNT draw-array snapshots. It can also verify a supplied
driver-owned fragment-state arena. It checks the job and frame headers,
identities and descriptor bounds, and rejects overlap of descriptor VAs with
the arena, pool, directory and active-engine scratch ranges. Every frame and
draw word must have the canonical padding defined by the checked hardware ABI
schemas; each draw also passes the patch-input arithmetic
precheck. The driver uses the discovered engine count for the scratch-alias
check. Private VMs can map snapshots with read-only, non-executable leaves;
the mapping operation refuses to replace an existing leaf and uses the normal
TLB-shootdown and rollback path. Before submission, the driver checks that
every descriptor VA resolves to the corresponding DMA page and scans the same
VM for writable mappings of those DMA pages. The check includes the full
strided draw-array span and, when supplied, the exact frame state range. It
fails closed on unowned page tables. Bare/ASID-0
mode only checks address identity and is suitable solely for trusted callers.

The caller must still validate remaining frame, draw and resource semantics
and permissions, prevent CPU writes and physical or IOMMU aliases (including
different DMA addresses for the same backing), and retain immutable snapshots,
backing allocations, VM mappings and context until the returned fence retires.
It must also protect page tables from CPU or GPU writes and reject aliases in
other contexts. Translated image backing still needs physical-alias protection.
These checks do not establish ownership of arbitrary GEM resources. No
userspace ioctl calls this internal transport yet.

`opengpu_tessellation_sched_submit` connects that transport to the DRM
scheduler for a trusted kernel producer. It copies GNQ, GNF, the complete
strided GNT span and any nonempty fragment-state arena into job-owned DMA
buffers, replaces their addresses with separate private VM windows, and
rejects malformed or wrong-context snapshots before enqueue. At execution it
maps those buffers read-only, verifies their exact backing, submits the command and keeps the
mappings, snapshots, context and declared GEM dependencies until retirement.
The producer supplies stable kernel bytes for the full state range; a missing
or short source is rejected. An empty state range needs no copy. The private
state window supports up to 256 MiB, subject to backing memory and available
per-context page tables; its exclusive limit may be 2^32. Empty jobs need no
draw mapping.
For each nonempty draw that reads patch inputs, the scheduler also requires
the full 64-byte-aligned input allocation to lie within one mapped vertex
buffer binding in that context. It checks the copied draw snapshot and requires
the binding's GEM object as a dependency that waits for prior writes and
publishes at least a read fence. The context binding cannot be replaced or
unbound until the job's fence completes. Empty sequences and patches with no
input components need no input binding.

The scheduler also checks nonempty bin arena, pool, directory and per-engine
scratch ranges, and active image-view and resolve allocations from
the copied GNF frame. Each complete 64-byte bus-line range must fit a mapped
compute-storage binding in that context. Image allocations and the other
ranges use context virtual addresses. Read-only ranges require a dependency
that waits for prior writes and publishes at least a read fence. Writable
ranges require a dependency that waits for prior reads and writes and
publishes a write fence.
The same checks require patch inputs and frame resources to use locally
allocated GEM objects; imported DMA-BUF backing is currently rejected because
its physical aliases cannot be established here. The scheduler conservatively
rejects overlap whenever either checked range can be written, including GEM
offset and DMA-address aliases, and rejects overlap with descriptor snapshots.
Some otherwise valid sharing is therefore not accepted yet.

When a state table is supplied, each nonempty, non-discard draw's referenced
GFS1 entry is checked in the copied snapshot before enqueue. Its generated
canonical word encoding, header, handle, context/pass/generation, engine count
and per-engine scratch views must match the frame's bounded per-state slots.
All table slots must fit the active scratch arenas, including an exclusive
limit at 4 GiB. Unreferenced state entries may remain uninitialized. A nonempty
draw without a copied table is rejected before enqueue, including a draw with
no patch-input components. This matches the image owner's required-state
admission before the scheduler publishes work or a completion fence.
Zero-count sequences do not reference a state, and raster-discard jobs retain
their existing trusted-producer contract.

Each referenced GFS1 state's texture and uniform root pointers are also checked,
including draws with no patch-input components. Version zero requires address
zero. Version one requires a complete, 64-byte-aligned GXR1 root of 128 bytes or
GUR1 root of 64 bytes; an exclusive end at 2^32 is accepted. These are context
VAs and must fit one locally allocated, mapped compute-storage binding with a
dependency that waits for prior writes and publishes at least a read fence.
Kernarg and other binding placements are not admitted by this path. The
canonical generator derives the field positions and root sizes from the checked
ABI schemas without including userspace-only headers in the kernel.

The root references reject DMA overlap with the copied descriptor buffers and
DMA or same-GEM offset overlap with writable frame resources. Shared read-only
roots and read-only patch/image aliases are allowed. The existing context fence
keeps the bindings stable until retirement, and the dependency retains the GEM
backing. This checks the root envelope and dependency; it does not copy or
decode root bytes, protect them from arbitrary shader or CPU writes, validate
their indirect tables, or qualify the underlying resource data or code. Storage
PTE permissions are unchanged. Unreferenced states, zero-count sequences and
discard-only jobs do not add root dependencies.

These checks do not validate all GFS1 semantics, shader code or records
referenced by other bin data, and do not establish complete resource ownership
or isolation. The trusted producer still has to make input data visible to the
GPU and prevent CPU writes or aliases through other contexts and IOMMU mappings. It must also
validate the full frame and draw semantics and all indirect resources, such as
shader state and code and feedback buffers, including their physical aliases.
The producer must hold the context and dependency reservation locks through
enqueue, supply stable kernel snapshots, and validate, map and synchronize all
referenced resources. The scheduler does not infer the entire resource set from
raw descriptors. There is no userspace entry point until those obligations can
be enforced.
Draw arrays have a separate private VA region below the kernarg windows;
allocation and the available per-context page tables still bound their size.

If temporary VM revocation fails during retirement, the scheduler wedges the
device and retains the job, context and all dependency references instead of
freeing backing that the device might still reach. That failed job is retained
for the life of the kernel; automatic recovery and reclamation are not yet
implemented.

Under the submission lock, the driver checks the execution owner and waits for
idle before reading the snapshots. It then verifies their mappings, orders CPU
writes, invalidates the GNQ, frame, nonempty draw array and other supplied
snapshots, selects the VM and publishes the unified command. The command uses
these fields:

| Register | Tessellation meaning |
|---|---|
| `SOURCE` | 64-byte-aligned GNQ virtual address |
| `DESTINATION`, `PATTERN[0]` | Low 32 bits and bit 32 of its exclusive limit |
| `BYTES` | 512 |
| `WIDTH` | Context decoded from the GNQ snapshot |
| `HEIGHT`, `SOURCE_STRIDE` | Feedback program and cookie |
| `FLAGS` | Optional raster discard and feedback capture only |

Wait/signal events are rejected for this command. A limit at 2^32 is encoded
without truncation; the currently integrated host reports zero completed bytes
for tessellation. Feedback flags and scope values are rejected when that
transport is absent. Failure before publication returns no fence and does not
ring the tessellation doorbell; prior cache invalidation commands may have run.

The bounded AXI tests cover discovery with and without feedback, read-only
flags, protected ownership, the 2^32 limit, query context and feedback scope.
The C tests exercise every generated canonical word rule and the production
command writer for all three GNQ formats, two strided draw snapshots, invalid
frame/draw encodings, all four GNC profiles with one through four engines and
virtual GNQ image views, unchanged outputs after failed discovery, ordering, the
same boundary, unavailable features and invalidation/busy/idle failures. MMU
tests cover snapshot permissions, exact backing, writable aliases, existing
mappings, rollback and page boundaries. A small RTL page-walker test checks
that the emitted leaf permits reads and faults stores and instruction fetches.
The scheduler integration test runs the production scheduler, MMU and transport
against a fake command device, including copied records, context/dependency
retention, window conflicts, writable aliases, abort and failed revocation. It
checks active inputs in two strided draws for each domain, including missing
dependencies, incorrect fence usages, wrong binding types and short ranges.
It checks frame ranges and all six image and resolve allocations in both image
addressing modes, with missing fences, imported backing, short bindings and
physical or object-offset aliases rejected. It checks state-copy lifetime,
read-only mappings, writable aliases, short sources and the 2^32 limit. The
GFS1 tests reject absent tables, bad headers, padding, identities, handles and
scratch slots, including a second strided draw; unused table entries are left
untouched. Missing-table checks cover all three domains and both strided draw
positions, verify no enqueue, MMIO or leaked references, and reuse the context
with zero-count work. Source-only discard jobs and copied-state jobs retain
their existing submission and retirement checks.
The rooted C admission witnesses cover both resource kinds in both strided draw
positions for all three domains. They reject missing or unsuitable dependencies,
unmapped binding metadata, wrong binding types, imported backing, short ranges,
invalid pointer versions, address overflow and writable DMA/GEM aliases before
enqueue, fence publication or MMIO, with no leaked references. Healthy reuse,
shared read-only roots, patch/image aliases and complete admission at the 2^32
boundary pass. These witnesses use constructed GFS snapshots and a fake command
device; they do not execute shader resource instructions or inspect root bytes.
The fake scheduler checks its completion-fence pool bounds, prevents reuse of
referenced slots and releases the final context fence at teardown.
Records generated by the existing three-domain host fixtures, including their
GFS1 states, pass with patch inputs, frame scratch and image views relocated to
mapped driver VAs. Those generated fixtures disable texture and uniform roots;
they do not establish rooted resource execution. The resource helper retains
its separate synthetic physical/virtual range checks. This does not execute a
scheduled job on a real or simulated GPU. The existing [4×4 native host test](../src/test/scala/opengpu/graphics/TessellationNativeHostAxiImageSpec.scala) checks the translated image
mapping from VA `0x600000` to PA `0xa00000` in the default GNC2 profile; it does
not qualify GNC1/GNC4 whole-host execution.
Separate fragment-stage RTL component cases pass exact-word uniform loads and
all 32 texture binding selections, including resources at the exclusive address
limit. The address-translator component also passes a nonidentity read and a
cached read-only permission fault. These exercise resource instructions and
translation independently, without combining them with tessellation submission.
Actual generated host snapshots for all three domains pass the combined
precheck. The Linux 7.2 guest driver also builds. Run:

The host driver suite runs the C job validator and the kernel-header ABI syntax
check directly. Changes limited to those C validator files therefore do not
compile Scala just to repeat the same checks; generated ABI changes retain
their existing Scala selection.

```sh
python3 scripts/test_tessellation_canonical.py
python3 scripts/test_tessellation_transport_submit.py
python3 scripts/test_tessellation_schedule.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_tessellation_schedule.py
python3 scripts/test_driver.py
bash scripts/test_scripts.sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.Sv32PageTableWalkerSpec -- -z "read-only non-executable snapshot"' \
  'testOnly opengpu.core.memory.GraphicsAddressTranslatorSpec -- -z "translate a nonidentity"' \
  'testOnly opengpu.core.memory.VectorTlbSpec -- -z "reject a store without write"'
sbt 'testOnly opengpu.graphics.TessellationNativeHostDomainFixtureSpec -- -z "decode actual"'
sbt 'testOnly opengpu.system.TessellationNativeJobValidateSpec opengpu.system.TessellationNativeRecordKernelAbiSpec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderUniformExecuteUnitSpec -- -z "retire exact words and coalesce identical active operands stage=1 lanes=4"' \
  'testOnly opengpu.graphics.NativeShaderTextureUnitSpec -- -z "fetch all 32 resident bindings at the exclusive address limit stage=1"' \
  'testOnly opengpu.core.memory.GraphicsAddressTranslatorSpec -- -z "translate a nonidentity page and enforce cached write permission"'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeWideTileGridEncodingSpec opengpu.system.TessellationHostAxiCommandSpec opengpu.system.ComputeResourceCapabilitiesAxiSpec'
```

With the ARTI Linux build paths configured, reproduce the guest driver build
with:

```sh
ARTI_DIR=/path/to/arti ARTI_WORK=/path/to/arti-work \
  LINUX_BUILD=/path/to/arti-work/arti-linux-build \
  bash scripts/build_arti_guest_driver.sh /tmp/opengpu-tessellation-driver
```

Fresh default 720p workload and whole-host emissions match the `7617dcf4`
qualification baseline after normalizing source-location comments. The six
workload fixtures and their manifest match byte for byte. Reproduction commands
are in the [qualification notes](TESSELLATION_QUAD_EVALUATION.md#native-fragment-image-coexistence).

These checks do not establish userspace submission, full resource validation,
cross-domain ordering, product timing, or H8 completion.
