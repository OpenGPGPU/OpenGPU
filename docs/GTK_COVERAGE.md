# GTK ellipse coverage guest proof

The full 640x480 FlashSim, QEMU, Mesa, and Debian proof runs manually. It
checks the exact inside and outside pixels and the `OPENGPU GL GTK COVERAGE
PASS` marker. The proof is not part of GitHub Actions: full GPU RTL elaboration
exhausted a 6 GiB Java heap on the standard hosted runner.

Prepare the sibling ARTI and FlashSim checkouts, Mesa 22.3.6 under
`depends/mesa`, and AArch64 libdrm under `depends/aarch64-root`. Then run:

```sh
bash scripts/run_gtk_coverage_guest.sh
```

To exercise the experimental tile attachment store and draw binner in the
same proof, run:

```sh
GPU_TILE_BINNING=1 JDK_JAVA_OPTIONS=-Xmx12g bash scripts/run_gtk_coverage_guest.sh
```

This passes `--tile-size 16 --tile-attachments --tile-binning` to RTL
emission. The default remains the existing scanline path. A cached tile-binned
model can be reused with `GPU_TILE_BINNING=1 GTK_PROOF_SKIP_BUILD=1`.
The tile-binned model and logs live under `../arti-work/debian-640x480-tile/`
and `../arti-work/gtk-coverage-proof-tile/`.

To run the multi-draw desktop after the GTK coverage check and capture QEMU's
actual 640x480 scanout, add `GTK_PROOF_DESKTOP=1`. The script verifies the
desktop's panel, window, triangle, and cursor pixels and writes
`desktop-scanout.ppm` in the proof directory. For example, reuse the cached
tile model with:

```sh
GPU_TILE_BINNING=1 GTK_PROOF_DESKTOP=1 GTK_PROOF_SKIP_BUILD=1 \
  bash scripts/run_gtk_coverage_guest.sh
```

To compare the complete tile scanout against a prior scanline proof, set
`GTK_PROOF_SCANOUT_REFERENCE=../arti-work/gtk-coverage-proof/desktop-scanout.ppm`.

To verify scissored GLES drawing in the same 640x480 scanout, use
`GTK_PROOF_SCISSOR=1`. This runs the scissor demo after the GTK coverage check,
checks yellow pixels inside the 16x16 scissor and blue pixels outside it, and
writes `scissor-scanout.ppm`. Compare the complete tile image with a scanline
reference using:

```sh
GPU_TILE_BINNING=1 GTK_PROOF_SCISSOR=1 GTK_PROOF_SKIP_BUILD=1 \
  GTK_PROOF_SCANOUT_REFERENCE=../arti-work/gtk-coverage-proof/scissor-scanout.ppm \
  bash scripts/run_gtk_coverage_guest.sh
```

Run the scanline `GTK_PROOF_SCISSOR=1` proof first to create that reference.
The desktop and scissor options select separate scanout captures.

The script builds the 640x480 GPU model and guest GLES driver, boots a fresh
Debian disk, loads the driver, and checks the coverage result. Once the model
and guest driver are already built, use `GTK_PROOF_SKIP_BUILD=1` to rerun just
the guest proof. Logs are kept in `../arti-work/gtk-coverage-proof/`.

The ordinary `Scala CI` workflow continues to run vector ALU specs and the
backend ellipse regression for relevant changes.

## Minimal GTK window

`userspace/examples/gtk_opengpu_smoke.c` is the first real GTK client. It
creates a `GtkApplication` window with a label and button, renders the panel
through `GtkGLArea` and the OpenGPU GLES driver, and queues a second GPU draw
when the button is clicked. The guest modules ISO now stages the source and a build helper. GTK and the guest development/debug tools are installed once into a dedicated image.
Cloud-init remains package-free during normal boots. Prepare the image with:

```sh
scripts/bake_gtk_debian_image.sh
```

Then point the proof at that image; ordinary boots remain package-free:

```sh
GTK_DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
  bash scripts/run_gtk_coverage_guest.sh
```

On that guest, build it with:

```sh
/root/opengpu_gtk_smoke_build.sh
```

Run it under the same environment as the GLES proofs:

```sh
LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root \
  /root/opengpu_gtk_smoke.bin
```

The first acceptance point is Weston DRM startup on the `Virtual-1` connector and
GTK/Wayland startup without a display error. The next acceptance point is
`GtkGLArea` realization and one successful frame, followed by the
`OPENGPU GTK REDRAW` line after pressing the button.
The existing ellipse, desktop, and scissor proofs remain the deterministic
scanout checks.

The opt-in `GTK_PROOF_SMOKE=1` gate checks the idle frame and button-triggered
active redraw, then keeps Weston and the client alive while QMP `screendump`
captures the QEMU surface. The 640x480 capture must contain the active panel
color. The ARTI sideband dump waits for that color anywhere in the full
scanout, so the check does not depend on the GTK window's compositor position.
`run-metadata.txt`, `gate.log`, `final-guest.log`, the integration config,
worktree patch, source copies and artifact hashes are retained in the proof
directory. **Final scanout verification has not passed.**

For an iterative check, set `GTK_PROOF_KMS_MODE=64x64` (or `320x240`). The
driver advertises these smaller KMS modes dynamically, and the smoke client
uses a compact layout for 64x64. This reduces the long compositor draws from
minutes to seconds on FlashSim:

```sh
GTK_DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
GTK_PROOF_SMOKE=1 GTK_PROOF_KMS_MODE=64x64 \
GTK_PROOF_SCANOUT_ENABLE=2 GTK_PROOF_SCANOUT_MATCH= \
GTK_PROOF_SCANOUT_PERIODIC=1 \
GTK_PROOF_SKIP_BUILD=1 GTK_PROOF_SKIP_COVERAGE=1 \
bash scripts/run_gtk_coverage_guest.sh
```

Use 640x480 for the final full-size qualification. The smaller-mode run still
requires the authoritative scanout capture to pass; client readback alone is
not sufficient.

When the compositor's output transform changes the client texel values, use
`GTK_PROOF_SCANOUT_PERIODIC=1`. The gate retains a periodic ARTI framebuffer
capture and validates its mode size, substantial non-black content, and colour
complexity after the active redraw marker. The strict default still looks for
the exact `f2592e` texel.

`GTK_PROOF_QEMU_DISPLAY` defaults to `none`; it can select a locally supported
backend for diagnostics. The Cocoa diagnostic run in
`/tmp/opengpu-gtk-smoke15` timed out its first GPU fence. This does not establish
that a graphical backend is required: ARTI's `arti_gfx_update` reads scanout
memory independently of the UI backend. No backend change is a proven fix.

The 2026-10-14 diagnostic run in `/tmp/opengpu-gtk-smoke10` reached both
client frame checks with successful fences, but GTK's composition shader was
rejected. The captured TGSI inputs are in `userspace/tests/gtk-composition/`.
A later run also reproduced a cross-client vertex-buffer bind `EBUSY`.
Client readback success alone therefore does not establish presentation.

The rebuilt model completed compositor draws 1–10 and the client idle frame
in `.artifacts/gtk-qualification-final/gtk-smoke.log`. Earlier manually
interrupted runs do not establish a texture deadlock. Full-frame draws take
about 16 minutes. The gate now defaults to no total time limit
(`GTK_PROOF_SMOKE_TIMEOUT_S=0`), including the SSH wait and scanout polling.
An explicit positive value remains available for bounded diagnostics.
The per-fence wait and kernel watchdog are separate from this total limit.

Build the guest module through `scripts/run_arti_gpu.sh` (as the display build
does). That script supplies `OPENGPU_DRAW_WAIT_MS` scaled for simulation and
`OPENGPU_FENCE_SLICE_MS=4`. A bare manual kernel-module build uses a 30-second
watchdog, which is shorter than the measured 640x480 compositor submissions.
The differing watchdog settings explain the early reset in the manual-build
diagnostics; they are not evidence of a source-level driver regression.

The gate is intentionally opt-in because a 640x480 FlashSim compositor run is
slow and requires the prepared GTK image, QEMU build, and host networking:

```sh
GTK_PROOF_SMOKE=1 GTK_DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
  bash scripts/run_gtk_coverage_guest.sh
```

The QMP framing helper can be checked without a guest or socket:

```sh
PYTHONPATH=scripts python3 scripts/test_qmp_screendump_protocol.py
```

The opt-in smoke gate now starts Weston through the OpenGPU EGL path and reports
`GL vendor: opengpu` and `GL renderer: opengpu`. The TGSI texture shader and
indexed triangle lowering are implemented in the Gallium driver. The remaining
GPU wait budget is selected with `OPENGPU_FENCE_TIMEOUT_MS`; the smoke gate uses
1800000 ms because a 640x480 FlashSim compositor frame can take several
minutes. Fast hardware or small tests can set a shorter value. The kernel
watchdog remains independent and still reports real GPU faults.

`OPENGPU_TRACE_DRAWS=1` logs each submitted triangle's framebuffer dimensions,
packed vertex positions (hex float bits for matrix shaders, Q16.16 otherwise),
timeout, and submission-to-fence elapsed milliseconds. The GTK smoke runner
enables it for both Weston and the client. A `submit` without `complete` means
the draw call is still outstanding; it does not by itself establish a GPU hang.
Correlate it with model ticks and the guest thread stack. A sampled kiosk-shell
run showed Weston in `DRM_IOCTL_SYNCOBJ_WAIT` and GTK in `poll`; neither GTK
rendering nor a requirement for asynchronous compositor submission has yet
been proven by that observation.

On 2026-10-10 the traced desktop-shell run submitted draw 1 at 640x480,
with float input vertices `(0,0,0,1)`, `(640,0,0,1)`, `(640,480,0,1)`,
no texture, blend `0x511`, and a 52-instruction fragment program. Its fence
wait returned `ETIMEDOUT` after 605023 ms with a 600000 ms budget. A subsequent
guest stack still showed Weston in a syncobj wait (possibly a later operation).
The model continued ticking, but ticks alone do not demonstrate fragment or
pixel progress. This is not evidence that asynchronous submission is required,
nor that increasing the timeout will solve the problem. The next diagnostic
must inspect execution progress or reproduce this exact draw in isolation.


### Measured compositor cost (2026-10-11)

With a 1800000 ms fence budget, the real Weston desktop-shell run completed
its first two 640x480 triangles successfully in 925181 ms and 1024258 ms.
Together these took 32.49 minutes; they are two triangles, not two complete
frames. This historical run stopped before a complete GTK startup proof, and
the earlier ten-minute timeout was insufficient for these submissions. A later
run on 2026-10-13 verified the real Weston desktop shell in the QEMU window;
GtkGLArea client redraw remains a separate acceptance point.

`GTK_PROOF_WESTON_REPRO=small|medium|full` isolates the uniform-color/opacity
shader, pixel-space transform, and premultiplied-alpha blending in `gl_fill`.
The small (32x24) and medium (160x120) triangle runs passed pixel checks and
completed in 5277 ms and 62928 ms respectively. These are diagnostic workloads,
not substitutes for the GTK window gate.

`GTK_PROOF_SMOKE_TIMEOUT_S` sets the overall GTK startup/redraw budget in
seconds, separately from `GTK_PROOF_FENCE_TIMEOUT_MS`. The default is `0`,
which follows the guest and QMP logs until they report success or a failure.
Set a positive value only for a deliberately bounded diagnostic run. A fence
value of `0` selects the driver's largest supported wait interval; it does not
add a total test deadline.

QEMU already runs with two guest CPUs. During these draws its host process
consumed approximately one CPU core; increasing guest vCPUs does not parallelize
the GPU model's serial execution. No higher-vCPU speedup has been measured.

The smoke runner supplies a Weston configuration with `idle-time=0` and shell
animations disabled. Weston 10's installed manual documents a default
300-second idle timeout and a startup fade; both are unsuitable when a single
draw takes about 900 seconds. This preserves the desktop shell, DRM backend,
OpenGPU renderer, and GTK frame checks. The controlled configuration is now the
recommended path for reproducing the verified Weston shell; GtkGLArea redraw
and button-triggered client work remain separate follow-up checks.

### Real Weston desktop milestone (2026-10-13)

The GTK-enabled Debian image now boots the stock Weston DRM backend through
the OpenGPU DRM/EGL path and presents an upright 640x480 shell in QEMU. The
checked-in screenshot is
[`docs/images/weston-desktop-640x480.png`](images/weston-desktop-640x480.png).
This closes the Linux DRM/KMS, Mesa/Gallium, Weston, ARTI, RTL scanout, and
QEMU presentation loop. The deterministic `opengpu_pipe_desktop` scanout
proof remains the pixel-exact regression; this run is the full-system
integration proof.


## Reusing the GTK-enabled image

To boot the real lightweight desktop shell in the graphical QEMU window, use
the GTK-enabled image and select the ARTI `weston` display mode:

```sh
OPENGPU_AUTO_DISPLAY=weston \
DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
DISK=/tmp/arti-weston.qcow2 REBUILD_DISK=1 \
QEMU_DISPLAY=cocoa ./scripts/run_arti_debian.sh
```

This starts the stock Weston DRM backend and desktop-shell through the
OpenGPU DRM/EGL path. It is a real Wayland desktop, separate from the
deterministic `opengpu_pipe_desktop` scanout proof. The GTK image must already
contain Weston; the image produced by `scripts/bake_gtk_debian_image.sh` does.

The prepared image is a normal Debian ARM64 qcow2 image, so the other ARTI
runners can use it as their base as well:

```sh
DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
  scripts/run_arti_debian.sh
```

`DEBIAN_BASE` is only used when the persistent `DISK` does not exist. To start
from the GTK image again, choose a new `DISK` or set `REBUILD_DISK=1`. The
qualification scripts should keep using the clean default image for reproducible
baseline runs; use the GTK image for interactive and application development.
