# Fragment staging clear ranges

`FragmentClearRangeRegister` captures the first/last clear words and byte masks
during the existing descriptor-validation clock. Operational descriptors require
a 64-byte aligned private base. Word arithmetic uses 26-bit prefix adders and
folds the final aligned-boundary subtraction into carry-save operands. The
original descriptor rejection and fault-address contracts remain unchanged.
No extra collection or per-quad clocks are added.

Two executed component tests cover 6,385 intervals: every line remainder,
32-bit address boundaries, exact end at 4 GiB, independent byte masks, and held
outputs under changing descriptor pins. Sixteen current-source native fragment
checks also pass, including local/global staging, malformed descriptors/EOF,
held requests and cancellation, late faults, 12-lane batches, captured texture
policies and protected per-sample depths. Nominal two-CU local/global fixtures
retain 5,917/7,563 clocks. No tests are skipped or pending.

The complete range component passes synthesis at 1,000 ps and 25% I/O delay:
+274.083 ps core/+717.233 ps I/O setup slack, zero skipped or unresolved mappings.
Full fragment-stage timing is a separate gate.

```sh
sbt 'testOnly opengpu.graphics.FragmentClearRangeRegisterSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-fragment-clear-range .artifacts/fragment-clear-range-rtl'
python scripts/qualify_ppa.py .artifacts/fragment-clear-range-rtl FragmentClearRangeRegister \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/fragment-clear-range-synthesis
```
