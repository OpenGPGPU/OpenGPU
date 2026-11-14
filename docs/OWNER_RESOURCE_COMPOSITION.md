# Complete owner compute resource composition

`OwnerResourceProfile.computeStage` adds the checked shared atomic owner to
both CUs of `NativeOwnerHostProfile.target`. It keeps two CUs, sixteen lanes,
eight warps, 16 KiB shared SRAM with sixteen banks, the complete native and
compute services, 2048×2048 API storage capacity and the 640×480 workload.
The original owner-v1 factory and its discovery table remain unchanged.

The new identity is `two-cu-vga60-owner-v2-compute-stage`. Its discovery differs
from owner-v1 only at GCR flags address `0x368`: `0x3ffff` becomes `0x7ffff`.
Complete RTL emission and the original preparation/source/assertion checks
pass. The emitted `GpuSystem` retains two actual `GpuComputeUnit` instances;
their CU core contains `SharedAtomicVectorExecuteUnit`. All 796 generated
assertion files and the characterized SRAM model source remain present.
This establishes complete structural composition, without GPU execution or
a timing result.

The stage is intermediate: `fullResourceQualification=false` does not qualify
the final geometry/layered composition, any API, complete GPU execution,
whole-host clock or 60 FPS. A fresh complete model and matching executor are
required; an owner-v1 model cannot execute the new atomic cases as qualified
hardware.

The compute source controller retains sixteen original Mesa ESSL dynamic shared
atomic cases: all ten word operations and six signed overloads. Each source has
four workgroups of 128 invocations and initializes all 4096 shared words.
The independent returned-byte oracle checks conditional old values, inactive
lanes, final shared values and per-address old-value multisets. It permits
arbitration order only inside each original output bucket and checks every
other physical allocation byte unchanged. These sixteen sources and their
identities remain byte-identical to the preceding source corpus.

Three additional original ESSL cases, `shared-address-add`,
`shared-address-cas-match` and `shared-address-cas-miss`, initialize every shared
word at address `a` in group `g` to `0xa1000000+(g<<16)+a`. The conditional
operation targets `id^16`; eight invocations contend on each tail address
`4080+((id^group)&15)`. Addition uses 257. CAS compares the addressed seed
(or that seed XOR 1 for the miss case) and replaces it with `0xfedcba98`.
The independent oracle derives old words and final values from
this ESSL arithmetic, retaining arbitration freedom only within each exact
address/group bucket. Both legal forward/reverse schedules pass CPU oracle
tests; dropping the group XOR, permuting tail addresses or permuting conditional
addresses fails for all three operations. This distinguishes those addressing
errors even when their global old-word multisets are unchanged.
The legacy uniform-seed cases retain their narrower addressing coverage.
CPU capture never supplies GPU results. The legacy shared and full UBO/SSBO sources remain available.

Mesa scalarizes shared initialization into single-component MOVs. The emitter
now copies such a MOV directly after capturing its source register, retaining
the same destination slot, SIMT mask and address-range proof. Multi-component
MOVs retain parallel staging. This admits the nonuniform CAS programs within
the existing 512-word program limit without reducing initialized shared memory,
local items or output planes.

This extension changes the userspace compiler/producer and CPU oracle, not the
hardware factory, discovery, Scala/RTL resources or hardware dependencies. Those
hardware inputs are unchanged from `9a41cb046`; the earlier structural RTL result
does not qualify a new producer/model/executor pairing. A fresh actual execution
still requires its original source, preparation and assertion checks.

Prepare a new complete host with all assertion layers and the characterized
SRAM models:

```sh
python3 scripts/prepare_native_owner_flashsim.py --stage prepare --compute-stage \
  --java-heap 24G --output "$PWD/.artifacts/owner-compute-stage"
python3 scripts/prepare_native_owner_flashsim.py --stage check \
  --output "$PWD/.artifacts/owner-compute-stage"
```

The factory is deliberately separate from the frozen owner-v1 executor.
`qualify_compute_owner.py --case mandatory-compute` requires a matching
`two-cu-vga60-owner-v2-compute-stage` executor qualification before creating a
producer. Until the executor and its actual profile probe support that fresh
factory, this remains an unfinished integration gate; changing an executor
record or generating expected bytes cannot satisfy it. See
[the actual owner lifecycle](COMPUTE_HOST_EXECUTION.md) for the required
producer, original-memory oracle and synchronous cleanup protocol.

Actual Mesa 22.3.6 ESSL/NIR/TGSI, Gallium/library submission and the original
Linux ioctl/scheduler/MMU capture passed all nineteen dynamic atomic sources
with GCC ASan/UBSan on the final compiler source. The complete shared compiler
regression also passes, including its existing CPU tests and all nineteen
original dynamic shader admission cases within the unchanged 512-word limit.
All nineteen untouched original-memory outputs failed the independent oracle
as required. These are CPU capture results, without a GPU
completion, returned image, frame-rate result or hardware cleanup receipt.

Reproduce a source capture with the independently checked compute-stage
profile wire, the configured Mesa dependencies described in the owner guide,
and a fresh private build/session directory per case:

```sh
python3 scripts/test_gallium_compute_host.py --sanitize --capture-only \
  --build NEW_BUILD --session NEW_SESSION --profile-wire COMPUTE_STAGE_WIRE \
  --mesa-shared-ntt CHECKED_PRIVATE_NTT --case shared-dynamic-0
python3 scripts/test_gallium_compute_shared.py --sanitize \
  --build NEW_SHARED_BUILD --mesa-shared-ntt CHECKED_PRIVATE_NTT
```

Repeat with `shared-dynamic-0` through `shared-dynamic-9`, signed overloads
`shared-dynamic-i-0`, `-i-1`, `-i-2`, `-i-3`, `-i-4`, `-i-9`, and the three
`shared-address-*` names above. `mandatory-compute` includes all nineteen
atomic cases alongside the original shared and uniform sources. The profile wire
used for this CPU check describes the factory and is not a live DUT probe.
The staged NTT postimage is checked before compilation. The compiler container
keeps the shared Mesa/source/build trees read-only.

Reproduce focused CPU transport, rejection and configuration checks:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_host.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_protocol.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_oracle.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_qualify_compute_owner.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_prepare_native_owner_flashsim.py
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
  'testOnly opengpu.system.NativeOwnerHostProfileSpec'
```

The tests reject a relabeled old factory, any changed discovery word and
premature execution/performance labels. They do not establish the six complete
640×480 rendering results, cross-domain coherence, native geometry admission,
matching physical timing, or H6–H8 acceptance.
