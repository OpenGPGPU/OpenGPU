# CU-pair placement for shared texture clusters

`NativeRgba8TexturePool` places one shared 8 KiB texture cluster per adjacent
CU pair. The [844-input candidate](performance/mobile-native-rgba8-pool-2026-10-21.json)
passes eight fresh checks and emits complete 1/2/4-CU profiles. All 841 inputs
from the 73-check native-client qualification remain exact. This qualifies
the pool component; full renderer/fabric wiring and pool synthesis remain open.

A client supplies the one-hot CU ownership mask associated with its native
task. The pool captures the pair for a held offer and leases the accepted
placement through public response consumption. Later ownership changes cannot
move the pending request, response or selective cancellation. Original tags
remain local to each client and are restored by the cluster router. Each
client currently has one response lease.

At four CUs there are two independent caches and 16 KiB total cache data;
one or two CUs use one 8 KiB cache. Both clients in a pair can join one line
fill. Different pairs have separate data and PTW port/ID spaces: sixteen data
IDs and four PTW IDs per cluster. Enclosing memory allocation must retain that
port identity while mapping to its bounded global physical transaction space.

The pool pins a single VM/publication epoch across both pairs. A new root
cannot enter an idle pair while an old lease remains public on the other pair.
Cache invalidation waits for all old leases, then reaches every pair. Multiple
scoped TLB flushes coalesce into a full idle flush, preserving every requested
address/ASID while work drains. The native caller retains its existing VM and
descriptor-publication pinning through preparation and full task retirement.

Tests prove pair sharing and independent cache/transaction namespaces at all
three configurations, resident reuse, held-offer placement, cancellation after
ownership changes, cross-pair real Sv32 roots, late public retirement, changed
backing and multiple same-root scoped flushes. Each emitted profile has 84 RTL
files. Of the standalone cluster's 83 files, 82 match after removing comments
and empty lines. The cluster wrapper is specialized because the pool ties off
per-client invalidation and scoped-flush fields, handling maintenance globally.
The wrapper is not covered by the standalone RTL-equivalence claim; the eight
fresh pool checks exercise the composition. Standalone cluster synthesis is
tracked separately; the full pool has no timing or mapped area result yet.

The full renderer must still connect its stage clients and ownership masks,
attach both physical/PTW port sets, and complete visibility and fault drain.
Serial native lane/metadata issue and one lease/client also need further
pipeline/credit work before the sustained native rate can be established.
All remaining H0-H8 exit gates stay open. PPA remains synthesis only.
