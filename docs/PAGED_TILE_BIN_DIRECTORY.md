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

These component checks alone do not close two-engine H2 acceptance or qualify
the whole-host clock or product frame rate.
