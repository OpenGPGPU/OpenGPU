# Compute texture host transport

When enabled with protected VM state and GCR1 discovery, the host AXI command
path accepts the compute texture publication staged by the command MMIO block.
Direct and indirect compute commands retain their own resource root and task
identity. GCR1 advertises the internal texture-command transport bit; it does
not by itself establish a complete compute API or native driver capability.

The bounded CI checks cover host AXI discovery and command snapshotting, MMIO
validation and existing host regressions. The two-CU direct/indirect texture
execution qualifier runs locally rather than in PR or nightly CI:

```sh
sbt -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=2 \
  'testOnly opengpu.system.GpuHostAxiSpec -- -z "publish compute texture"' \
  'testOnly opengpu.graphics.GpuHostComputeTextureCommandSpec'
python3 scripts/test_changed_test_suites.py
```

Public command encoding, native driver admission, broader operation and format
coverage, coherence and full H7 qualification remain open.
