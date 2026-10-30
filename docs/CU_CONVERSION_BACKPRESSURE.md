# Vector conversion backpressure timing screen

A two-entry non-flowing conversion result queue passed 15 focused conversion,
vector-backend and dependent-warp tests. In the complete storage-enabled CU
pre-route synthesis screen (1,000 ps period, 25% I/O delay, 344 characterized
SRAMs), its core slack was −413.848 ps and its I/O slack was −86.925 ps. The
preceding trap-retirement candidate measured −405.924 ps and −86.925 ps in the
same screen: this conversion queue worsened core slack by 7.924 ps. It is an
experimental candidate and is not promoted. This result does not establish
whole-GPU timing, frame rate, or H6 completion.

Focused functional tests can be reproduced with:

```sh
sbt 'testOnly opengpu.core.vector.VectorFcvtAluSpec' \
  'testOnly opengpu.core.backend.VectorBackendSpec -- -z vfcvt' \
  'testOnly opengpu.core.backend.VectorBackendSpec -- -z "two warps"'
```
