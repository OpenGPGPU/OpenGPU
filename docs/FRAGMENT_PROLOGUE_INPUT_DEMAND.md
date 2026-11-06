# Fragment coordinate loads in the production compiler

Gallium's fragment-menu fallback calls `opengpu_fs_emit`, then converts its
machine words through the runtime shader ABI. The emitter now omits unused
FragCoord X/Y loads from that path. It scans the surviving IR after dead
temporary elimination, including dot components, both conditional arms and
discard predicates. Coordinate declarations alone do not require a load.

Register allocation, lane addressing, output addressing, shader capacity and
the 256-word source limit remain unchanged. Output and discard addresses use
the scalar lane base, not coordinate-vector contents. System-value source
files remain unsupported. Nonzero input indices conservatively retain both
original loads, preserving the existing generic-input/texture-origin path.
Position Z/W admission and the existing Y conversion rules are unchanged.

Normal production emitters before and after the change produced these words:

| Existing shader | Source words | Runtime ABI words | Vector loads removed |
| --- | ---: | ---: | ---: |
| Constant product | 56 → 51 | 62 → 57 | 2 |
| Constant minimum | 52 → 47 | 58 → 53 | 2 |
| Constant square root | 48 → 43 | 54 → 49 | 2 |
| Constant clamp | 60 → 55 | 66 → 61 | 2 |
| FragCoord Y | 50 → 47 | 56 → 54 | 1 |
| Uniform conditional | 59 → 54 | 65 → 60 | 2 |
| Y-based discard | 59 → 56 | 66 → 64 | 1 |
| Texture fallback | 23 → 23 | 25 → 25 | 0 |
| GTK coverage corpus | 141 → 141 | 147 → 147 | 0 |

For these complete source programs, only the unused coordinate instruction
groups were removed; all other source words and immediate-patch metadata
matched the original emitter. The checked-in shader code assets contain the
normal emitter's new output. The runtime ABI may need a different first
address calculation after a load is removed, so its word reduction can differ
from the source reduction.

`fs_prologue_lower` checks the real TGSI/menu/emitter/runtime-ABI path and
executes its final words with an independent CPU interpreter. Seven cases
cover no coordinates, X, Y, XY, a temporary dependency, dead input elimination
and a two-component dot. Three resource profiles include 16 lanes × 8 warps
and all 128 items: 84 warp executions check exact packed output and every
other backing-memory byte. Z/W remain rejected. Conditional-arm, discard and
texture cases also retain their required loads and pass compiler/ABI checks.

Validation passed with AddressSanitizer for `fs_prologue_lower`,
`fs_alu_lower` and `fs_dot_lower`, plus the complete kernel-free driver entry:

```sh
python3 scripts/test_driver.py
```

The TGSI tests require the existing Mesa and DRM headers; use
`OPENGPU_MESA_DIR` and `OPENGPU_DRM_INCLUDE` for external checkouts. The same
entry accepts `CC='clang -fsanitize=address -fno-omit-frame-pointer'` for an
AddressSanitizer build.

This removes at most five source instructions and two vector loads per warp;
programs needing both coordinates save none. It does not change the fixed
42/67-word product-qualification shaders. No GPU execution, hardware-cycle,
720p performance, physical-clock or FPS improvement is claimed.
