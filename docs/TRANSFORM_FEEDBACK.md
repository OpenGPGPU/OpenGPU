# Transform-feedback hardware

Status: optional native typed/binned feedback control,capture and scoped
recovery pass the current reduced-capacity AXI qualification. Full-capacity
runtime,composed timing/resource budgets and primitive-query integration remain
open. Compiler/driver implementation is outside the hardware alignment plan;
its required hardware interfaces are in scope. PPA uses synthesis only.

[TransformFeedbackExport](../src/main/scala/opengpu/graphics/TransformFeedbackExport.scala)
accepts complete raw assembled primitives before flat shading or clipping.
It captures up to64 selected32-bit components in an interleaved buffer, or
up to four components in each of four separate bindings. Position, raw
varyings and point size use the existing vertex-output ABI; the shader
produces promoted32-bit values. No shader instruction encoding changes.

The semantic baseline is GLES3.0.6 sections2.15,2.16 and2.17: capture precedes
flat shading/clipping, incomplete primitives do not write, paused objects
preserve append positions, and written-primitive counts advance for captured
primitives. API draw-mode/index and object/program validation remain software
responsibilities. [Khronos specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)

The default controller retains eight object rows keyed by opaque context,full
VM root and reuse cookie. At most one object runs per context/root; paused
objects may remain resident while another object is bound. Explicit paused
eviction and checkpoint restore allow the enclosing scheduler to virtualize
this bounded capacity. Checkpoints include immutable program/resource views,
selectors,byte offsets,64-bit primitive/vertex counts and fault state. The
caller retains allocation and program leases across pause/eviction.

Begin resets cursors/counters and snapshots descriptors. End releases its
row with an acknowledged checkpoint. Pause/resume preserve cursors; resume
rejects another running object in the same context/root. Commands serialize
with complete primitive/public-result drain. Restores validate component
limits,selectors,word alignment and bounded cursors. Native serialization uses the bounded GTF1 record below.

Each primitive validates every enabled destination before its first store.
Dense words coalesce into masked64-byte writes, preserving packed neighbors.
Only one data transaction is outstanding; ID0 and `memorySatp` remain owned
through the response. Append cursors and written-primitive counts advance
only after the final successful acknowledgement. Partial memory-fault stores
are not a completed primitive; their object fault remains latched. No atomic
rollback of physical memory is promised.

Reset blocks admission,retains already offered stores and public outputs,and
clears object rows after the owned primitive and replies drain. Primary memory
faults precede cancellation. Collected success is immutable under later reset.
Successful raw capture then applies the original provoking-vertex flat values
for downstream clipping/binning; raster discard suppresses that output while
retaining feedback writes/counts.

[TransformFeedbackGeometryStage](../src/main/scala/opengpu/graphics/TransformFeedbackGeometryStage.scala)
composes the actual shared-CU vertex shader,raw primitive assembly and export
engine. It snapshots draw scope,serializes object commands with complete draws,
retains fault/cancel/source ownership and returns captured counts alongside
source completion. Vertex memory keeps IDs0..3; feedback uses ID4 in an
eight-ID namespace. The enclosing translator must pin the draw root and apply
allocation permissions/cache policy. Per-primitive recorded events provide a
primitive-query count hook.

The test plan covers full64-component raw capture,all four separate bindings,
preflight bounds and4GiB overflow,guard bytes,pause/resume,root/cookie
isolation,eight-row capacity,explicit eviction/restore,held offers/replies,
reset/fault precedence and healthy reuse. Both the default flat assembly and
the explicit pre-flat profile run the existing seven-topology/restart oracle.
The historical [874-input component qualification](performance/mobile-transform-feedback-2026-10-21.json)
passes23 distinct checks:seven export controls,12 exact-input default/raw
assembly checks,and four actual shared-CU local/spill cases. Two full-capacity
export/geometry emissions pass. Fourteen real-GPU draws capture96 primitives,
including indexed instances,restarts,pause/resume,raster discard,program
faults and two held-export-store reset cuts with healthy reuse. Exact cached
GPU readback follows coherent uniform and destination changes. The local
path uses no global shader staging. Private export ownership survives source
payload release on cancellation; held stores/root and final payload drain
are checked directly.

Primitive preflight now snapshots object metadata and registers limits,
bounds and selector groups. Commands snapshot and normalize begin state,
register validation,then commit table/reply. An admission-reset regression
first reproduced cookie0 instead of37; the fix binds identity before reset
can bypass later validation. Both packet and private-command cancellation
retain the accepted identity and root.

Standalone synthesis uses1000ps TC/SLVT,25percent I/O,standard ABC and no
retiming. The retained initial screen misses core by325.052ps (I/O+460.446ps)
with zero skipped instances; registered primitive validation improves it to
core-183.976ps (I/O+447.714ps),also with zero skipped instances. Registered command admission removes the second critical path. The third
screen passes core+259.866ps,I/O+464.703ps with zero skipped instances,
161654 mapped cells and library area20947.815000. Metadata is flip-flop
storage with zero SRAM macros. This is the default export component only;
shared-CU geometry and native host timing are not qualified by it. The902-input
result below qualifies the earlier lease/publication design; the904-input
scoped-abort result qualifies current production. Older screens remain
historical evidence. Primitive-query hardware and other H5/H6 gates stay open.


## Native command and checkpoint transport

Opt-in `NativeTypedGraphicsConfig.transformFeedback` and
`NativeBinnedGraphicsConfig.transformFeedback` expose opcode12. The default
profile keeps legacy geometry and memory interfaces. Typed and binned hosts
capture immediately after raw assembly, before flat shading, clipping and
bin fanout. Binned image engines replay already captured geometry.

The generated [C ABI](../driver/opengpu_transform_feedback_abi.h) and
[machine-readable layout](abi/native-transform-feedback-v1.json) define GTF1:
128 little-endian words,512 bytes,64-byte alignment and a33-bit exclusive
allocation limit. The record contains action,opaque context,cookie,program,
primitive kind,layout,bindings,selectors,cursors,64-bit counts,fault state and
full VM root. Input words42/43 must be cleared before every command; reserved
words must be zero. Public actions0..5 are begin,end,pause,resume,restore and
evict. Scoped invalidation6 is private recovery,not a public action.

For opcode12, `SOURCE` is the record VA, `DESTINATION` and `PATTERN[0]` are
its exclusive limit, `BYTES=512`, `WIDTH` is opaque context, `HEIGHT` is cookie,
and `SOURCE_STRIDE` is the program witness. All flags must be zero. Malformed
envelopes become a zero limit and fail before memory. Commands wait for native
rendering and graphics dependencies,then fence following dispatch until their
owned result enters the unified completion queue.

For opcode7, `WIDTH` supplies the same opaque context, `HEIGHT` is the program
witness and `SOURCE_STRIDE` is the capture cookie. Flag4 requires an exact
running context/full-root/cookie/program/kind lease; missing state fails with5,
identity mismatch with2. These rejections do not poison another lease. With
flag4 clear,the context must be inactive or paused; a running object fails
closed with5. Flag3 discards raster output while retaining feedback stores and
acknowledged counts. The native job and each admitted draw snapshot this scope.
Program/allocation lease management remains the caller's responsibility.

Control/checkpoint memory and feedback data use separate translated clients.
Typed feedback data uses IDs40/41 and control42/43 after query38/39; existing
renderer IDs and physical-image classification stay unchanged. The binned
clients occupy analogous reserved IDs after existing native/data/PTW owners.
They share the qualified auxiliary Sv32 fabric,including captured-root pinning,
permission faults,held replies,automatic epoch invalidation and PTE cache
policy. No CPU cache-coherence assumption is added.

Checkpoint publication writes physical lines0,1,3,4,5,6,7,then2. Line2 contains
root,counters,operation status and flags. Public `record_complete` appears only
after its final acknowledgement. A failed unified completion invalidates the
record regardless of partially changed bytes. If a command mutated an object
and publication fails,only that object is invalidated before reporting the
memory fault; a rejected command's publication failure preserves the existing
object. Global reset drains owned offers/replies,then clears all objects.
One-cycle reset during a retained core offer stays latched through retirement.
After global reset,the caller must discard stale checkpoints and establish
fresh cookies before reuse.

Completion opcode bit3 remains at metadata bit16. Completion data and record
word43 use bits0=object resident,1=record complete,2=mutation applied; upper
bits and render-detail words are zero. GFC1 at0x260..0x27f is read-only and
reports eight objects,four bindings,64 interleaved/four separate components and
profile-specific raw source-word count. Features advertise scoped objects,
paused checkpoints,pre-flat capture,acknowledged counts,raster discard,scoped
publication recovery,commit-line-last ordering and required capture leases.

The [902-input native qualification](performance/mobile-transform-feedback-native-2026-10-21.json)
historically passes65 distinct checks across exact source scopes:13 control/export,15
transport/MMIO,17 legacy router/query,two enabled/disabled capabilities,16
assembly/actual-CU geometry and two native typed AXI cases. The typed cases
execute four frames across local/spill payloads,including two checkpoint-store
reset cuts,exact GPU raw captures and MRT/depth/resolve guard-byte oracles,
raster discard,query coexistence and held-completion root protection. Generated ABI comparison,C11/C++17 layout and three complete
feedback+query typed,binned/full-product emissions pass. Fresh default export
synthesis passes1000ps TC/SLVT,25percent I/O,standard ABC without retiming:
core+226.040ps,I/O+451.601ps,zero skipped instances,164002 mapped cells,
library area21091.471740 and zero SRAM macros. The default native control/parser screen also passes core+407.438ps,
I/O+235.022ps with zero skipped instances,30641 cells,library area3963.412620
and zero SRAM macros. Both screens are individual components; composed
control/export,geometry and whole-host timing remain separate gates. That checkpoint preceded binned completion and the scoped-abort correction;
these component/structural results do not close V07,Q02,H5 or H6.


The first native typed fixture paired a nonzero centroid mask with fragment
ABI1. Both draws were correctly rejected by existing descriptor validation.
The failure/source are retained; changing that fixture to centroid-capable
ABI3 passes both payload modes without any production or validator change.
The preserved902-input source proof records that one-line test-only delta.


## Scoped draw abort and global reset

The four healthy native typed/binned local/spill cases pass on the preserved
902-input source,eight frames with four checkpoint-store reset cuts. A new
904-input native fault matrix reproduces a separate bug in both payload modes:
a failed required-capture draw routed cancellation into the global feedback
reset and removed a healthy peer context's object. The peer draw returned5
instead of0. Source,failed local/spill cases and healthy controls are retained.

The export core now has separate global reset and scoped abort inputs. Native
geometry routes draw/payload cancellation to scoped abort; only the explicit
native controller reset clears all objects. Unfinished owned capture latches
cancellation without committing cursors/counts; primary memory faults retain
precedence. Wrong/missing capture leases cannot poison a peer. Default
standalone geometry keeps its original global cancel contract. Held stores,
root and public results remain owned through drain. The permanent
`EmitTransformFeedback control` entrypoint emits byte-identical RTL to the
qualified native control module.

The [current904-input qualification](performance/mobile-transform-feedback-scoped-abort-2026-10-21.json)
passes71 distinct checks across exact source scopes:25 fresh (nine export,six
native control,eight native typed/recovery/default geometry and two native
binned cases),plus46 unchanged transport/MMIO/router/capability/assembly
checks. Five current emissions pass:typed,binned,full-product,default export
and permanent native control.

Native runtime completes22 frames across local/spill payloads:16 healthy and
six expected failures,plus two reset-aborted capture draws. The eight healthy
typed/binned frames check raw capture before fanout,exact MRT/depth/resolve
guards,raster discard,query coexistence and four checkpoint-store reset cuts.
The14 fault-matrix frames check checkpoint/capture permissions,a capture bus
fault,a missing capture lease,and healthy peer recovery. Both accepted capture
store resets drain,clear stale objects and complete fresh-cookie reuse. Denied
stores never reach physical AXI; faulted partial stores stay within the planned
primitive. Counters/cursors do not advance on a failed primitive. Both payload
modes release all allocations and pins. Native frames capture20 complete
primitives; the four fresh standalone geometry cases execute14 draws,capture96
primitives and cover two additional held-store reset cuts with cached GPU
readback and changed coherent uniforms/destinations.

Current scoped-abort export synthesis passes core+252.981ps,I/O+461.151ps at
1000ps TC/SLVT,25percent I/O,standard ABC without retiming,zero skipped
instances,164503 cells,library area21108.107520 and zero SRAM macros. The
native control's byte-identical screen passes core+407.438ps,I/O+235.022ps,
30641 cells and area3963.412620,also with zero skipped instances and zero SRAM
macros. These are individual components; composed control/export,geometry
and whole-host timing/resource budgets remain open. Full16-lane/eight-warp
product emission is structural evidence,not full-capacity runtime. Primitive
query objects/native transport and the remaining H0-H8 hardware gates stay
open. No whole-GPU clock,frame-rate or GLES conformance claim is made.
