# Bounded compute word atomics

Compute submissions can use relaxed 32-bit add, swap, xor, or, and, signed and
unsigned min/max, and compare/exchange when
`OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS` is returned by
`OPENGPU_PARAM_DRIVER_FEATURES`. Older drivers may reject that query; a missing
feature bit retains the existing instruction restrictions.
The `compute_offload` example queries this feature before validating its device
image and passes the same hardware availability to its local admission check.

The complete host defaults to device ABI version 2. This version preserves the
existing registers and guarantees the sixteen-word GCR1 discovery record.
GCR1 flag bit 3, `WORD_ATOMICS`, reports the implemented word operations,
including CAS. The driver validates the record before enabling admission.
ABI-v1 devices retain zero atomic features without any GCR1 reads; probing an
optional, unmapped register is not required. Standalone hosts retain version 1
unless their caller explicitly supplies version 2 and a compute resource record.

The validator proves that the entire aligned word lies inside the submitting
context's writable kernarg binding. Constant, unknown and warp-relative pointers
do not authorize access. Replacement registers must be defined on every incoming
control-flow path. CAS also reads the original destination register; its
comparison value must be defined, including when operands alias. The returned
old word loses any pointer provenance previously attached to its destination.
The immutable argument root cannot be overwritten.

Compute storage jobs also admit these relaxed operations on the writable
kernarg after proving every storage backing disjoint. This does not establish
ordering between storage and kernarg accesses. A small translated ARTI Linux
storage ioctl job executes one storage store/load and one relaxed `amoadd.w`,
checking the backing, loaded word and atomic old/new values after completion.

Graphics profiles, LR/SC, wider atomics and nonzero acquire/release bits remain
rejected. This does not expose arbitrary storage/image pointers, shared-memory
addresses, general shader loops, cross-domain barriers or API conformance.
The existing [word CAS hardware contract](WORD_COMPARE_EXCHANGE.md) and its
execution/fault evidence remain unchanged; these admission tests do not rerun
the earlier 80 CU kernels. One small translated ARTI Linux job executed a
relaxed `amoadd.w` alongside a disjoint texture size/fetch and checked both the
old and new word values; it does not qualify broader atomic or coherence behavior.

Local validation covers all 1,024 operation/width/ordering encodings, feature
fallback, word bounds, undefined operands, aliases, conditional comparison
definitions and result-pointer rejection. Discovery checks prove that legacy
hardware receives zero optional reads and malformed version-2 records fail.
Four hardware cases pass: mobile/split/absent AXI discovery and an actual complete
host with different compute and graphics configurations. A separate elaboration
case rejects three invalid version/discovery combinations. The generated ABI check
and existing kernel-free driver regressions also pass. The AArch64 kernel
module builds successfully against the configured Linux 7.2.0 tree.

Reproduce locally:

```sh
python3 scripts/test_driver.py
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh texture
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'runMain opengpu.elaboration.EmitComputeResourceAbi --check' \
  'testOnly opengpu.system.ComputeResourceCapabilitiesAxiSpec opengpu.system.ComputeResourceOwnerHostSpec'
```
