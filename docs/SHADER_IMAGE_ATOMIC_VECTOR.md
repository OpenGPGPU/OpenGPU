# Internal vector image atomic adapter

`ShaderImageExecuteUnit(atomicAvailable = true)` accepts an internal CUSTOM-1
function 12 operation whose suboperation occupies bits 14:12. It reuses the
existing per-warp image parameter pairs: pair 0 supplies unit and control,
pair 1 supplies z and its reserved word, and pair 2 supplies the atomic operand
and compare operand. The instruction's vector source operands supply x and y;
the destination receives the old word. All participating tuples are checked
before the first lane is offered. Only lanes in the execution, active and
side-effect masks issue requests, so helper lanes do not modify image memory.

The adapter serializes lanes through its external atomic-service port, holds an
offer until accepted, waits for the response, suppresses vector writeback after
fault or cancellation, and retains the fault address and page-fault flag. A
later fault cannot roll back an earlier acknowledged atomic. The parent must
connect a task-owned service, forward cancellation and retain descriptor and VM
ownership until retirement. The atomic response identity, unit and lane tag must
match the accepted request.

The default is disabled: function 12 remains outside the production decoder and
CU routing, and this adapter does not expose or advertise an ISA/API capability.
Opcode assignment, compiler support, cross-domain memory ordering, actual shared
atomic contention and product qualification remain open. The connected internal
atomic service is described in [checked image atomic access](SHADER_IMAGE_ATOMIC_ACCESS.md).

One atomic adapter case and six existing image-unit cases pass locally with no
skips, using four lanes and two warps without a frame:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderImageAtomicVectorSpec opengpu.graphics.ShaderImageExecuteUnitSpec'
```

The atomic service response is mocked. The new case checks operands, lane tags,
helper filtering, held offers, held results, fault retirement, helper-only
execution and cancellation; it does not execute the instruction in a CU or
prove atomicity in L2.
