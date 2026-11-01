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

Reproduce the hardware-layout, generated-file and C decoder checks with:

```sh
sbt 'testOnly opengpu.system.TessellationNativeCompletionInfoSpec'
```

Full driver integration, physical alias provenance and product qualification
remain open.
