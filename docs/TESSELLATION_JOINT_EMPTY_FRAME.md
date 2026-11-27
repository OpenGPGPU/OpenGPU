# Joint GNQ empty-frame qualification

`TessellationJointEmptyFrameSpec` exercises the actual isoline GNQ/GNF owner,
input memory fabric, four image banks, clear, backing stores and resolves. Its
GNQ7 geometry case uses the thin native-geometry parent instead of the GNQ4
parent. The only peer is physical memory. It supplies no shader result, patch
contents, frame completion or retirement completion.

The supported graphics bounds are 640×480 through `GraphicsTestResolution`;
`productQualification=1` is rejected. The runtime sequence is a healthy 4×4
frame, a faulted 4×4 frame and a healthy 16×16 frame without a reset between
them. Configuration retains 16 lanes × 8 warps, two shared
CU interfaces, all 16 attributes/bindings/varyings, four RGBA32F attachments,
D32F, four samples, four instantiated banks and the original 32 KiB native tile
storage with vendor SRAM models. Each 4×4 frame uses one bank. The final 16×16
frame retires one actual tile from each bank.

The independent byte oracle checks every image allocation, row padding,
resolve allocation and unused directory byte. Expected useful stored/resolved
bytes are 4,352/1,088 for 4×4 and 69,632/17,408 for 16×16. The zero-spill version-2
pool covers its complete original reservation. CPU checks reject the former
truncated initial interval, a nonzero spill count and nonzero unused spill
metadata. Unexpected actual completion is reported immediately with status,
fault stage/address and read-byte count.

## GNQ7 thin geometry parent result

The native-geometry isoline parent passed two consecutive 4x4 clear-only frames
without reset. Each frame used the real GNQ7/GNF readers, geometry image
composition, four banks and physical memory responder. It read exactly 2,560
metadata bytes and one 64-byte directory line, reported 4,352 stored and 1,088
resolved bytes, and retired the expected single tile. The independent oracle
matched all image allocations, row padding, resolve allocation, directory bytes
and guards; GNQ/GNF metadata stayed immutable. No shader/CU launch, range task or
retirement peer was supplied. The held completion remained stable under cancel,
and the same owner accepted the next frame only after physical drain.

This case uses behavioral SRAM (`useSramBlackBoxes=false`) and does not require
the vendor macro models. Triangle/quad clear-only parent execution and actual
original VS/TCS/TES/GS CU/frame qualification remain pending.

Reproduce with JDK 21 and the repository JVM options:

```sh
sbt -batch 'testOnly opengpu.system.TessellationJointEmptyFrameSpec -- -z "GNQ7"'
```

## FlashSim result

The assertion-enabled isoline parent passed all three frames with FlashSim
`e8039c6`. The hardware read exactly 2,560 GNQ/GNF bytes for each frame. The
faulted frame held the response to an accepted backing-store write for 12
cycles during cancellation. After physical drain, its completion reported
status 3, stage 6 and the exact write address. The held completion stayed
stable and the same owner completed the 16×16 frame without reset. The healthy
frames matched the independent image, resolve and directory byte oracle,
including pitch padding and allocation guards. GNQ and GNF metadata remained
immutable in all three frames.

`TessellationJointEmptyFrameRecipeCheck` passed the two runtime recipes and
the invalid pool controls. The two fabric cases each retired 40 metadata
reads on physical tag 260 and one directory write on tag 16 with a held
response and exactly one acknowledgement, including the owned-source wrapper.

### Reproduction

Initialize `depends/asap7_sram_0p0` at the repository's pinned revision
`9f5af0939`. Point `FLASHSIM_DIR` to FlashSim `e8039c6` and `CIRCT_VERILOG` to
the CIRCT installation. Use Python 3.13 and compile the current Scala sources.

```sh
export FLASHSIM_CIRCT_THREADS=4 FLASHSIM_MODULE_JOBS=2
export FLASHSIM_CIRCT_LARGEST_FIRST=1 FLASHSIM_DATA_REGION_FIELDS=0
export FLASHSIM_PREFLATTEN_WIRES=1 FLASHSIM_COMMIT_PARTS=16
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython=python3.13 \
  -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  -Dopengpu.flashsimShards=64 \
  'Test/runMain opengpu.system.TessellationJointEmptyFrameRecipeCheck' \
  'testOnly opengpu.system.TessellationJointEmptyFrameSpec'
```

The staged cold build ran CIRCT import in 33 seconds at 2.70 GiB peak and
file-backed C++ generation in 2,128 seconds at 7.90 GiB peak. The verified
`flashsim.reshard` step produced 157 units with a 32 MiB method shard target
and a 24 MiB invalidation-table limit. Two Clang jobs with precompiled headers
built and linked the model in 1,313 seconds under an 11 GiB process-group guard.

Replay first matched the freshly elaborated RTL and bridge sources, ordered
inputs, assertion defines and svsim ports to the built model. Fresh elaboration
and replay completed in 114 seconds at a sampled 4.72 GiB peak. Triangle/quad
clear-only parent execution, complete three-domain original shader execution,
protected submission and the VGA60 product run remain separate qualification
gates.
