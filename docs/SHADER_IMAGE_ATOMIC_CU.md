# Opt-in CU image atomics

`GpuComputeUnit(imageAvailable = true, imageAtomicAvailable = true,
finishOnTrap = true)` routes the internal image atomic encoding through the
existing vector frontend and image tuple owner to descriptor admission and the
shared global atomic translator. Image task permission, stage, descriptor table
and VM root are captured at kernel acceptance. The image lease remains held
until the software-visible kernel completion is consumed.

The image service reserves external memory transaction ID 7 for one outstanding
atomic-descriptor or page-walk request, retaining the original internal ID until
its reply is accepted. Ordinary CU and image IDs 0–6 remain unchanged. The shared
atomic route arbitrates the image owner with scalar atomics and, when enabled,
the existing counter owner. Atomic failures report store fault class, retained
virtual address and page-fault state, identify the faulting lane, release the
reserved vector destination and suppress partial vector writeback. Earlier
acknowledged atomics are not rolled back. Helper lanes issue no atomic request.

Four bounded local CU cases pass without skips, using four lanes and two warps:
bare and Sv32 descriptor/atomic access with atomic-counter hardware off and on.
Each runs success, injected atomic page fault, denied image read permission and
healthy reuse; the Sv32 cases additionally reject a read-only atomic mapping.
They check exact old values, helper suppression, task and root capture, fault
address/cause, descriptor ownership and completion lease. The memory and atomic
responders are mocked, and the counter hardware is present but does not issue a
concurrent counter instruction in these cases.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitImageAtomicSpec'
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitImageSpec opengpu.core.GpuComputeUnitAtomicCounterSpec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderImageAtomicVectorSpec'
```

The seven existing image and atomic-counter cases and the focused vector fault
mask case also pass. The option remains disabled by default in system and
product configurations. These cases do not establish
live CU-to-CU contention in L2, complete resource coexistence, image/texture/attachment/DMA
ordering, API/compiler support, full capacity or physical qualification. H7 and
the overall mobile GPU objective remain open.

An additional small composition executes the image instruction on a real
four-lane CU and connects its bare-address atomic request to a two-client shared
L2. A competing atomic is offered at the same time to the other L2 port. The
test checks serially consistent old values, the final word, guard bytes, both
acknowledged writes, CU completion and the image lease. Instruction, descriptor
and lower memory are modeled; the competing port is not a second CU.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitImageAtomicL2Spec'
```

This one bare-address contention case passes without skips. The separate
[system test](SHADER_IMAGE_ATOMIC_SYSTEM.md) covers translated access through
shared L2 and one read-only mapping fault followed by recovery. Concurrent
counter instructions, other operations and general cross-domain visibility
remain unqualified.
