# Staged typed image admission and transfer

`TypedTileImageTransfer` validates a captured descriptor before private or
physical memory traffic. Admission visits all six source and six resolve planes,
retaining format, sample, permission, allocation, extent and alias rules.
Immutable shared loads and unused backing remain legal.

One checked 33-bit shift/add accumulator computes both image ends and physical
tile origins. It retains the exclusive 4 GiB endpoint and rejects overflow.
Prepared origins, byte sizes, slot strides and active row widths stay with the
binding. Depth/stencil clear words use the existing exact preparation pipeline.
The transfer registers store addresses and prepares one physical base per
eight-byte refill word before selecting cached bytes.

All eight accounting fields retain their original 64-bit wrapping values and
handshake events. Registered byte carry predicates avoid long enabled carry
chains without adding an observation clock. The helper's predicates clear and
commit with their owning counter.

Two admission cases check 342 descriptors against the original rules and an
independent BigInt origin/end oracle. They cover all 36 color formats, three
sample modes, depth/stencil, aliases and exact 4 GiB ends. A twelve-plane numeric
case completes in 747 clocks without traversing a large image; the bounded
validator produces a result within 1024 clocks after acceptance. A long-product case passes cancellation
and reset at every one of its 86 offsets, including held results and reuse.
Seventeen transfer cases retain the original twelve behavior checks and add exact
lazy clears, binding lifetime, store preparation and grouped writes. Refill cancellation covers
seven arrival phases. A counter case checks 65 carry-boundary seeds through 512
clocks, increments from 0 to 127, consecutive commits, reset/clear and full wraparound.
The existing 32-bit arithmetic carry check also passes. The bounded two-CU
parent fixture checks real shared-CU drawing, cached resolve visibility, final-ack
holds, cancellation/fault drain and healthy reuse.

Complete ASAP7 TC/SLVT synthesis passes 1000 ps including I/O:

| Block | Setup slack | I/O slack |
|---|---:|---:|
| Staged admission | +188.271 ps | +481.405 ps |
| Complete image transfer with gathering | +34.459 ps | +441.687 ps |

Both use 25% I/O delay, no retiming or false paths, and map without omitted
operators or unresolved drivers. These results qualify these components.
Enclosing renderer/host timing, mapped host resources, complete product
workloads and frame rates remain separate gates. Other descriptor consumers
retain their own admission. See [line gathering](TYPED_IMAGE_LINE_GATHER.md)
for grouped-write behavior and enclosing-test commands.

```sh
sbt -J-Xmx4G -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.StagedTypedImageAdmissionSpec opengpu.graphics.TypedTileImageTransferSpec opengpu.util.PreparedSmallIncrement64Spec'
sbt -J-Xmx4G \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-admission .artifacts/image-admission-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-transfer .artifacts/image-transfer-rtl'
CHIPAGENT_DIR="$PWD/../chipagent" ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/image-admission-rtl StagedTypedImageAdmission --clock clock \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/image-admission-synthesis
CHIPAGENT_DIR="$PWD/../chipagent" ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/image-transfer-rtl TypedTileImageTransfer --clock clock \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/image-transfer-synthesis
```
