# Independent Linux compute counter source

`DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES` publishes a mutable local
GEM counter view without consuming one of the four permanent storage windows.
Query `OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE`: GAM1 discovery, unified
commands and protected VM state are required, together with an enabled context
VM at submission. Existing ioctl layouts and the 38 binding slots are unchanged.

The version-1 payload embeds `drm_opengpu_compute_uniform_resources`, a separate
16-byte `{offset, handle, count}` counter source, and an outer version plus three
zero padding words. The handle belongs to the submitting DRM file; the offset
is four-byte aligned and count is 1..8 adjacent 32-bit words. Both versions must
be 1. The nested legacy counter binding and its COUNTERS flag must be zero.
Unknown flags, padding and disabled resource metadata are rejected. All uniform
block/default metadata may be zero to disable GUM1; otherwise every existing
uniform size, mapping and snapshot rule applies and GUM1 must be available.
Texture, image and storage capabilities remain independently checked.

Only local GEM DMA allocations are accepted. The selected words and their
enclosing transport lines and pages must fit the retained object, with no
physical/address overflow. Imported or uncertain backing, shared reservations
and aliases with shader, kernarg, textures, storage, images or uniform sources
are rejected. Different objects require disjoint complete allocations even
when their selected subranges do not overlap or their other use is read-only.

Counter data stays in the original GEM. The driver captures its identity,
offset, count and DMA view, builds an immutable GSB1 record, and maps only the
enclosing one or two pages at job-private command slot 7 (`0xb1c00000`). The
record remains in slot 5; GUM1 snapshots remain in slot 6. No permanent fifth
storage binding or copied mutable buffer is created. A complete compute job
uses seven of the existing eight temporary mapping entries. The counter GEM
is borrowed from the job's dependency references and never buffer-freed.

The input syncobj fence is captured and waited once before CPU snapshots.
The same `drm_exec` reservation locks cover dependency registration, immutable
snapshot copying and finished-fence publication. The counter waits READ usage,
which includes prior reader, writer and kernel fences, and publishes its
finished fence as WRITE to that exact original reservation before unlock.
Several queued jobs retain independent metadata/snapshots while their mutable
counter accesses follow these dependencies. CPU writes must not race the job;
CPU consumers wait its finished fence before reading the original local GEM.

The data mapping is uncached. Global atomic translation applies that PTE policy;
shared L2 discards a stale resident/sharer copy and reads authoritative lower
memory, then acknowledges its masked write before returning the atomic result.
The existing memory drain and completion identity/status checks remain intact.
The hardware completion is POPped before its fence signals. No rollback of
already acknowledged counter updates is promised on a later execution fault.
Retirement revokes temporary mappings before dropping the source/context
references. Failed revocation wedges the device and retains the entire job
instead of freeing backing behind a reachable translation. Cancel-before-run
creates no mapping. The 512-word forward-CFG shader sandbox is unchanged;
Linux indirect dispatch remains unsupported.

Local actual-function tests pass direct ioctl, common launch preparation,
dependency publication, private mapping, MMIO staging, held completion/POP and
retirement with mocked kernel primitives and device replies. The admitted set
contains thirteen distinct full uniform sources (twelve 16 KiB blocks plus all
1024 defaults), 32 distinct textures, four independent 128 MiB SSBOs, four image
units sharing those storage backings, and a separate eight-counter GEM:
51 unique job references and 52 reservation locks including shader. The test
checks every uniform byte and counter mapping, prior reader/writer fences,
two queued roots, a later read-only texture consumer's reservation dependency,
post-enqueue producer data, original-GEM write visibility,
single-word and cross-line/page endpoints, fresh backing/root reuse, rejected
metadata/aliases and preparation/map/submit/cancel/quarantine failures.
Device writes in this test explicitly resolve the mapped VA to the original
GEM; they are simulated replies, not GPU instruction execution.

Linux AArch64 GCC 13.3 passes `-O2 -Wall -Wextra -Werror`; macOS ASan/UBSan pass.
A real AArch64 Linux 7.2 external `gpu_drv.ko` builds with GCC 15.2.0. The existing
binding-based counter ioctl still rejects a counter alias with the fourth SSBO
and retains its separate three-SSBO case. This source variant closes that
driver admission/window limitation. It does not establish full Linux resource
execution, compiler/API integration, arbitrary alias coherence, indirect
ownership, four-CU product/physical qualification or H7 completion. No new GPU
model, Linux guest, frame or CI lane is added. The independent
[native host witness](NATIVE_HOST_RESOURCE_PUBLICATION.md) keeps its own scope.

Reproduce without a GPU simulation:

```sh
python3 scripts/test_compute_uniform_flow.py
CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_compute_uniform_flow.py
bash scripts/test_scripts.sh
# Use an already prepared AArch64 kernel, with an independent external M= stage:
make -C "$LINUX_BUILD" M="$PWD/driver" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- -j1 gpu_drv.ko
```
