# ASAP7 PPA status

Current physical status of the checked-in RTL. Synthesis estimates,
register-only STA and post-route STA are separate claims: a completed tool
run is not timing closure.

## How to run

Emit RTL via `EmitPpaRtl`, then run `scripts/qualify_ppa.py` with ChipAgent
ASAP7 (ORFS) with a real `clock` port (1000 ps target, TC, SLVT), explicit
SRAM `.lib`/`.lef`, and a 25% boundary delay budget. Each run directory
keeps `manifest.json`, `result.json` and ORFS artifacts. Exit code 2 means
the flow finished but the verdict is FAIL.

```sh
sbt -batch 'runMain opengpu.elaboration.EmitPpaRtl gpu-system generated/qualification/rtl/gpu-system'
python3 scripts/qualify_ppa.py generated/qualification/rtl/gpu-system GpuSystem \
    --output generated/qualification/ppa/gpu-system --stage synthesis
python3 scripts/qualify_ppa.py generated/qualification/rtl/strided-copy StridedCopyEngine \
    --output generated/qualification/ppa/strided-copy --stage route
```

## Retained Yosys hierarchy

Use `--synthesis-engine yosys --yosys-preserve-hierarchy` for local synthesis
when flattening the complete design exceeds memory. This preserves RTL module
boundaries and disables SAT resource sharing. All modules still map to cells;
STA links the complete netlist and retains paths across module boundaries.
The option supports synthesis only. It keeps the declared clock and I/O budget,
mapping-failure checks, and setup checks for every reported path group.

The qualifier pins the configured ChipAgent Docker image, derives an environment
profile from `yosys-hierarchy.Dockerfile`, and verifies identical filesystem
layers. Its manifest records both image IDs, the recipe hash and settings.
The profile disables DUT blackboxing, memory mocks, gutting and retiming;
real SRAM library/LEF inputs remain required. No tool checkout is modified.

Reproduce the small two-module control locally with ChipAgent's Python:

```sh
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
    timing/tests HierarchyProbe --synthesis-engine yosys --yosys-preserve-hierarchy \
    --clock clock --period-ps 1000 --io-delay .25 --timeout 180 \
    --output .artifacts/hierarchy-probe-positive
```

Repeat with `--period-ps 100` and a fresh output directory for the negative
control. The measured 1000 ps control retains both `Leaf` instances and passes
core/I/O setup (+633.756/+716.920 ps). The 100 ps control maps successfully but
fails setup (-55.872 ps). A separate linked STA check reports a real `u0` to
`u1` register path. These controls validate the flow; whole-host timing, memory
capacity and workload FPS still need their own complete-design evidence.
Neither control qualifies hold, routing or power. Raw outputs stay local.

CPU configuration controls run in `bash scripts/test_scripts.sh`; Docker mapping
and full-host qualification remain local-only.

## Canonical checkpoint partitions

`scripts/split_yosys_rtlil.py` prepares a bounded module input from an existing
complete Yosys canonical checkpoint and its split RTL emission. It checks the
reachable definitions and direct instance counts against the emitted RTL,
rejects abstracted DUT modules and unknown non-library definitions, and retains
the checkpoint's library definitions and direct child implementations. It also
records the checkpoint, emission and partition hashes, plus the direct DUT
children that a later partition mapping step would temporarily blackbox.

For example, given the RTL and canonical checkpoint from the same frozen run:

```sh
python3 scripts/split_yosys_rtlil.py \
    .artifacts/product-host-rtl \
    .artifacts/product-host-synthesis/flow/orfs-work/results/base/1_1_yosys_canonicalize.rtlil \
    GpuHostSystemAxi .artifacts/product-host-root-partition
```

Pass `--module NAME` to prepare another reachable definition. Each output must
be new. The hierarchy comparison does **not** prove that a checkpoint was
generated from those RTL bytes: verify the original source manifest, canonical
run and checkpoint hash separately. A partition is not a mapped or qualified
design. Mapping must preserve its exact interfaces and later restore every DUT
implementation, check the complete linked netlist and real SRAM instances, and
run whole-design STA across module boundaries. The existing full-host gate is
unchanged; this preparation alone produces no timing or product claim.

Before using a saved checkpoint for further mapping, verify its recorded local
provenance against the clean source and ChipAgent checkouts used for the run:

```sh
../chipagent/.venv/bin/python scripts/verify_yosys_checkpoint.py \
    .artifacts/product-host-synthesis .artifacts/product-host-rtl \
    --source-worktree /path/to/frozen/source-checkout --chipagent-dir ../chipagent \
    --output .artifacts/product-host-checkpoint-provenance.json
```

This requires matching source and emitted-RTL hashes, the original combined
RTL and ChipAgent flow input hash, exact configuration and clock constraints,
the pinned image and environment, SRAM library/LEF bytes and canonicalization
log checksum. Unexpected HDL or library files and unsafe flow overrides fail the
check. The canonical log records the first 20 hexadecimal digits of SHA-1;
the verifier also records the complete checkpoint SHA-256. This is a check of
saved local evidence, not a signed attestation or a physical qualification.
Inputs must remain unchanged while subsequent partition mapping uses them. The
source checkout must remain clean; place outputs outside it or in an ignored
artifact directory.

On the frozen `7617dcf4` 720p host, the checkpoint inventory contains 724
reachable RTL definitions and 204 library definitions, including 874 32-bit
and 1,330 64-bit SRAM instances. Its 577 MiB checkpoint yields a 17 MiB root
slice. With the same pinned ASAP7 tool image, mapping just that root with its
seven direct DUT children temporarily abstracted completed in 22.67 seconds at
245 MiB peak under one CPU and a 4 GiB cap. All 152 root ports retain their
names, directions and widths. This is a root-component experiment; it does not
establish the memory cost or timing of the reassembled host.
The separate 130 MiB `StreamingTypedTileColorLanes` slice reached ABC extraction
but timed out after 600 seconds under one CPU and a 6 GiB no-swap cap. The
container was not OOM-killed and produced no completed mapped netlist. The
later bounded mapping and linked-component screen are recorded below; the
complete linked-host gate remains necessary.

Reproduce the bounded CPU controls and the separate local ASIC control with:

```sh
python3 scripts/test_extract_emitted_rtl.py
python3 scripts/test_split_yosys_rtlil.py
python3 scripts/test_verify_yosys_checkpoint.py
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python \
    scripts/test_split_yosys_integration.py --output .artifacts/split-yosys-control
../chipagent/.venv/bin/python scripts/test_verify_yosys_checkpoint_integration.py \
    --source-worktree /path/to/clean/source-checkout --chipagent-dir ../chipagent \
    --output .artifacts/checkpoint-provenance-control
```

The two-module ASIC control maps both definitions from a shared checkpoint,
restores them, and matches the complete-hierarchy setup results of +633.756 ps
core and +716.920 ps I/O. It also checks a +844.128 ps register path from `u0`
to `u1`, proves RTL/netlist equivalence, and rejects a missing module and a
changed arithmetic constant. The Docker control stays outside regular CI;
the small CPU controls run in the existing host-script job.
The separate provenance control regenerates a small saved flow and rejects
changes to its RTL, constraints, checkpoint, tool profile, input hash, and
unexpected HDL or library files.

After mapping a slice, check its original port names, order, directions, ranges
and signedness, as well as every direct child instance and named connection:

```sh
python3 scripts/check_yosys_partition.py \
    .artifacts/product-host-root-partition/partition.rtlil \
    .artifacts/product-host-root-partition/manifest.json \
    /path/to/mapped/1_2_yosys.v --output .artifacts/root-structure.json
python3 scripts/test_check_yosys_partition.py
```

The checker permits an omitted child output only when its original connection
was already empty. It rejects changed ports, missing instances or connected
ports, abstracted mapped modules and stale partition hashes. This checks
structure, not the equivalence of mapped logic or connection expressions;
the mapping log, full linked hierarchy and whole-host STA remain separate gates.

The frozen 720p `StreamingTypedTileColorLanes` slice completed mapping under
one CPU and a 6 GiB no-swap cap in 788 seconds, with 1,810 MiB peak memory.
All 250 module ports and 20 direct child instances passed this structural
check; the 72 omitted child connections were originally empty outputs. The
children are still separate implementations. Three representative host leaves
also mapped and passed the structural check, including an SRAM wrapper that
retains one real characterized macro. These are component results only.

All 40 definitions reachable from that color component have since been mapped
and reassembled from the same frozen host checkpoint. The source and linked
hierarchies agree on 653 expanded module instances. Streaming the 40 mapped
files through Yosys `hierarchy -check` and `check -assert` reported zero
problems; a deferred parse of the same netlist exceeded the 6 GiB cap. The
linked OpenROAD synthesis screen reports +18.946 ps core and +494.171 ps I/O
setup slack at 1,000 ps TC/SLVT with 25% I/O delays. `check_setup` reported no
missing constraints; a small control with output delays removed was detected.
The link removed 231,066 dead instances after restoring the child modules.

The component has no SRAM instances. Its link loaded the original host's SRAM
collateral and reported only unused-macro site/pin warnings; the ten standard
Liberty timing warnings match the small control. A real `CSA3to2` leaf also
passed 214 RTL/netlist equivalences and rejected changed logic. These checks do
not constitute a formal equivalence proof of the complete 40-module component,
whole-host timing, routed timing or product FPS. The full component run uses
local partition orchestration and saved artifacts; the commands above verify
individual slices rather than automatically reproducing the complete screen.

The following local-only control adds unused child outputs, requires dead
instances to be removed after linking, and checks linked timing and independent
RTL/netlist equivalence. It also rejects a missing child and changed logic:

```sh
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python \
    scripts/test_split_yosys_integration.py \
    --rtl timing/tests/partition_dead_output --expect-dead-logic \
    --output .artifacts/split-yosys-dead-output-control
```

## Pre-ABC checkpoint scripts

`scripts/prepare_yosys_abc_checkpoint.py` derives checkpoint and resume scripts
from the exact supported pinned OpenROAD `synth.tcl` (SHA-256
`4725557a1a74a1ea6495140db1142a5cfe84f4b46ba7b6cfb20835935e611319`).
It rejects a different script or ambiguous insertion point. The checkpoint
script saves RTLIL after `setundef -zero`, writes a SHA-256 completion sidecar,
and then executes the original ABC and mapped-design checks. The resume script
requires an intact checkpoint and complete sidecar before reading it and
executing the same original suffix. Both reject wrapped-operator flows.

The sidecar verifies checkpoint bytes only. Before a resume, independently
verify that the original image, flow, source and canonical checkpoint,
partition, configuration, environment, constraints and libraries are the same
as the run that created it. These scripts do not perform that binding or launch
and adopt jobs. Resume only after the original job has stopped, using a fresh
output directory containing the saved checkpoint and sidecar. A checkpoint or
component mapping is not a complete-host timing or product qualification. Keep
incomplete and active run data until its owner has finished.

To derive the scripts from the pinned image used by the local control:

```sh
docker run --rm --entrypoint cat \
    sha256:a61e1f55785dcd34306e833181fc3c9c1699eef30bfd518352dc5478a31ea1d2 \
    /OpenROAD-flow-scripts/flow/scripts/synth.tcl > .artifacts/pinned-synth.tcl
python3 scripts/prepare_yosys_abc_checkpoint.py \
    --synth-script .artifacts/pinned-synth.tcl --output .artifacts/abc-scripts
```

Mount the generated script into the same verified flow and select it with
`SYNTH_SCRIPT`; the fresh output directory receives `pre_abc.rtlil` and its
`.sha256` completion sidecar. The control below demonstrates both phases.

For local controls, with ChipAgent configured as above:

```sh
python3 scripts/test_prepare_yosys_abc_checkpoint.py
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python \
    scripts/test_yosys_abc_checkpoint_integration.py --output .artifacts/abc-checkpoint-control
```

The two-module control checks byte-identical original, checkpointed and resumed
mapped roots. Its reassembled design passes independent RTL equivalence and
retains the +633.756/+716.920 ps core/I/O and +844.128 ps cross-module setup
results above. Corrupt, incomplete and stale checkpoints, wrapped operators and
a changed arithmetic constant are rejected. The Docker control is local-only;
the CPU controls run in the existing host-script job.

## Current results

All runs: 1000 ps target, TC, SLVT, 25% boundary-delay budget. `synthesis`
is pre-layout STA on the synthesized netlist; `route` is post-route STA.
Rows marked _(dirty)_ were measured with uncommitted RTL; re-run from a
clean commit before treating them as the baseline.

| Run | Scope | Core Fmax | Worst setup | Note |
|---|---|---:|---:|---|
| `gpu-system-readuse` | synthesis | 904.83 MHz | -105.19 ps | resolve read data captured before accumulate; `resolveEngine.result` +703.76 ps, `fsqrtAlu.quotient` -22.84 ps; limiter `l2.request_address` → fill `bytesFilled`; area 168398 µm²; STA re-run by hand after the wrapper was interrupted |
| `gpu-system-4f7cf7d` | synthesis | 861.42 MHz | -160.88 ps | FVF operand read after pending-`flw` check; limiter `l2.request_address` → `resolveEngine.result` (-40.01 ps in `ec2887e`, RTL unchanged); FVF paths +331.53 ps; area 168085 µm² |
| `gpu-system-ec2887e` | synthesis | 905.83 MHz | -103.97 ps | clean baseline, reproduces `wgmul`; limiter `fsqrtAlu.quotient`; area 167786 µm² |
| `gpu-system-fsqrtpost` _(dirty, rejected)_ | synthesis | 873.75 MHz | -144.49 ps | Extra sqrt result stage regressed integrated timing; source differs from `wgmul` only in `Fp32SqrtLane` |
| `fma-lane-cspipe` _(dirty)_ | synthesis | **1193.57 MHz** | **+162.17 ps** | FMA CS partials registered before assemble; **PASS** |
| `gpu-system-wgmul` _(dirty)_ | synthesis | 905.83 MHz | -103.97 ps | + workgroup Z mul split; limiter `fsqrtAlu.quotient` → `result` |
| `gpu-system-writeuse` _(dirty)_ | synthesis | **912.80 MHz** | -95.53 ps | + resolve sWriteUse; limiter `taskLocalSize2` → `workgroups.state` |
| `gpu-system-copyack` _(dirty)_ | synthesis | 861.87 MHz | -160.27 ps | + CopyEngine byte-add queue; limiter still `l2.request_address` → `resolveEngine.y` |
| `gpu-system-resolveresp` _(dirty)_ | synthesis | 804.45 MHz | -243.09 ps | resolve resp stage regressed; limiter `l2.request_address` → copy `bytesCopied` |
| `gpu-system-copybytes` _(dirty)_ | synthesis | 894.12 MHz | -118.42 ps | + delayed strided byte add; limiter `l2.request_address` → `resolveEngine.y` |
| `gpu-system-resolveprep` _(dirty)_ | synthesis | 868.65 MHz | -151.22 ps | + resolve sPrep addr; limiter `l2.request_address` → strided `bytesCopied` |
| `gpu-system-stridesum` _(dirty)_ | synthesis | 846.21 MHz | -181.74 ps | + strided sum lo/hi; limiter `resolveEngine.mode` → pending Memory |
| `gpu-system-joblast` _(dirty)_ | synthesis | 898.44 MHz | -113.05 ps | + JobDispatcher atLast flags; limiter `holdDstAddr` → `sumDstLine` |
| `gpu-system-resolveaddr` _(dirty)_ | synthesis | 870.99 MHz | -148.13 ps | + resolve shift addrs; limiter `jobs.command_gridSize_1` → `groupId_2` |
| `gpu-system-dmabytes2` _(dirty)_ | synthesis | 850.35 MHz | -175.98 ps | + cascaded CSA DMA bytes; limiter `resolveEngine.samples` address mul |
| `gpu-system-redfold` _(dirty)_ | synthesis | 824.68 MHz | -212.59 ps | + integerAlu fold stage; limiter `fillBytesFired` → `dmaBytesDelta[63]` |
| `gpu-system-coalpipe` _(dirty)_ | synthesis | 875.27 MHz | -142.51 ps | + coalescer pending; limiter was attributed to `integerAlu.reductionPairs` |
| `gpu-system-smulcs` _(dirty)_ | synthesis | 813.51 MHz | -229.24 ps | + cmdproc localSize pipe + mul CS cuts; limiter `vectorMemoryRouter` `elementSize` |
| `gpu-system-cspipe` _(dirty)_ | synthesis | 800.83 MHz | -248.70 ps | FMA cut; limiter `hold0Cmd_launch_localSize` |
| `gpu-system-cmdmul` _(dirty)_ | synthesis | 754.81 MHz | -324.84 ps | interim; limiter scalar mul CPA |
| `gpu-system-rowbase` | synthesis | 781.65 MHz | -279.34 ps | divide finalize + resolve row bases; limiter FMA `csaSumReg` |
| `gpu-system-csaccum` | synthesis | 760.84 MHz | -314.33 ps | carry-save perf counters (`a882bdb`); limiter vector divide |
| `gpu-system-fma5` | synthesis | 755.96 MHz | -322.83 ps | five-stage FMA; limiter DMA byte counter |
| `gpu-system-20d0732` | synthesis | 701.98 MHz | -424.55 ps | prior baseline (four-stage FMA) |
| `gpu-system` | synthesis | 505.13 MHz | -979.70 ps | older committed baseline |
| `strided-copy` | post-route | 505.06 MHz | -979.97 ps | committed baseline |
| `strided-copy-pipe9` _(dirty)_ | post-route | 976.34 MHz | -24.24 ps | descriptor cone pipelined; `vclk` output -263.79 ps keeps verdict FAIL |
| `gpu-system-routerpipe` _(dirty)_ | synthesis | 622.85 MHz | -605.53 ps | descriptor + router dispatch pipelined |
| `fma-lane` _(dirty)_ | synthesis | 934.68 MHz | -69.89 ps | FMA lane, four stages, carry-select completion add |
| `fma-lane-stage5` | synthesis | 929.70 MHz | -75.62 ps | five stages: add cut from invert/LZD-mask/mask-valid |
| `gpu-system-fma` _(dirty)_ | synthesis | 634.23 MHz | -576.72 ps | limiter the fill-engine completion path |
| `gpu-system-dmabytes` _(dirty)_ | synthesis | 672.47 MHz | -487.05 ps | + registered DMA byte accumulator |
| `gpu-system-cmdpipe` _(dirty)_ | synthesis | 694.39 MHz | -440.11 ps | + pipelined command-processor dispatch |

`gpu-system` is the bounded integrated top (`GpuHostSystemAxi`).
`strided-copy` routes cleanly (DRC/antenna 0, hold clean); its failing path
is flop-to-flop descriptor address arithmetic, not routing.

Standalone FP32 FMA now **meets 1 GHz** at synthesis (`fma-lane-cspipe`:
1193.57 MHz, +162.17 ps) after registering carry-select completion-add
partials on `fire_reg0b` and mux-assembling on `fire_reg0c` (stops Yosys
from flattening the CS structure into one long CPA).

Integrated `GpuSystem` still fails. The retained `gpu-system-wgmul` RTL is
905.83 MHz (−103.97 ps); the best measured intermediate is `writeuse` at
**912.80 MHz**. Hierarchical STA on `wgmul` names `fsqrtAlu.quotient`
through rounding into `result`. A post-iteration register was tested in
`gpu-system-fsqrtpost`, but it reduced integrated Fmax to 873.75 MHz and was
reverted. The two run manifests differ only in `Fp32SqrtLane.scala`.
Hold paths still name `l2.slices_*.fillWriteData`
into the data SRAM write port. `GpuCommandProcessor.queued` reports queue
occupancy only (commands held in the dispatch pipeline are not counted).

The 25% IO budget is a placeholder until the enclosing SoC supplies real
parent-interface budgets. Until then, internal `core_clock` is the only
transferable claim — and the integrated top is not met at 1 GHz.
Carry-save observation CPA still burns `vclk` slack on perf ports.

## Next

1 GHz remains the objective for the integrated top. Inspect the sqrt
quotient-to-result cone before trying another cut, then re-measure the
integrated top. Derive real parent IO budgets in parallel. These dirty-run
synthesis estimates need a clean committed baseline and post-route
qualification before making a physical closure claim.
