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
