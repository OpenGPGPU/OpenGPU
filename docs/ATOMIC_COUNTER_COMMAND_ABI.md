# Atomic counter command encoding

`opengpu_read_atomic_counter_command_support` first checks the validated compute-resource capability flag. An unadvertised interface is not probed; an advertised interface with a truncated MMIO range or incorrect magic is rejected.

`opengpu_encode_atomic_counter_command_context` produces the seven staging words for a version-1 descriptor table. The base and exclusive limit must be 64-byte aligned, the range must be nonempty, its length must contain whole descriptors, and the limit must not exceed 2^32. Rejection leaves the output unchanged. The caller retains descriptor memory, VM and coherence ownership until the software-visible completion is popped.

Validate the generated constants and C boundary cases with:

```sh
sbt -J-Xmx4G 'runMain opengpu.elaboration.EmitAtomicCounterCommandAbi --check' \
  'runMain opengpu.elaboration.EmitComputeResourceAbi --check'
python3 scripts/test_driver.py
```

This helper encodes command state; host submission, fault recovery and API conformance require their own integration and qualification.
