# Default native shader execution profile

The baseline uses four configured CUs, sixteen lanes and eight resident warps
per CU, default instruction/vector caches and L2, a 256-bit AXI bus and a
512-byte SoA stride. Both local and spilled payload cases render a full
128-thread fragment batch at 1x and 4x MSAA. Exact image bytes, sample values,
allocation guards, held-completion VM ownership and released payloads pass.
This workload reaches one active CU; it does not demonstrate four-CU scaling.

Two fixture corrections were required on the unchanged baseline hardware:

- `vsetvli x0,x9,e32,m1` derives VL from the launch capacity. The earlier
  `vsetivli` literal enabled only four lanes on the sixteen-lane model.
- Each frame allocates distinct vertex and fragment code addresses. External
  translated instruction-fetch assertions therefore remain meaningful after
  the first frame warms the instruction cache.

Four plain/structured-SIMT local/spill cases render eight frames with exact
image/sample, guard, payload-release and held-completion checks. Four CUs and
256-bit AXI are fixture selections; the host constructor retains its independent
defaults. See [validation](EXECUTION_VALIDATION.md). Whole-GPU clock, aggregate
four-CU rates, 720p workloads and H0–H8 completion remain open.
