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

## FlashSim validation

Fresh Scala 2.13.18 compilation with the Chisel 7.15.0 plugin covered 793
production sources and 1,144 test sources. All 16 component RTL cases
passed in FlashSim. The three domain controllers exercised real record
readers and explicit execution peers, covering partial CONT cursors,
held offers, physical drain, same-edge faults, cancellation, zero work
and reset-free reuse. The typed ranges and publication endpoints covered
actual result accounting and overflow attribution. The two input fabric
cases checked held directory acknowledgement and exact tag retirement.

The three domain controllers retained the image fault address from the
matching frame through completion and physical drain. The actual four-bank
isoline owner returned the exact address of a delayed backing-store write
fault during cancellation. Its next frame completed without reset and matched
the byte oracle across all four banks.
[Scope and reproduction](TESSELLATION_JOINT_EMPTY_FRAME.md).

All three single-bank cases passed with FlashSim `e8039c6` in 1,874 seconds
with a 5.81 GiB sampled aggregate peak under an 8.5 GiB guard. The allowed
mode checked 4,352 stored and 1,088 resolved bytes, guards, held write
acknowledgements, unexpected primitive rejection and healthy reuse. The
default mode rejected the same absent-state descriptor before any state
read or shader launch. A delayed physical fault from an accepted write
kept its status, stage and address through cancellation and response drain.
The same owner then completed a healthy frame without reset. The fault
case matched all 628 freshly emitted files, their input order and the
compiler configuration. It reused the allowed model and finished in
24.7 seconds, including fresh elaboration and replay.

The component qualification uses two-CU interfaces. The original Mesa
VS and TCS/TES programs, two physical CUs, complete three-domain protected
submission and VGA60 product acceptance require their respective
end-to-end runs.

### Reproduction

Configure FlashSim and CIRCT as in the
[empty-frame recipe](TESSELLATION_JOINT_EMPTY_FRAME.md). Compile the current
Scala sources and initialize the pinned vendor SRAM submodule.

```sh
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython=python3.13 \
  -Dopengpu.simJobs=2 -Dopengpu.flashsimShards=12 \
  'testOnly opengpu.system.TessellationJointRangeCursorSpec opengpu.system.TileBinHostMemoryFabricJointClearSpec opengpu.system.TessellationJointNativeControllerSpec opengpu.graphics.TessellationVertexTypedRangeSpec opengpu.system.TessellationViewportPublicationEndpointSpec'
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython=python3.13 \
  -Dopengpu.simJobs=2 -Dopengpu.flashsimShards=32 \
  'testOnly opengpu.graphics.TypedReplayEmptyStateSpec'
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_changed_test_suites.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_test_selection.py
bash -n scripts/ci_shard_tests.sh
```

The source selectors passed 171 checks and both catalog selections passed.
