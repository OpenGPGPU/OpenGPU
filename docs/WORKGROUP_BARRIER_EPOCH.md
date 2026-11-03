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

The Linux ordinary-compute validator admits the exact `0x0000000b` workgroup
barrier in branch-free shaders. It rejects conditional branches in any admitted
barrier program, as well as barrier use in graphics, texture and storage
profiles. This ensures the admitted program has one common barrier sequence;
it does not qualify general shader convergence or all memory domains.

A 16×16 one-CU Linux/ARTI smoke passed two active four-lane warps through two
barrier generations. Each warp writes a separate word, reads warp zero's word
after the first barrier, and writes its observed value; all four results are
checked. Reproduce with an isolated configured model:

```sh
python3 scripts/test_driver.py
bash scripts/test_scripts.sh
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=600 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh compute-barrier
```
