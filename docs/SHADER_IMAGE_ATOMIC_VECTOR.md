# Internal vector image atomic adapter

`ShaderImageExecuteUnit(atomicAvailable = true)` accepts an internal CUSTOM-1
function 23 operation whose suboperation occupies bits 14:12. It reuses the
existing per-warp image parameter pairs: pair 0 supplies unit and control,
pair 1 supplies z and its reserved word, and pair 2 supplies the atomic operand
and compare operand. The instruction's vector source operands supply x and y;
the destination receives the old word. All participating tuples are checked
before the first lane is offered. Only lanes in the execution, active and
side-effect masks issue requests, so helper lanes do not modify image memory.

The adapter serializes lanes through its external atomic-service port, holds an
offer until accepted, waits for the response, suppresses vector writeback after
fault or cancellation, and retains the fault address and page-fault flag. A
later fault cannot roll back an earlier acknowledged atomic. The
`ShaderImageAtomicVectorService` connects the adapter to the task-captured
descriptor-backed service, forwards cancellation to both, and exposes separate
regular-image and translated atomic-descriptor memory ports. Its parent must
translate regular image traffic, translate and arbitrate the virtual atomic
request using the captured root, and retain descriptor and VM ownership until
retirement. Atomic response identity, unit and lane tag must match the request.

The default is disabled: function 23 remains outside production CU routing, and
this adapter does not expose or advertise an ISA/API capability. An isolated
`FullInstructionDecoder(imageAtomicAvailable = true, imageAvailable = true)`
can decode it only while its image task permission is enabled. Existing decoder
configurations leave the option off.
Functions 12 and 13 remain reserved for the existing quad derivatives; the
internal atomic matcher does not match either encoding.
Opcode assignment, compiler support, cross-domain memory ordering, actual shared
atomic contention and product qualification remain open. The connected internal
atomic service is described in [checked image atomic access](SHADER_IMAGE_ATOMIC_ACCESS.md).

One atomic adapter case and six existing image-unit cases pass locally with no
skips, using four lanes and two warps without a frame:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderImageAtomicVectorSpec opengpu.graphics.ShaderImageExecuteUnitSpec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderImageAtomicVectorServiceSpec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderImageAtomicL2Spec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ImageAtomicEncodingIsolationSpec'
```

The atomic service response is mocked. The new case checks operands, lane tags,
helper filtering, held offers, held results, fault retirement, helper-only
execution and cancellation; it does not execute the instruction in a CU or
prove atomicity in L2. The additional small composition case passes with real
descriptor admission and two acknowledged lanes, then checks a later-lane fault,
cancellation and repeated instructions; its physical atomic responder is mocked.

A small test-only composition also connects the vector service through the
existing bare-address atomic translator to a real two-client shared L2. An image
atomic and a competing client add to the same word; returned old values must
match a serial order, the final word must be exact, surrounding bytes remain
unchanged, and both lower writes drain. Descriptor and lower memory are modeled.
This does not execute the instruction in a CU, test translated atomic addresses
through the L2, or establish general cross-domain ordering.
The encoding isolation test checks that both quad derivative encodings remain
legal and distinct, while function 23 remains illegal in the default decoder.
Seven small decode cases pass with the explicit option and task permission varied:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.frontend.decode.ImageAtomicAvailabilityDecodeSpec opengpu.core.frontend.decode.ImageExecutionAvailabilityDecodeSpec opengpu.graphics.ImageAtomicEncodingIsolationSpec'
```

No production CU instantiates the decoder with image atomics enabled; CU dispatch,
fault retirement and atomic service routing must be connected before doing so.
The four existing extension-decoder cases also pass with
`sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 'set Test / parallelExecution := false' 'testOnly opengpu.core.frontend.decode.ExtensionDecoderSpec'`.
