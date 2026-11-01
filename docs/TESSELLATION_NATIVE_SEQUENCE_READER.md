# Tessellation native draw-record reader

`TessellationNativeSequenceReader` reads a fixed GNT1 draw snapshot from a
bounded GPU-memory view and offers it to a sequenced tessellation frame owner.
The draft record has 512 little-endian words (2,048 bytes), a magic, size and
version in words 0–2, and 466 named fields with fixed maximum capacities of
16 lanes, eight warps, 16 varying slots and a 12-bit task tag. The generated
[C header](../driver/opengpu_native_tessellation_sequence_abi.h) and
[JSON schema](abi/native-tessellation-sequence-v1.json) specify the offsets.
All reserved words and unsigned padding must be zero; signed padding is sign
extended. The encoded task tag must be zero: the enclosing command owner
supplies the retained tag and frame identity separately.

The reader rejects malformed encodings, wrong frame identity, unsupported
nonzero fields and values that would be truncated by the configured lanes,
warps, varyings or tag width. Helper masks retain the native 16-lane-per-warp
layout in the record and are repacked per warp for smaller CUs; bits outside
the configured lanes or warps are rejected. It checks the complete aligned record view and
allows an exclusive end at 4 GiB. Each of 32 uncached 64-byte reads is retired
before the next; memory faults identify the line address. Offered reads and
draws remain held through cancellation. A terminal `frameStopped` signal may
flush an unaccepted draw; an offered memory read still drains. The completion
marks terminal-frame flush separately from descriptor rejection and identifies
whether a child result is present. Accepted child
work must complete before the held reader completion can be consumed. `readBytes` counts the
64-byte responses retired, including a fault response. Child semantic and
resource validation remains with the sequence and frame owners.

The enclosing command owner must reserve descriptor-memory transaction ID zero,
pin the descriptor, mappings and resources,
check descriptor aliases against writable storage, retain the frame and tag,
forward `childCancel`, assert `frameStopped` only when the frame can no longer
accept a draw, and drain the frame independently. This is a draft
record reader, not a complete native job reader, host/MMIO submission, driver
or compiler interface. It does not claim other tessellation domains, product
throughput or physical timing.

The local tests cover a complete snapshot, final record at 4 GiB, bad bounds,
reserved and narrow padding, frame identity and tag violations, terminal-frame
flush, capacity
truncation, memory faults, child failure, cancellation with held offers,
completion backpressure and reuse. A composition test reads the actual record
and drives two tasks through `TessellationPatchSequence`, checking their input
addresses, task IDs, assigned tag and primitive numbering. The schema test checks generated outputs
byte-for-byte; a C11 probe checks record size, magic and selected offsets.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationNativeSequenceReaderSpec opengpu.system.TessellationNativeSequenceAbiSpec'
cc -std=c11 -Wall -Wextra -Werror driver/tests/opengpu_tessellation_sequence_abi_test.c -o /tmp/opengpu_tessellation_sequence_abi_test
/tmp/opengpu_tessellation_sequence_abi_test
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
