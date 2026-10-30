# Compute texture host commands

The optional GTM1 staging block publishes a compute texture root and its
context, pass and generation with each direct or indirect compute command.
GCR1 advertises the transport independently of graphics texture support.
Staging captures the publication when submitted; later register writes do not
modify queued commands. Protected VM ownership continues until the software
completion is popped, and reset clears the enable bit.

The driver helper checks the discovery flag and magic before using the block.
It encodes version 1 only and rejects unaligned or overflowing 128-byte roots
without changing the output. The caller must retain the root, referenced
records and data, and the VM mapping through completion pop.

Local validation covers malformed staging writes, disabled-register behavior,
two queued independent publications, held commands and protected VM writes.
A 256-bit AXI test executes direct and indirect work on two CUs, verifies each
texture's root and data reads, and retains ownership through software completion.
The host test uses a compact 16x16 graphics configuration; it does not render a
frame. The C tests cover discovery and root boundary checks.

```sh
sbt 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GpuCommandMmioComputeTextureSpec opengpu.graphics.GpuHostComputeTextureCommandSpec' \
  'runMain opengpu.elaboration.EmitComputeTextureCommandAbi --check' \
  'runMain opengpu.elaboration.EmitComputeResourceAbi --check'
python3 scripts/test_driver.py
```

Native kernel admission and complete API/compiler integration remain open.
Broader AXI reset/fault recovery, full mobile capacity, cross-domain coherence,
and physical qualification remain required; this does not close H7 or claim
720p performance.
