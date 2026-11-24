# Joint GNQ empty-frame qualification

`TessellationJointEmptyFrameSpec` exercises the actual isoline GNQ/GNF owner,
input memory fabric, four image banks, clear, backing stores and resolves. Its
only peer is physical memory. It supplies no shader result, patch contents,
frame completion or retirement completion.

The supported graphics bounds are 640×480 through `GraphicsTestResolution`;
`productQualification=1` is rejected. Runtime frames are 4×4 then 16×16 in the
same model without reset. Configuration retains 16 lanes × 8 warps, two shared
CU interfaces, all 16 attributes/bindings/varyings, four RGBA32F attachments,
D32F, four samples, four instantiated banks and the original 32 KiB native tile
storage with vendor SRAM models. The 4×4 case uses one bank; the 16×16 case must
retire one actual tile from each bank. Neither case measures VGA throughput.

The independent byte oracle checks every image allocation, row padding,
resolve allocation and unused directory byte. Expected useful stored/resolved
bytes are 4,352/1,088 for 4×4 and 69,632/17,408 for 16×16. The zero-spill version-2
pool covers its complete original reservation. CPU checks reject the former
truncated initial interval, a nonzero spill count and nonzero unused spill
metadata. Unexpected actual completion is reported immediately with status,
fault stage/address and read-byte count.

## FlashSim result

The assertion-enabled isoline parent passed both frames in one reset session on
current RTL. The hardware read exactly 2,560 GNQ/GNF bytes for each frame,
reported success after the held physical acknowledgements drained, and
retired the expected tiles across the four banks. Stored and resolved bytes
matched the independent oracle together with directory entries, all pitch
padding, allocation guards and immutable metadata. The final report stayed
stable under cancellation while held. The next frame reused the same owner
without reset.

`TessellationJointEmptyFrameRecipeCheck` passed the two runtime recipes and
the invalid pool controls. The two fabric cases each retired 40 metadata
reads on physical tag 260 and one directory write on tag 16 with a held
response and exactly one acknowledgement, including the owned-source wrapper.

### Reproduction

Initialize `depends/asap7_sram_0p0` at the repository's pinned revision
`9f5af0939`. Point `FLASHSIM_DIR` and `CIRCT_VERILOG` to the FlashSim and
CIRCT installations. Use Python 3.13 and compile the current Scala sources.

```sh
export FLASHSIM_CIRCT_THREADS=4 FLASHSIM_MODULE_JOBS=2
export FLASHSIM_PREFLATTEN_WIRES=1 FLASHSIM_COMMIT_PARTS=16
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython=python3.13 \
  -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  -Dopengpu.flashsimShards=64 \
  'Test/runMain opengpu.system.TessellationJointEmptyFrameRecipeCheck' \
  'testOnly opengpu.system.TessellationJointEmptyFrameSpec'
```

The measured run used FlashSim `3230040` for C++ generation and `8632e96`
for file-backed splitting. It reused an assertion-bound CIRCT import and ran
generation and splitting in separate processes. Their sampled aggregate
peaks were 7.48 GiB and 2.94 GiB. The first two-job native build reached its
6.5 GiB guard during compilation. The verified `flashsim.reshard` step used
a 32 MiB method shard target and a 24 MiB invalidation-table limit, producing
159 units. Two Clang jobs with precompiled headers built and linked that
model in 1,412 seconds under an 11 GiB process-group guard.

Fresh elaboration matched 921 source and bridge files by digest, the 609
ordered inputs, the enabled assertion defines and all 2,459 svsim ports.
Fresh elaboration and replay then completed in 108 seconds with a sampled
4.75 GiB peak under a 6.5 GiB guard. The staged measurements cover the
isoline empty-frame transport. Triangle and quad complete parents, original
shader execution, protected submission and the VGA60 product run remain
separate qualification gates.
