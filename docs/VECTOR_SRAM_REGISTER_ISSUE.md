# Synchronous vector register issue

The default 16-lane/eight-warp vector backend uses the same synchronous
request/read/issue protocol in behavioral and SRAM configurations. The pinned
ASAP7 1RW macros return data after a read clock. Capturing their outputs as
combinational data could issue stale operands; a separate write-slicing error
also repeated the low 64 bits into every vector chunk. The manager now retains
the read owner and response, and each macro receives its own packed-data slice.

An accepted manager request reaches issue after two further clocks when there
are no hazards or stalls. The complete issue stage takes five clocks after
input acceptance, including its skid, reservation queue and output register.
Same-warp writeback owns the 1RW port and postpones a new read; another warp's
write can overlap it. Buffered operands and their warp metadata remain stable
under backpressure. RAW/WAW reservations release on writeback or cancellation.
Reset flushes buffered work and the scoreboard without clearing SRAM contents.

The manager selects synchronous behavioral reads explicitly. Standalone
`VectorRegisterFile` reference users retain their default combinational
read/bypass API. Behavioral backend cycle measurements must include the SRAM
read stage; earlier measurements using the shorter path need revalidation.

Validation passes 24 cases across seven suites with no failed, skipped,
canceled, ignored or pending cases. Actual pinned SRAM models cover every lane
in the first/last warp, four vector operands and v0 predicates, queued responses,
write conflicts, output stalls, RAW/WAW release, cancellation and reset. The
complete physical backend configures VL, stores initialized values, commits an
in-place vector add through SRAM writeback, and stores the computed values.
Existing backend arithmetic, flags, conversions, dependent sums and multi-warp
ellipse checks also pass.

The complete default SRAM-backed register manager passes mapped synthesis at
1000 ps with 25% I/O delay: core slack **+359.903 ps**, I/O slack
**+180.529 ps**. This uses ASAP7 TC/SLVT and SRAM revision
`9f5af0939e8dd3cc1a9693a50b23441691dd7d25`, standard synthesis, no retiming or
false paths, and zero omitted operators. This component result does not qualify
whole-CU/host timing, final storage or area, or 720p/30 FPS. Register capacities
and macro replication are unchanged.

Reproduce from the repository root with the SRAM submodule initialized:

```sh
sbt -Dopengpu.simJobs=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.backend.register.VectorRegisterSramContractSpec opengpu.core.backend.VectorSramBackendSpec opengpu.core.backend.register.VectorRegisterFileSpec opengpu.core.backend.register.VectorRegisterManagerSpec opengpu.core.backend.scoreboard.VectorRegisterScoreboardSpec opengpu.core.backend.issue.VectorIssueStageSpec opengpu.core.backend.VectorBackendSpec' \
  'Test / runMain opengpu.core.backend.register.EmitVectorSramRtl .artifacts/vector-sram-manager-rtl'

# With ChipAgent installed beside this checkout:
../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/vector-sram-manager-rtl VectorRegisterManager \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/vector-sram-manager-synthesis
```

Raw logs, emitted RTL, manifests and qualification reports remain local under
`.artifacts/`.
