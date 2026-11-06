# Owned fragment code for native tessellation

The internal `opengpu_tessellation_resources_sched_submit` entry requires a
fragment-code plan for every GFS state referenced by a nonempty rasterized
sequence. Missing, duplicate or conflicting plans reject before enqueue or
fence publication. There is no caller-owned code fallback in this entry.
Zero-count sequences, unreferenced states and raster-discard jobs require no
fragment program. The older trusted and uniform-only entries retain their
existing producer obligations.

Plans contain stable kernel code bytes, a byte length, an entry offset and the
expected compilation base VA. Referenced programs are packed in state-handle
order at 64-byte boundaries within the existing 4 MiB private fragment-code
window, slot 1. The expected base must equal the planned address; lengths and
entries must be word aligned, the entry must lie inside its program, and the
complete rounded mapping must fit its window and the 32-bit physical address
space. An exclusive physical end at 4 GiB is valid. No product resource or RTL
capacity changes.

The scheduler copies each program, zeroes allocation padding and rewrites only
the copied GFS entry PC using the generated ABI field. Caller code and plans
may be released after submit returns. Code pages are private, read/execute and
not writable; descriptor pages remain read-only and non-executable. The actual
transport verifies exact backing, permissions and absence of writable aliases
in that VM under the submission lock, then invalidates code and descriptors
before publishing the command.

The run worker reserves tracking capacity before mapping either kind of
snapshot, so a full tracking array cannot leave an untracked mapping.
Retirement revokes every recorded window before freeing backing. Failed
revocation wedges submission and retains code, descriptors, context and
dependencies; it is not automatic recovery.

This is an ownership contract for trusted compiled code. The producer must
compile or relocate embedded addresses for the declared VA, or supply code
that is position independent with respect to its own address. Copying and
rewriting an entry does not relocate instructions or validate arbitrary
control flow. Control/evaluation programs, other resource operands,
cross-context data writers and the remaining admission obligations stay with
the trusted producer. No userspace ioctl, GLES capability or H8 completion is
introduced.

The production C scheduler, MMU and command functions pass strict Clang and
ASan/UBSan checks for all three domains, exact capture after caller-byte
replacement, required-plan and VA failures, the full code window, physical
address boundaries, occupied mappings, writable aliases, abort, failed
revocation and healthy reuse. Existing descriptor, uniform, 32-unit texture
and three-domain export checks also pass. These are CPU tests with mocked
device completion; they do not establish native GPU execution or timing.

Reproduce the focused ownership checks:

```sh
python3 scripts/test_tessellation_schedule.py --owned-code
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_tessellation_schedule.py
python3 scripts/test_tessellation_canonical.py
python3 scripts/test_tessellation_transport_submit.py
cc -std=c11 -O2 -Wall -Wextra -Werror \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Idriver/tests/mmu_stubs driver/tests/opengpu_mmu_test.c -o /tmp/opengpu-mmu-test
/tmp/opengpu-mmu-test
```
