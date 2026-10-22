# Native control host

When `externalBinnedRender` is selected, the native renderer owns vertex, raster
and fragment execution outside `RenderHost`. The legacy renderer's launch was
already permanently disabled in this configuration. `RenderHost` now omits that
unused `RenderCore` instance at elaboration, using the shared `RenderCoreIO`
boundary to expose idle legacy ports. Legacy and persistent configurations
continue instantiating their original renderer. Native command/completion
routing, capability records, DMA, register shadows, scanout/vblank and VM/reset
ownership remain active. Unowned legacy replies are backpressured.

Tests retain held native submissions/completions, DMA exclusion during rendering,
VM ownership, fill/copy/strided transfers with delayed replies, invalid descriptor
rejection, exact data/guards, scanout/vblank and capability discovery. Matching
legacy configurations retain equivalent emitted RTL code.

Native typed/binned shader tests cover scoped programs, join/quota/reset
recovery, and default-width plain/structured programs with local/spilled payloads.
The pruned control component passes 1000 ps synthesis timing; whole-CU/host timing
and aggregate throughput remain open. See [validation](EXECUTION_VALIDATION.md).
