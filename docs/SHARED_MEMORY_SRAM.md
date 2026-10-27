# CU shared-memory SRAM

The physical-memory switch in `GpuCore` also selects characterized SRAM for
CU-local shared memory. Each byte bank uses the low byte of a 256×32 macro;
larger banks concatenate segments. Reads retain one-cycle latency and each bank
accepts one byte operation per clock. Independent byte writes require no extra
read/modify/write cycle.

| Default resource | Per CU | Four CUs |
| --- | ---: | ---: |
| Logical shared capacity | 16 KiB | 64 KiB |
| Independent byte banks | 16 | 64 |
| Characterized 256×32 macros | 64 | 256 |
| Physical SRAM capacity, including padding | 64 KiB | 256 KiB |

Vector spans check their lower bound and retain the carry in the exclusive end
address. Word AMOs retain that carry too. Below-allocation and wrapped accesses
fault before selecting a bank row; an allocation ending at the exclusive 4 GiB
RV32 boundary still permits its final aligned word.

Eighteen local checks pass. They cover every byte at bank depths 1/64/256/1024,
the full default allocation, cycle-by-cycle model agreement, randomized masks
and bank conflicts, all nine AMOs, held replies, reset retention and allocation
edges. Two real CU kernels execute sixteen lanes and eight warps with actual
scalar/vector/shared SRAM models, exact global output bytes and drained traffic.
Six integrated-core regressions preserve existing execution and fault recovery.
The two range-check failures were reproduced before their fix. GitHub also
executed 138 Scala tests with zero failures, cancellations, ignored or pending
cases on the implementation head.

The complete shared-memory component passes mapped synthesis and linked STA at
1,000 ps, TC/SLVT with 25% I/O budgets: core/I/O setup slack is
**+148.066/+483.627 ps**. All three functional definitions and 64 SRAMs are
included, with zero omitted or unresolved logic, standard ABC, preserved
hierarchy and no retiming, false paths or memory mocks. Mapping contains 42,317
standard cells plus the 64 SRAM macros. The retained unmodified component
mapped to 734,525 standard cells; its parent and byte-bank RTL are byte-identical
to the pre-change native profile. Cell counts do not weight macro area.

This qualifies the shared-memory implementation. Whole-host timing, total GPU
storage accounting, energy, routed timing and 720p frame-rate qualification
remain separate gates.

## Reproduce locally

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx3G -J-XX:+ExitOnOutOfMemoryError \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.SharedMemoryByteBankSpec opengpu.core.memory.BankedSharedMemorySpec opengpu.core.memory.BankedSharedMemorySramSpec opengpu.core.memory.BankedSharedMemoryBoundsSpec opengpu.core.GpuComputeUnitSharedSramSpec opengpu.core.GpuCoreSpec'

sbt -J-Xmx3G \
  'runMain opengpu.elaboration.EmitPpaRtl shared-memory-sram .artifacts/shared-memory-emitted'
python3 scripts/extract_emitted_rtl.py .artifacts/shared-memory-emitted \
  BankedSharedMemory .artifacts/shared-memory-rtl
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/shared-memory-rtl BankedSharedMemory --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy \
  --period-ps 1000 --io-delay 0.25 --timeout 604800 \
  --output .artifacts/shared-memory-synthesis
```

The measured synthesis/STA run used a 1 GiB container limit with no swap. Keep
raw logs, source manifests and generated reports local under `.artifacts/`.
