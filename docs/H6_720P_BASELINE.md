# Completed frozen 720p baseline

All six original 1280×720 scenes completed on 2026-11-06. Independent checks
matched every stored color/depth/stencil value and allocation guard, including
the four-sample depth/stencil and resolved color planes for MSAA. Traffic
drained, and the assertion-enabled model retained 8,026 assertions.

These results use frozen RTL `c6667b46a9de5c0040122eb5a407932a4c4f79e4`
and primitive-clip-bound-v2 fixtures from
`eb093dd17fa2abfae70c10884478813e6f495e1c`. They establish a historical baseline;
later RTL optimizations have separate targeted evidence and are not included
in these frame counts. Whole-host physical timing and full H6–H8 acceptance
remain open.

| Scene | Service cycles | GPU cycles | Estimated ms at 1 GHz | Within 30 FPS cycle budget | Within 60 FPS cycle budget |
| --- | ---: | ---: | ---: | :---: | :---: |
| Desktop composition | 23,940,171 | 23,940,254 | 23.940 | Yes | No |
| Textured 3D | 10,688,599 | 10,688,682 | 10.689 | Yes | Yes |
| Opaque overdraw | 58,853,466 | 58,853,549 | 58.853 | No | No |
| Transparent layers | 58,168,263 | 58,168,346 | 58.168 | No | No |
| Small triangles | 30,605,575 | 30,605,658 | 30.606 | Yes | No |
| 4× MSAA | 45,366,053 | 45,366,136 | 45.366 | No | No |

The user-selected planning clock is 1,000 MHz. Estimated milliseconds are
`serviceCycles / 1,000,000`; the 30/60 FPS budgets are `1,000,000,000 / 30`
and `/ 60` service cycles. Three scenes meet the assumed 30 FPS cycle budget,
and one meets the assumed 60 FPS budget. This is not a qualified clock or
measured FPS result. Simulator wall time does not enter this calculation.

Execution used one fresh process per scene, at most two simultaneously, on
the previously qualified FlashSim model: `-O2` shards, `-O0` commit parts,
conservative alias handling and strict flex arrays. Fresh-process counters
must be compared with the same mode; the serial-suite small-triangles count
can differ by two cycles. Four-worker phase counters overlap and cannot be
added or translated directly into frame savings.

To reproduce a scene with the matching frozen binary and fixtures, use a new
output directory for every process:

```sh
"$H6_FROZEN_DRIVER" "$H6_FROZEN_FIXTURES" "$H6_SCENE_OUTPUT" \
  --scene opaque-overdraw
```

For the assembled six-scene output, the independent stored-image verifier is:

```sh
"$H6_JAVA" -Xmx6G -XX:ActiveProcessorCount=2 \
  -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=1 \
  -cp "$H6_FROZEN_ORACLE_CP" opengpu.system.VerifyCompiledNativeProduct \
  --product --output "$H6_ALL_SCENES_OUTPUT"
```

The binary, fixture and oracle variables must resolve to the pinned historical
inputs, rather than a current build. Raw manifests, complete outputs and
verification reports remain local under `.artifacts/` while needed; generated
data is not part of this document. The final two PNGs were extracted directly
from completed DUT memory after verification, without rerunning simulation or
rebuilding the model. No new 720p case or CI/nightly lane was added.
