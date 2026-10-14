# GTK composition shader captures

These are verbatim TGSI programs rejected during the GtkGLArea smoke run.
They are inputs for a future lowering regression, not passing tests.

The 2026-10-14 run `/tmp/opengpu-gtk-smoke12/gtk-smoke.log` recorded:

- Client draws 1–4 completed with `status=0`.
- Idle readback was `(51, 140, 46, 255)` and active readback was
  `(242, 89, 46, 255)`.
- `constant-position.vert.tgsi` was rejected by vertex lowering.
- `texture-swizzle.frag.tgsi` reached the ALU fallback, which rejected
  opcode 52 (`TEX`). `SEQ` and `CMP` already have ALU emitter support;
  the failure does not demonstrate a missing compare ISA instruction.
- The client logged `draw without a lowered shader or a colour target`
  after each frame's readback.
- The captured QMP PPM contained neither client color. The run was stopped
  while waiting for scanout, so this is a diagnostic capture, not a completed
  negative presentation test.

The fragment program samples a texture, compares a uniform with 1.0, and
selects either BGRA-swizzled or unchanged RGBA. A regression must exercise
both uniform values with distinguishable red and blue channels. The vertex
program multiplies a constant vector by a constant matrix; it has no input
attribute. A regression must verify the transformed position, not merely
shader acceptance. Both operations must execute on the GPU shader path.

The `weston-texture.*.tgsi` captures are supported shaders. The short runs
were manually interrupted and do not demonstrate a texture deadlock. In
`.artifacts/gtk-qualification-final/gtk-smoke.log`, compositor draws 1–10
completed with status 0, including full-frame draws taking about 16 minutes.
The client idle frame passed; the captured texture-swizzle rejection remains.

Before extending lowering, reproduce these inputs in a small GLES draw and
verify the same rejection. After fixing them, require GPU fences and output
pixels for that draw, then rerun the full GTK-to-QEMU presentation gate.
Do not substitute client readback for final scanout evidence.

The small GLES reproducer is `userspace/examples/gl_texture.c`. Build it with
the existing AArch64 guest toolchain, stage it as
`/root/opengpu-gl-texture-regression`, load the guest modules with
`/root/load_opengpu.sh`, and run:

```sh
OPENGPU_GL_GTK_SWIZZLE=1 OPENGPU_GL_EXIT=1 \
  /root/opengpu-gl-texture-regression
```

The 2026-10-14 cached guest runs are retained in
`.artifacts/gtk-swizzle-repro/`. The original driver run is the rejection
baseline. After the sampler matcher and fixed swizzle shader were added,
`run-final.log` reaches both GPU draws and produces `0xff0000ff` for the
unchanged red texel and `0xffff0000` for the BGRA-swizzled result; the clear
pixel remains `0x00ff00ff`. The small fragment blocker is therefore cleared.
The remaining GTK gate blocker is the separate constant-position vertex
program captured in `constant-position.vert.tgsi`.
