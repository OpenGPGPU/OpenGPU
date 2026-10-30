# Tessellation patch storage

`TessellationPatchStore` owns one input patch and one control-output patch at a
time. It accepts up to 32 input and output vertices, 64 components per vertex,
120 per-patch components, and 2,048 total control-output components. Input and
output each have 2,048 logical 32-bit words (16 KiB combined). Six tessellation
levels have separate 32-bit registers: GLES excludes those built-ins from the
per-patch component limit. This is logical capacity, not a measured SRAM area.

The store checks layout and access bounds, serializes reads and writes, holds
replies under backpressure, and blocks release until outstanding replies drain.
As with uninitialized shader outputs, unwritten words have undefined contents;
the enclosing producer must apply initialization and barrier rules. The
[OpenGL ES 3.2 specification, §11.2.1 and Table 21.45](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines the component limits and the separate tessellation-level accounting.

The compact test covers maximum layouts, a 2,049-component rejection, separate
input/output address spaces, all six levels at full output capacity, invalid
accesses, held replies and reuse:

```sh
sbt 'testOnly opengpu.graphics.TessellationPatchStoreSpec'
```

Control shader scheduling, invocation barriers, native patch submission,
physical SRAM mapping and full tessellation qualification remain open.
