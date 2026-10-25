# Staged typed image admission

`TypedTileImageTransfer` validates a captured descriptor before issuing private
or physical memory requests. The validator visits all six source and six resolve
planes, preserving the original format, sample, permission, allocation, extent
and alias rules. Shared immutable loads and unused backing remain legal.

Row bounds retain the exclusive 4 GiB endpoint. A bounded shift/add accumulator
checks each positive term against the admitted 33-bit limit. Overflow rejects
the descriptor; it never becomes an accepted wrapped address. No image capacity,
format or address limit is reduced. Cancellation retires admission ownership
before transfer completion; a held validation result remains immutable.

Two focused cases check 277 descriptors against the complete original rules,
with an independent BigInt oracle for wide row products. They include all 36
color formats, three sample modes, depth/stencil, all alias classes and exact
4 GiB ends. A long valid product completes in 51 clocks; all 52 cancellation
offsets, selected reset stages and healthy reuse pass. The original twelve
transfer cases also pass without changing their fixtures or assertions.

Standalone ASAP7 TC/SLVT synthesis passes 1000 ps with +145.963 ps core and
+480.661 ps I/O setup slack, with no omitted operators or unresolved drivers.
It uses 25% I/O delay, no retiming and no false paths. This component result
does not qualify enclosing transfer/renderer/host timing, mapped host resources
or product frame rates. Other descriptor consumers retain their own admission.

```sh
sbt -J-Xmx4G -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.StagedTypedImageAdmissionSpec opengpu.graphics.TypedTileImageTransferSpec'
sbt -J-Xmx4G \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-admission .artifacts/image-admission-rtl'
CHIPAGENT_DIR="$PWD/../chipagent" ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/image-admission-rtl StagedTypedImageAdmission --clock clock \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/image-admission-synthesis
```
