# Linux direct-compute uniform publication

`DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES` adds version-1 GUM1 publication to
the existing compute scheduler. Query `OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS`
first: the driver requires discovered GUM1, unified commands, protected VM
state and an enabled context VM. Absent GUM1 is neither probed nor written.
The feature describes this submission path, not GLES support.

`drm_opengpu_compute_uniform_resources.resources` contains the existing direct
launch and optional texture/image/storage/counter descriptions. Its
`resource_flags` selects storage, images and, with
`OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS`, a counter view. Disabled arrays and
counter fields must be zero. Texture metadata is copied once from its user
array. Version must be `OPENGPU_COMPUTE_UNIFORM_VERSION`; padding and unknown
flags must be zero.

The twelve `blocks` and separate `defaults` are `{offset, handle, bytes}` GEM
views. Handles belong to the submitting DRM file. The 12-bit `active_mask`
selects nonempty blocks; inactive views must be zero. Each block contains at
most 16384 bytes; defaults contain at most 4096 bytes / 1024 components. Offsets
and lengths are multiples of four, and the complete range must fit its object.
A zero default handle disables that view and requires its other fields zero.
At least one block or the default view must be present. Logical blocks 0..11
map to native bindings 0..11; this is not the GLES 36-index binding namespace.

Only local CPU-mapped GEM sources are admitted. Imports, unknown/shared backing
identity and aliases with shader, writable kernarg, storage, images or counters
are rejected. Several read-only views of one source object, including a texture
alias, retain one dependency with its strongest usage. The existing image/
storage/texture alias checks continue to apply independently.
Different objects must have disjoint complete allocations, including read-only
texture peers: separate reservations cannot order producers of overlapping
memory. Selecting disjoint subranges does not establish that ownership.

The ioctl captures and waits its binary input syncobj fence once, before the
CPU snapshot. It locks all source and job reservations, waits uniform producer
WRITE fences, and copies the bytes while those locks remain held. CPU writes
must not race submission. Enqueue publishes the same scheduler finished fence
as READ usage to each source reservation before releasing the locks; source
GEMs and the context remain referenced through retirement. Output syncobj and
other resource dependencies retain their existing behavior. This ioctl may
block while preparing its immutable snapshot.

GUR1/GUB1 records and copied payloads occupy a job-owned private allocation.
The first 4096 bytes contain the root, 24 native binding slots and default
record. Block data starts on 256-byte boundaries, defaults on a 64-byte
boundary; padding is zero. Twelve full blocks plus all defaults need exactly
204800 bytes, mapped uncached in command VA slot 6 (`0xb1800000`). Smaller
publications pack only their active data. Records contain driver-generated
identity and addresses; userspace cannot substitute a GPU pointer. The writable
kernarg is the excluded private interval. CPU-written snapshot lines are
invalidated under the hardware submit lock before GUM1 staging and SUBMIT.
A subsequent command without uniforms explicitly clears GUM1 CONTROL.

The snapshot remains intact through held completion, execution faults and
scheduler cancellation. The hardware completion path validates the completion
and POPs it before signaling its fence. Retirement revokes temporary VM mappings
before freeing snapshots, source references and context. Failed revocation
wedges the device and retains the backing rather than freeing reachable data.
Queued jobs have independent snapshots; the private VA is reused after the
preceding job retires, including when the next job selects another context root.

Only the new uniform-bearing validator profile admits scalar `uniform` and
unmasked vector `vuniform`. Sources must be defined and vector length valid;
results lose ordinary-address and packet provenance. Dynamic indices and
offsets are checked by the retained uniform service. Existing profiles continue
rejecting these instructions. The 512-word limit, forward-only control flow,
ordinary-memory sandbox, converged barriers and atomic capability checks remain
unchanged. This does not admit the larger backward-polling native qualification
program, general compute loops or Linux indirect dispatch.

Local validation passes the actual ioctl, scheduler, job mapping, hardware
preparation, MMIO staging, completion POP and retirement functions with mocked
kernel primitives/device replies. Its largest reachable combination retains
13 distinct full-size uniform source GEMs, 32 distinct texture GEMs and four
128 MiB storage views, with four images sharing those storage backings:
50 unique scheduler references and 51 reservation locks including shader.
Every copied payload byte is checked, including all 1024 distinct defaults.
Further cases cover read-only alias deduplication, two queued immutable
snapshots/two roots, default-only publication, producer/input waits, held
completion, execution-failure status, pre-run cancellation, rejected metadata,
allocation/map/invalidate/enqueue failures, revocation quarantine and reuse.
ASan/UBSan pass. Generated kernel/userspace records remain 64 bytes, and a real
AArch64 Linux 7.2 `gpu_drv.ko` builds with GCC 15.2.0. These are driver-function
and module-build results; no Linux guest or new GPU model ran.

The binding-based counter UAPI shares the four storage VM windows. Its C test
retains rejection of a fourth-SSBO counter alias and the admitted three-SSBO
case. The separate [counter-source submission](COMPUTE_COUNTER_SOURCE_SUBMISSION.md)
maps an independent mutable GEM in a job-private window and admits all four
SSBOs alongside every uniform, texture and image view: 51 unique references and
52 reservation locks. This is driver-function/module-build evidence.
Full resource execution through Linux, compiler/GLSL/API integration,
indirect ownership, broader coherence, four-CU product/physical qualification
and H7 remain open. The later [Linux resource-view guest](LINUX_NATIVE_RESOURCE_EXECUTION.md)
adds actual execution of twelve full blocks/all defaults beside four independent
128 MiB SSBOs, 32 textures, four images and an independent counter source, with
bounded fault and fresh reuse. Shared traversal and complete profile/ordering
qualification remain separate. The [native host witness](NATIVE_HOST_RESOURCE_PUBLICATION.md)
retains its own hardware execution scope.

Reproduce the relevant local checks without rendering or a GPU simulation:

```sh
bash scripts/test_scripts.sh
CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_compute_uniform_flow.py
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  'Compile/runMain opengpu.elaboration.EmitNativeUniformAbi --check'
# Against an already prepared AArch64 kernel; no kernel build is required:
make -C "$LINUX_BUILD" M="$PWD/driver" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- -j1 gpu_drv.ko
```
