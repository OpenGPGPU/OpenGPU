# Experimental tessellation completion snapshot

The opt-in native tessellation host writes a GNZ1 snapshot to the same twelve
read-only MMIO words used for native binned completions, beginning at `0x180`.
Read the words while the corresponding completion is held: POP or reset clears
its visibility. Check the magic before interpreting the snapshot; GNZ1 and GNX1
are distinct formats. This layout and decoder do not advertise a product
capability or implement driver submission, allocation pinning, or alias checks.

The generated [C layout](../driver/opengpu_native_tessellation_completion_abi.h)
and [schema](abi/native-tessellation-completion-v1.json) describe the fields.
The [decoder](../driver/opengpu_tessellation_completion.h) accepts host-order
MMIO register values, rejects wrong magic and reserved or inconsistent fields,
and leaves its output unchanged on failure. Reader address and draw are raw
owner fields; only interpret them with the reported reader stage. The tile and
input fault fields are meaningful only when their respective valid bits are set.
The legacy completion counters are not a qualified tessellation diagnostic ABI.

The unified completion drain captures a valid fault snapshot before POP when
the reported opcode is tessellation and the register aperture contains the
whole window. It retains the decoded fields in the internal fault snapshot only
when the command ID and opcode match the waiting fence. Other faults clear that
internal detail. IRQ and polling drains serialize the status check, snapshot
reads and POP under the fence lock; abort uses the same lock when discarding a
completion. A pthread-based mock runs the actual drain concurrently with a
held completion window for IRQ/poll and abort/drain races: the pre-fix code
popped twice and read after POP, while
the locked code retains one pop and the matching snapshot. Reproduce with
`python3 scripts/test_unified_completion_race.py`; it does not exercise Linux
IRQ scheduling or real MMIO. A bad magic, invalid field or status mismatch is
ignored; the existing public fault ioctl is unchanged. Runtime tessellation
submission and public reporting of these details are not implemented.

Reproduce the hardware-layout, generated-file and C decoder checks with:

```sh
sbt 'testOnly opengpu.system.TessellationNativeCompletionInfoSpec'
```

The decoder and generated definitions support both userspace and kernel builds.
A small test module using the decoder also built against Ubuntu Linux 6.8
headers. With a configured kernel headers tree, reproduce that check with:

```sh
bash scripts/test_tessellation_completion_kernel.sh /path/to/linux-headers
```

The affected driver objects compile against Ubuntu 24.04 Linux 6.8 headers:

```sh
make -C /path/to/linux-headers M="$PWD/driver" opengpu_hw.o opengpu_compute.o
```

The full driver module also builds with this change against Ubuntu Linux
7.0.0-42 arm64 headers (`make -C /path/to/linux-headers M="$PWD/driver"
modules`) and the configured Linux 7.2.0 arm64 tree:

```sh
make -C /path/to/linux-source O=/path/to/linux-build M="$PWD/driver" \
  ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- modules
```

A full module build on Linux 6.8 remains unqualified; that kernel also
requires DRM scheduler and vblank API adaptation. Full driver integration,
physical alias provenance and product qualification remain open.
