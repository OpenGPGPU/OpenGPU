# Barrier dispatch completion belongs to one workgroup

`SingleCuKernelController` retained its dispatch-complete flag until the entire
kernel completed. A later workgroup could therefore release its first barrier
before its remaining warps had launched. This mixed barrier generations and
allowed a reader to observe the preceding group's stored value.

The repair clears the flag when the first task of each workgroup enters context
initialization. It sets the flag only after that group's final warp launches.
Clearing precedes the first runnable warp; register-initialization backpressure
cannot expose the preceding group's state. Completion handling and all ports,
instruction encodings and ABI fields remain unchanged.

Both controller regressions pass with four lanes/two warps and sixteen lanes/
eight warps. They check stalled peer initialization, three groups at full
residency, exact release PC/mask/warp identity and held release/completion.
Actual CU kernels also pass at both widths, including default caches and
128-thread groups. A one-group control precedes three groups; two barriers
separate writer/reader work, delayed memory replies preserve exact group values,
and output guards remain intact. These tests do not establish a general memory
fence or cross-CU coherence contract.

The 23-check qualification includes controller, actual-CU and four-CU grid
compatibility tests. Complete default-width whole-grid and grid-slice controller
timing passes at 1000 ps, including I/O. See [validation](EXECUTION_VALIDATION.md).

The Linux ordinary, checked-storage and texture-enabled compute validators admit
the exact `0x0000000b` workgroup barrier when all reachable forward paths
reconverge at each barrier and no path has already terminated. They reject a
barrier within an unresolved branch or after an earlier path exits, as well as
barrier use in graphics profiles. This guarantees one common barrier sequence for admitted
programs; it does not qualify general shader convergence or all memory domains.

A 16×16 one-CU Linux/ARTI smoke passed two active four-lane warps through two
barrier generations. The two warps take different forward branch paths and
reconverge before each barrier. Each warp writes a separate word, reads warp
zero's word after the first barrier, and writes its observed value; all four
results are checked. Host tests also cover nested branches, skipped barriers
and exits between barriers. Reproduce with an isolated configured model:

```sh
python3 scripts/test_driver.py
bash scripts/test_scripts.sh
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=600 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh compute-barrier
```

The checked-storage profile also passed a local 16×16 one-CU Linux/ARTI test
with two warps. Each stores to a distinct storage word, takes a different
forward path, reconverges at a barrier, reads warp zero's word and publishes
the result before a second barrier. An initial job makes one warp's offset
out of bounds; the job fails, a rebind reports the completed error once, and
the writable two-warp exchange succeeds without resetting the device. This
does not establish general GLES, graphics, image or texture barrier semantics.

```sh
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=900 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh storage-barrier-fault
```

The combined texture/storage compute profile passed a local 16×16 one-CU
Linux/ARTI test with two warps and one active lane each. Both fetch the same
texel, synchronize, write distinct storage words, take different forward paths
and reconverge at another barrier, then read warp zero's storage word and
publish it before a final barrier. Exact texture-derived storage and kernarg
values pass. The texture and storage backings are disjoint; this is not an
aliasing, graphics, image, or general GLES memory-barrier qualification.

```sh
scripts/build_arti_compute_resources_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=900 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh resources-barrier
```
