# Native Mesa pixel formats

Framebuffer and texture storage now follow their declared Gallium formats.
Shader registers and fragment staging retain canonical `0xRRGGBBAA` values.
The sampler selects bytes before filtering; the output merger decodes the
destination before blending and selects bytes when writing. These are
combinational selections with no additional pipeline stages. There is no CPU
image-format conversion or transfer shadow allocation.

| ABI value | Gallium format | Bytes at increasing addresses | KMS FourCC |
| --- | --- | --- | --- |
| 0 | Legacy low-level ABI | A, B, G, R | RGBA8888 |
| 1 | R8G8B8A8_UNORM | R, G, B, A | ABGR8888 |
| 2 | B8G8R8A8_UNORM | B, G, R, A | ARGB8888 |
| 3 | R8G8B8X8_UNORM | R, G, B, X | XBGR8888 |
| 4 | B8G8R8X8_UNORM | B, G, R, X | XRGB8888 |

RGBX/BGRX sampling and destination blending use alpha 255; writes set X to
255. RGBA/BGRA textures filter alpha with the same bilinear/trilinear weights
as RGB. Scalar clear patterns are packed once into the selected storage order.
Existing copies for framebuffer dimensions and pitched textures remain row
copies in the same format.

Capability bit 25 (`NATIVE_PIXEL_FORMATS`) gates the new ABI. Render descriptor
word 13 uses bits 2:0 for the framebuffer and 6:4 for the texture. Its size
remains 64 bytes; zero preserves legacy storage. Submit flags 6:4 select the
framebuffer; texture resource flags 10:8 select the sampler format. The kernel
rejects reserved values and nonzero selections on older hardware. Gallium
requires the capability rather than advertising incorrect CPU mappings.

Scanout register value 2 selects ABGR/XBGR; values 0/1 retain legacy RGBA and
ARGB/XRGB. Local QEMU companion commit `a67092b243` adds the decoder. Native
GLES fixtures pass their actual GBM FourCC to KMS and compare semantic RGBA
readback, rather than reconstructing a GPU word from incorrectly ordered bytes.

Validation: 92 sampler/output-merger/host RTL tests pass. A shared four-CU,
512-bit AXI test checks every framebuffer pixel across all 25 sampler/target
format combinations, with translated texture reads and write combining.
Kernel-free driver tests, Linux 7.2 module compilation, Mesa DRI compilation
and all 31 native GLES fixture cross-builds pass. Standalone QEMU scanout tests
check all five buffer layouts; isolated device compilation passes.
Another 31 texture-instruction/fragment-stage RTL checks pass, including
bilinear/trilinear alpha filtering and helper/output-control regressions.

The fresh full model passes 18 compute replays, all 21 GLES cases, GTK
idle/active pixel readback and periodic scanout at 64x64. The GLES cases
include distinct RGBA channels, alpha, padded textures and swizzling. Mesa's
EGL config deduplication now preserves different native visuals, and the
stock swizzle shader preserves green and alpha in canonical RGBA order.
Hashes, configuration and scope are recorded in
[the native qualification report](performance/shared-cu-native-qualification-2026-10-16.json).
The actual 640x480 Weston desktop also passes on both instrumented and PGO
builds, with visually verified background, panel, launcher and clock. See
[the desktop qualification](performance/shared-cu-desktop-qualification-2026-10-16.json).
Clean simulator timing remains pending; instrumented or concurrent guest runs
are not performance baselines.

Commit `f974cb4` fixes the separate four-CU gradient coherence failure: external staging-write
IDs could alias CU0 in the directory's requester mask and skip its probe.
All 27 L2 tests and the matched complete-pool 1/2/4-CU scenes now pass. See
[the full-pool measurements](performance/shared-cu-full-pool-2026-10-15.json).
