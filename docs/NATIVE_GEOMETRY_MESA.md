# Original Mesa scalar GS compiler

This private compiler consumes original Mesa 22.3.6 GS TGSI from one linked
ESSL 3.20 VS/TCS/TES/GS/FS program. It emits the existing scalar geometry
program dialect 1. It does not expose a screen capability, getter, submit
route or new GNG version. The four-stage opaque joint API remains unchanged.

## Compiler contract

`opengpu_native_geometry_compile` accepts a bounded original token packet and
owned copies of the original GS input/output NIR variable spans. These spans
are captured before `nir_to_tgsi` loses array identity. They preserve semantic,
slot, component and array boundaries; adjacent declarations do not become one
array. The compiler validates complete tokens before Mesa's optional-token
parser, canonical span tails, declaration/span agreement and component use.
Failure leaves the output object unchanged.

The result contains at most 512 original instruction words, exact constant
view requirements, and layout/link metadata for the existing GNG1 contract:

- Input topologies have 1, 2, 3, 4 or 6 vertices; GS invocations separately
  range from 1 to 32. Input/output components remain bounded by 64.
- Output vertices remain bounded by 256. Counted output components, including
  applicable layer/PrimitiveID outputs, times output vertices must fit 1024.
- Position and present generic components are packed independently at the
  input and output boundary. The result retains GNG input-source and
  output-source mappings; it does not reuse the old TES-to-FS map.
- Twelve logical UBO blocks and 1024 default components remain stage-local.
  Scalar checked uniform loads use block 0..11 or default block 31, with
  16-KiB/4096-byte limits. Requirements do not construct or authorize GUR2.

GS input-array dimensions are carried by source operands. Mesa's
`DCL IN[][slot]` has no `Declaration.Dimension` constant-buffer bit. Dynamic
vertex operands retain all 32 bits: an unsigned bound check precedes adding
a static vertex offset. Invalid operands become `UINT32_MAX`, not a masked or
wrapped valid vertex. The separate generic-indirect path uses original
variable spans and exact component strides. The original-ESSL qualification below covers dynamic vertex indexing and
a separately indexed matrix-column register using the original NIR span.

The backend emits scalar ALU and checked GS operations, never ordinary
memory, texture/image instructions or VS/FS SoA accesses. The original
`gl_InvocationID` becomes function 37 selector 0; `gl_PrimitiveIDIn` becomes
selector 1 and is not an input-vertex component. Layer and shader PrimitiveID
outputs use functions 34 and 38 with the corresponding declared GNG builtin
mask. Input/output/EmitVertex/EndPrimitive use functions 32/33/35/36.

Runtime conditionals use forward scalar branches. TEMP, output-shadow and
ADDR definedness are intersected at joins; the else arm starts with the
incoming facts, not definitions from then. Only original output reads need RF
shadows, and EmitVertex clears their definedness to match output staging and
the source language. Constant induction and exit conditions may be compiled
by bounded loop expansion; they do not authorize arbitrary runtime backedges.
The 512-word bound remains strict.

## Final CPU validation

The final integrated Linux/GCC 13.3/ASan+UBSan run compiled 30 translation
units fresh, with no object reuse, inside the 512-MiB combined-memory,
one-CPU, no-network container under a 180-second process-group deadline.
All 30 MMD files (250 distinct dependencies) were checked against the
original/source or private generated-input closure. The before/after guard
covered 1311 GPU/reference and 8101 external inputs. All five original GS
cases and the old v1/v2 byte regressions passed.

| Original linked ESSL GS case | Words | Coverage |
|---|---:|---|
| `graphics60.geom` | 451 | Original loop, dynamic induction vertex, 12 UBO last words, two emits/end |
| `geometry-runtime.geom` | 224 | Full-width runtime vertex, 12 UBOs and all 1024 defaults |
| `geometry-invocation.geom` | 193 | 32 declared invocations, PrimitiveID input/output and layer |
| `geometry-conditional.geom` | 255 | Runtime arms, output reads after the join, checked uniforms |
| `geometry-matrix.geom` with its original TES | 269 | Independent runtime vertex and mat2x3-column ADDR values, sparse location13 |

The same build ran the three old v1 and five old v2 ESSL cases, comparing
native bytes with the independent original emitter. Those bytes were
unchanged. The four final GS blobs also passed the production scalar GS
validator, private RX/no-W copying, nonzero-entry preservation, actual MMU
publication and whole-owner retirement using the repaired callback-capture
helper. Missing geometry/uniform permission remains rejected. These are
explicit synthetic CPU profiles, not producer-authorized device profiles.
The callback isolation controls and their limits are described in
[Geometry](GEOMETRY.md).

The integrated run compiled all five original programs in the table and
retained their listed lengths. The matrix program carries actual Mesa
`IN[ADDR[1].x][ADDR[0].x+1]` with independent runtime vertex and matrix-column
indices and original NIR mask7/two-column span. Its 269 original words passed
the independent production GS validator/private-RX/MMU fixture; this checks
code protection, not matrix values or shader execution. A separate original
five-stage source/lease test is documented in
[Original linked five-stage source](NATIVE_FIVE_STAGE_SOURCE.md).

Supplemental literal-token tests use Mesa's actual token builder/parser. They
cover all five input-vertex counts, invocations 1/32, coupled output limits,
full-width vertex encodings, malformed/truncated packets, canonical spans,
wrong processor/version and unchanged output on rejection. The runtime-ADDR
case rejects a sibling-only definition and accepts redefinition in both
arms followed by a joined input read. Source/encoding negative controls
remove the sibling-ADDR restoration, change the full-width bound branch,
and permit nonzero unused spans. The ADDR and unused-span controls require
their own named unexpected-accept diagnostic; the branch control requires
the `guards == 4` assertion. Clang and GCC 13.3 ASan+UBSan passed the positive
fixture and all three precise controls; the final GCC group took 10.303
seconds under the same container limits and input guards. Compilation errors
or a group timeout do not count as a successful negative control.
These are parser/encoding controls, not an interpreter or shader result oracle.

## Remaining qualification

The compiler is a bounded initial scalar GS backend, not complete GLSL GS
lowering. Unsupported operations, register pressure and programs exceeding
512 words fail closed. A general data-dependent loop, and original ESSL
maximum-output cases such as 256×4 or 16×64 components, are not qualified by
constant expansion or the existing literal 19-word output-loop test. They
need a semantics-preserving lowering to the existing proven pure-output loop
where applicable, or an explicitly versioned stronger loop contract.

Remaining arithmetic/builtins, point-size transport, TES-to-GS production
ownership, GS-capable joint BUILD routing, ordered GS execution/TF and actual
CU/frame execution remain pending. The initial GS-linked FS and five-code/
65-uniform-reference lease now have separate compiler/CPU checks, not device
qualification. The
existing GS/TCS/TES/fragment capacities and the full five-stage target remain
unchanged. No CPU test here demonstrates shader values, full GUR data or the
2-CU 640×480/60-FPS product target. No Scala regression or model was run.

## Reproduction

Use the original read-only Mesa/SDK libraries; do not rebuild Mesa. In the
existing immutable `cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72`
image, use `--memory=512m --memory-swap=512m --cpus=1 --network=none --read-only`,
read-only GPU/Mesa/SDK mounts, and one private writable output directory:

```sh
GPU=/Users/duckdonald/workspace/gpu
export OPENGPU_MESA_DIR="$GPU/depends/mesa"
export OPENGPU_MESA_BUILD="$GPU/depends/mesa-build"
export OPENGPU_DRM_INCLUDE="$GPU/depends/aarch64-root/include"
export LD_LIBRARY_PATH="$GPU/depends/aarch64-root/lib"
export OPENGPU_MESA_LINK_LIBS="-l:libz.so.1 $GPU/depends/aarch64-root/lib/libexpat.so.1.6.7"
git show 71a798ccdca6e1bf2c81bf827a39dc316c5fa63a:userspace/gallium/opengpu_native_tess_uniform_emit.c > "$OUT/reference-tess.c"
python3 -B scripts/test_tessellation_uniform_frontend.py --sanitize --scalar-geometry \
  --regressions --baseline-source "$OUT/reference-tess.c" --build "$OUT/build"
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_geometry_emit.py \
  --negative-controls --temporary-root "$OUT/parser"
```

`OUT` must be a private directory under this checkout's `.artifacts`. Bind all
original/generated compiler inputs and actual MMD dependencies before/after
use, enforce the group deadline, and prove all descendants/container exited
before deleting ended outputs. For the explicit offline CPU protection chain:

```sh
for name in geometry geometry-runtime geometry-invocation geometry-conditional geometry-matrix; do
  python3 -B scripts/test_geometry_program_snapshot.py --sanitize \
    --program "$OUT/build/programs/$name/native-gs.words" --owned-uniforms
done
```

The old no-argument snapshot fixture and callback-case CLI remain available.
The offline program CLI accepts only complete 1..512-word little-endian files;
it is not a live executor or a GNG/permission import interface.
