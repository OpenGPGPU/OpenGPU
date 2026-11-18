# Same-GEM image and storage atomics

The explicit P10 source matrix binds one original 8-KiB Gallium buffer as both
a coherent typed buffer image at API binding 3 and a coherent SSBO at binding
0. Binding 2 stores each invocation's two returned old words. Real Mesa
lowering, Gallium resource views and production Linux admission retain the
same GEM object and its shared reservation through owner cleanup.

The 66 ESSL sources cover R32UI/R32I exchange/add, xor/or, and/min, max/CAS and
CAS/CAS pairs, both image-first and storage-first order, and three original
view layouts. Six further combinations exchange finite R32F image values and
their raw uint SSBO encodings. Two workgroups of four invocations compete for
one word. Explicit buffer/image barriers retain each invocation's call order;
the independent oracle allows other legal interleavings.

| Layout | SSBO offset / bytes / index | Image offset / bytes / coordinate | Physical byte offset |
| --- | --- | --- | --- |
| Same | 0 / 64 / 0 | 0 / 64 / 0 | 0 |
| Shifted | 3840 / 512 / 67 | 4096 / 52 / 3 | 4108 |
| Endpoint | 7936 / 256 / 63 | 8140 / 52 / 12 | 8188 |

All storage starts preserve the production 256-byte VA and 64-byte DMA
alignment rules. Endpoint selects the final complete original GEM word.

The capture checker reconstructs both `GIU1` and `GSB1` tables, exact writable
view union, permissions and private translations from the original recipe.
Both virtual target addresses must resolve to that same original physical
word. Actual Mesa words must match the original code snapshot and every staged
private executable mapping. The result oracle requires a legal serialization
of all sixteen returned old values and the final word, then guards every
other physical byte. One guarded compiler build serves the matrix; each
independent case uses a fresh complete DUT/reset and real cleanup receipts.

All 66 sources pass real Mesa compilation and production admission with
ASan/UBSan and actual compiler/link dependency checks. Their original
GEM/code/view captures and ten rejection controls per source pass. The focused
CPU oracle/controller/executor checks also pass. These are CPU checks;
complete-host execution of this mixed matrix remains required. A successful
complete run must check 1,056 returned old words and final source/model inputs.

Run the focused controls from `scripts`:

```sh
python3 -B -m unittest test_compute_mixed_atomic_cases \
  test_compute_mixed_atomic_source test_qualify_compute_owner
```

The explicit execution entry requires an original, freshly qualified complete
float-owner compute executor and matching Mesa environment:

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case image-storage-atomics --timeout 7200
```

This buffer-alias matrix does not qualify other image alias APIs, cross-command
coherence, raw RV32 global AMOs, accepted-write/error cached aliases, a physical
clock or FPS. P10 and those independent acceptance gates remain open.
