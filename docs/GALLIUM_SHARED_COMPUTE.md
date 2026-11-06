# Bounded Gallium shared uint32 frontend

Compute state creation now queries the device's shared base, byte capacity and
lane count. A nonzero `req_local_mem` must fit that discovered capacity. Missing
or inconsistent discovery fails closed; zero is a valid shared base. The screen
reports the queried shared size without enabling a GL/GLES compute API.

The library queries `COMPUTE_SHARED_BASE/BYTES/LANES` independently and publishes
the tuple only after all three queries and interval checks succeed. An unknown
device returns no inferred capacity; an older driver error is preserved.

The explicit shared emitter accepts canonical uint32 MEMORY stripes over a full
workgroup, straight-line converged barriers, and the checked adjacent-warp
`localID ^ 16` read permutation. Mesa's actual shared allocation bounds every
access. The emitter preserves the shared declaration's typed MEMORY binding,
proves an initialized prefix before a barrier makes it readable, and emits
unmasked e32 unit-stride operations using the trusted local-linear warp base.
The shared lane count must be a power of two representable by `vsetivli`
(at most 31); the validated device has 16 lanes. Legacy nonshared compilation
keeps its entry point and program layout, including the 16-vec4 immediate limit.
The shared parser permits 32 immediate vec4s; the native limit remains 512 words.

The CPU qualification passed with actual Mesa 22.3.6 ESSL310 → NIR → TGSI, the
production emitter, discovery callbacks and kernel word validator. Its full
128-thread program uses all 16 KiB (4096 uint32 words), exchanges values across
adjacent warps and executes two barrier generations. Both shader seed epochs
compile to 486 native words. An independent instruction decoder checks every
store and every read address/value, each complete 4096-index read multiset,
all eight warps in forward and reverse orders, two groups, exact outputs and
allocation guards. The original 51 callback submissions, comparison/predicate
corpus and failure/recovery checks also pass under ASan/UBSan. Missing discovery,
short allocation, uninitialized reads, omitted/divergent barriers, masked stores,
undeclared MEMORY bindings and unsupported lane configurations are rejected.

Reproduce manually with matching, already configured Mesa 22.3.6 objects and the
checked uniform-index lowering source:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_MESA_BUILD=/path/to/matching-mesa-build \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
python3 scripts/test_gallium_compute_shared.py \
  --build /new/private/shared-cpu-output --sanitize \
  --mesa-nir-lower-io /path/to/checked/nir_lower_io.c
```

The runner compiles private fixture/emitter objects and one private NIR-to-TGSI
object, preserving all other Gallium archive members and shared Mesa files. It
corrects the shared MEMORY use index to match Mesa's existing typed declaration.
It neither rebuilds Mesa nor starts a GPU/guest. This evidence covers the bounded
compiler/validator/CPU instruction semantics; RTL execution, faults, cancellation,
physical shared-memory ordering and hardware timing require separate native
qualification. `PIPE_CAP_COMPUTE` remains zero and API versions are unchanged.
