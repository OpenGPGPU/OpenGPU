# Paged tile bin directory validation

`TileBinDirectory` captures descriptor legality on the accepted `begin`.
Version, continuation ownership, nonzero tile count, capacity, metadata
alignment, the exclusive 4 GiB limit, complete metadata extent and separation
from the writable bin arena are checked together. The page pool receives the
same result when admitting its binding. Invalid descriptors retain the same
fault/drain behavior.

Descriptor fields remain immutable until final completion. Reusing the
captured result keeps wide bounds arithmetic off cancellation, allocation,
restart and admission paths without adding a validation cycle or changing
resource capacities. An assertion compares it with the original descriptor
checks throughout every active binding.

All 30 existing unpaged/paged directory cases pass, including real SRAM,
eviction, corrupt metadata, invalid bounds, exact exhausted-prefix restart,
continuation history, cancellation, late faults and healthy reuse. Reproduce:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.TileBinDirectorySpec opengpu.graphics.PagedTileBinDirectorySpec'
```

Complete 10-module standard-ABC synthesis passes 1,000 ps TC/SLVT with 25% I/O
delay: core +41.439 ps and I/O +59.144 ps. Both real 32-bit SRAM macros are
included, with zero skipped/unresolved instances and no retiming, mocks or
false paths. Reproduce with a sibling ChipAgent checkout:

```sh
sbt -J-Xmx2G -Dopengpu.productQualification=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-paged-bin-directory .artifacts/paged-bin/emitted'
python3 scripts/extract_emitted_rtl.py .artifacts/paged-bin/emitted TileBinDirectory .artifacts/paged-bin/complete
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/paged-bin/complete TileBinDirectory --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/paged-bin/qualification
```

The emission checks the product metadata address capacity; it does not render
a 720p frame.

These component checks alone do not close two-engine H2 acceptance or qualify
the whole-host clock or product frame rate.
