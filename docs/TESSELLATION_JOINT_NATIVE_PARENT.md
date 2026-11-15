# Joint tessellation native owner

`TessellationJointNativeController` owns the real GNQ4/5/6 → GNF → GVM2 →
GNT7/8/9 → GVS1 → GND1 capture sequence. It is disabled by default. The three
`TessellationSystem*JointVertexImage` modules connect those captures to the
actual typed range, original vertex source, owned tessellation/image parent
and existing input memory fabric. No public getter, factory, ioctl or hardware
capability is enabled by this change.

The caller must retain the immutable BUILD graph and its actual authorized MMU
mappings until both the report and complete physical retirement. An owner
address envelope does not authorize holes inside it. Metadata uses the existing
single tag-zero credit; the real vertex source uses the existing source adapter.
No memory IDs or hardware capacities are added.

## Ownership and progress

The directory entry remains held through actual range execution, report
transfer and physical drain. Its held lease is not treated as outstanding
memory traffic. Reader ownership changes only after the preceding report has
transferred, its child has stopped and its accepted memory credit has drained.
Cancellation preserves an already offered child request until acceptance, then
cancels that child. Actual late faults take precedence over cancellation.

Original invocation IDs, draw ordinals, instance/patch cursors and generated
sequence/primitive cursors remain independent. A FIRST segment initializes the
publication cursor from the captured GNT; CONT uses the previous successful,
physically retired range's actual cursor. Neither capture completion nor a
predicted TES output count advances it. A frame fault arriving on the same edge
as range drain suppresses cursor and counter commit. A held final report is
immutable, while admission still waits for live transport drain.

No invocation means a captured clear-only frame with an exact absent state
table. An actual invocation with zero work still reads GNT/GVS/GND and obtains
its actual zero-task range retirement. Both paths require the real frame end
barrier, frame completion and complete memory drain.

## Empty state tables

`allowEmptyStates=false` preserves the existing replay and image contracts.
The explicit joint path permits only a state view with both base and limit
zero, retaining frame identity, version, scratch, image, protected-span and
alias validation. An unexpected primitive is rejected before a missing state
or shader can be fetched. The concrete parents also propagate the existing
independent primitive-ID input option; they do not infer language IDs from
generated primitives or task IDs.

## Validation and remaining gates

All 37 source files in the original focused provider/parent/test closure passed
fresh plugin compilation with Scala 2.13.18 and Chisel 7.15.0. Borrowed classes
were accepted only after package/SourceFile, their own producer's source hash
and class hash matched; changed providers were compiled from current source.
This is type qualification, not execution of the complete composed parent.
The subsequent fault fix refreshed transfer, image, replay and fixture sources
(11.47 seconds, 334128 KiB), extending the current source closure to 39 files.

The three domain master tests passed actual RTL serially (53.31, 52.98 and
52.99 seconds; sampled group peaks 486656, 477456 and 491328 KiB). Each uses
real record readers and independently constructed little-endian memory
packets, with explicit execution-transport peers. It covers partial-row CONT,
held offers and cancellation, actual report/drain boundaries, same-edge frame
fault, mismatched source roots, zero-work invocation, no-invocation clear
protocol, late memory fault and reset-free reuse. Restoring only the faulty
same-edge commit condition in a private production-source copy failed at the
specific cursor/statistics assertion (55.58 seconds, 476528 KiB). This mutant
preserves the new interface; it is not a claim that an older whole module ran
unchanged.

These master tests do not execute a shader or clear/store/resolve. The actual
full-capacity replay clear test passed in 904.25 seconds with a sampled peak of
4564096 KiB. It retains 16 lanes × 8 warps, two CU interfaces,
16 varyings, four MRTs, four samples, D32F, the complete native tile store and
advanced blend paths. Its independent oracle checks all declared clear/store/
resolve bytes and allocation/pitch guards, with held physical write replies,
no shader launch and no state fetch. Both healthy clears, rejected unexpected
primitive and reset-free reuse passed. The composed GNQ-to-image execution and
old-default negative case remain separate gates. Earlier heap/metaspace/aggregate limits and a 600-second native
build limit were insufficient; those bounded failures are not RTL failures or
passing results. The successful run used the original vendor SRAM models and
a fresh complete native build.

An accepted physical write whose acknowledgement was held for twelve cycles
through cancellation exposed a real propagation defect: the original source
reported cancellation 4 instead of fault 3, failing the specific status assertion
(769.30 seconds, 4803008 KiB). Transfer, image and replay now allow a real fault
to replace cancellation, preserve an already recorded real fault, and prevent
same-edge cancellation from overwriting a newly raised fault. Ordinary
cancellation metadata and all interfaces remain unchanged. Resolver failures
use the same transfer fault path.

The unchanged failing fixture passed against the fixed source (797.27 seconds,
4902208 KiB): status 3, stage/address, actual response-credit drain and held
report stability all passed, followed by healthy clear/store/resolve with a full
byte/guard oracle on the same owner without reset. This tests a delayed fault
after cancellation; it is not a claim of exhaustive fault-injection coverage.
All reported actual groups passed source, class, tool and HEAD guards. Ended
models and raw logs were removed after their consumers finished.

No full-CU qualification, native admission, six VGA scenes, physical result or
640×480/60 FPS acceptance follows from this component evidence. The Scala
regression workflow remains disabled and was not invoked.

## Reproduction

Run only the named suites through the existing component simulator, without
SBT or workflow dispatch:

```text
org.scalatest.tools.Runner -oD -s opengpu.system.TessellationJointNativeControllerSpec
org.scalatest.tools.Runner -oD -s opengpu.graphics.TypedReplayEmptyStateSpec
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_changed_test_suites.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_test_selection.py
bash -n scripts/ci_shard_tests.sh
```

Compile the current source closure with the real Chisel plugin before running
it; do not substitute an old compiled parent or changed provider. Master groups
used 128 MiB heap/metaspace, 32 MiB code cache, SerialGC, ActiveProcessorCount=1,
180 seconds and a sampled 512 MiB aggregate limit. Replay's current budget is
1024 MiB heap, 256 MiB metaspace, 32 MiB code cache, 1800 seconds and a sampled
6 GiB aggregate limit. Supply the pinned original SRAM files through
`-Dopengpu.testSramRoot=.../depends/asap7_sram_0p0/generated/verilog`, or the
default in-repository submodule path. Bind tools, classes, sources and those
files before and after each group. Reproduce the reported budget; any later
different-budget run must report its actual limits and preserve the prior
bounded result.

`simJobs=1`, low-memory/O0, separate native build and heap release were enabled.
The observed Verilator generator used `-j0`; subsequent build jobs/make were
one. This does not establish strict single-CPU execution for the whole tool
chain. Ended models, logs and private source mutants are removed after their
consumers finish.
