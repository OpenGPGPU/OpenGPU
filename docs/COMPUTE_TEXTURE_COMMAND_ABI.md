# Compute texture command encoding

The internal GTM1 command transport exposes five staged words for the resource
root and task identity. Software checks the GCR1 transport flag, MMIO range and
GTM1 magic before use. The checked encoder accepts version 1 and a 64-byte
aligned root whose 128-byte record fits in the 32-bit address space. On error
it leaves the output unchanged. The caller retains the records, data and VM
until the completion is popped.

```sh
python3 scripts/test_driver.py
sbt 'runMain opengpu.elaboration.EmitComputeTextureCommandAbi --check'
```

This checked command encoding does not supply a complete compute API or native
driver admission. Wider texture operation/format coverage, coherence and full
H7 qualification remain open.
