# GTK composition shader captures

These are verbatim TGSI programs captured from the GtkGLArea smoke run.
The swizzle fragment and the constant-position vertex program now lower.
The notes below record the original rejection.

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
The client idle frame passed; the captured texture-swizzle rejection remains in that log.

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

`constant-position.vert.tgsi` is `CONST[0][0..3] * CONST[0][4]` with no
vertex attribute. The vertex menu selects `vertex_mvp` and the draw stages
that fifth vec4 as the object-space position, so the vertex core performs
the multiply. `opengpu_vertex_lower_kind` returns 2 for this program. An
identity matrix and `(0.5, -2, 0, 1)` stage those floats and the shader's
per-term Q16.16 sum is `(32768, -131072, 0, 65536)`. A position attribute
still returns 1 and is not replaced by the constant. The fragment bound with
that vertex shader is `MOV OUT[0], CONST[0][0]` and stays on the uniform-colour
menu. The texture frame from the same log, `TEX` then `SEQ` then a red/blue
`CMP`, stays on the sampler menu, as does the vertex program that writes
position `zw` from `(0, 1)` and a vec2 varying.

The two captured programs have been through that sequence. On 2026-10-22 the
guest gates in `../arti-work/gtk-coverage-proof/` log no `did not lower`.
Client readback is `(242, 89, 46, 255)` in both modes, and the scanout checker
accepts the presented rectangle at channel tolerance 3. A same-order pair
still applies the red/blue swap. An RGBA view into a BGRA window keeps
`sampler_format=1` and `folded_swap=0`, because the format store already
places those channels.

The 64×64 periodic run (`GTK_PROOF_KMS_MODE=64x64`,
`GTK_PROOF_SCANOUT_ENABLE=2`, empty `GTK_PROOF_SCANOUT_MATCH`) is
`gate-64x64-pass.log`. The client is 64×64. The scanout rectangle is
`[8, 14, 50, 36]` with 1887 active pixels. Its sha256 is
`96023b41d72eb995b1e9829cc1fb5281eb71cee973a1b5c13f216cab177e7d7d`.
That PPM was replaced by the later 640×480 save. Switching into 64×64 is a
second scanout enable, which is why enable count 2 writes the frame there.

The 640×480 periodic run (`GTK_PROOF_SCANOUT_ENABLE=1`, empty match) is
`gate.log`, and the saved frame is `gtk-smoke-verified.ppm`. The client is
280×124. The scanout rectangle is `[93, 223, 227, 72]` with 16571 active
pixels. Its sha256 is
`7283ef88946c6e4bb3e474aae530d86c89bbc92f18e075d0165ed0aa9fa7eefa`.
The 227×72 block is the single colour `f1582d` (16344 pixels). The image has
16571 pixels of that colour. Exact `f2592e` is absent. `f1582d` is
`floor(channel * 255 / 256)` from white modulation of the client orange, and
it is inside the checker's tolerance. The boot console is already 640×480, so
enable count 2 never dumps on this mode.

A divergent per-pixel branch, a second colour output, a loop, or a general
NIR compiler waits for a newly captured program that fails to lower. The
remaining full-frame cost is the serialized `vtex.sample` path.
