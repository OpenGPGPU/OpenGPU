# Independent draw-buffer blending

The typed renderer accepts independent enable, RGB/alpha factors, RGB/alpha
equations and component write masks for four draw buffers. Blend color remains
shared. This implements the ordinary indexed controls described in
[GLES 3.2 section 15.1.5](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf#page=402);
advanced blend equations remain separate work.

Draw/state payload version 2 requires blend version 2. Its four control words
occupy GFS words 8–11 or GTD words 604–607. Container headers retain version 1,
record sizes remain 512/2560 bytes, and every legacy field keeps its offset.
Version-one payloads require zero control words; ambiguous version combinations
and nonzero reserved bits are rejected before shader/color side effects.

| Control bits | Meaning |
| --- | --- |
| 0 | Blend enable |
| 4:1, 8:5 | Source/destination RGB factor, 0–14 |
| 12:9, 16:13 | Source/destination alpha factor, 0–14 |
| 19:17, 22:20 | RGB/alpha equation, 0–4 |
| 26:23 | RGBA write mask |
| 31:27 | Reserved, zero |

The effective mask intersects the control mask with the attachment view mask.
Integer attachments bypass blending while retaining that mask. The serial and
streaming MRT stores select each location's immutable state before arithmetic;
the native single-target packet path selects buffer zero before expansion.

Native profiles expose read-only GDB1 discovery at `0x400`: eight words report
magic `0x47444201`, word count, draw-version mask `6`, four buffers, factor mask
`0x7fff`, ordinary-equation mask `0x1f`, feature mask `0x7f`, and reserved zero.
Feature bits describe independent enable/factors/equations/masks, shared blend
color, integer bypass and linear-space sRGB blending, respectively. Legacy
profiles keep the range unmapped. Generated headers publish fields and masks.

65 distinct RTL checks pass across resident images, packet expansion, both
native descriptor readers, staged image admission and AXI discovery, with no
failed, canceled, ignored or pending cases. Store checks cover all 36 supported
formats, including packed/normalized/integer/sRGB targets, 1x/4x sampling,
changing masks and state, serial/four-port/eight-port backends, held snapshots,
invalid-state rejection and healthy reuse.

The parallel stores prepare six 64-bit clear-word patterns per color before
admitting tile traffic. Registered selection preserves repeating three- and
six-byte pixel phases and sixteen-byte pixel parity, while removing format
decoding from each active word port. Preparation adds 1–3 clocks per clear-start
color and 1,536 register bits before optimization; SRAM capacity is unchanged.
Both port profiles check 6,656 exact clear words, 227 masked updates and
cancellation at 51 preparation offsets, followed by healthy reuse.

The explicit local native-host qualifier also passes four 4x4 frames: four
draw buffers and the single-target path, each at 1x and 4x MSAA, with two shader
draws per frame. It checks every stored color/depth byte and guard byte,
read-only discovery, allocated AXI backing, delayed write acknowledgements and
complete traffic drain. This uses two four-lane/two-warp CUs; it does not
qualify concurrent publication, Sv32, full product capacity, clock or FPS.
Full control, state-reader and staged draw-reader synthesis passes 1000 ps at
TC/SLVT with 25% input/output budgets. Core/I/O margins are +263.772/+50.500 ps,
+256.105/+418.985 ps and +141.638/+355.857 ps, respectively. The updated complete
pixel backend still requires that gate. API integration remains required;
these results do not close E32-04c, H8, whole-host timing or product FPS.

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ParallelTypedTileColorStoreSpec opengpu.graphics.EightPortTypedTileColorStoreSpec opengpu.graphics.IndexedDrawBufferBlendSpec opengpu.graphics.TypedFragmentStateReaderSpec opengpu.graphics.NativeTypedGraphicsJobReaderSpec opengpu.system.NativeDrawBufferBlendCapabilitiesAxiSpec opengpu.graphics.StagedTypedImageAdmissionSpec'
bash scripts/test_scripts.sh
```

Run the native host model explicitly outside ordinary PR/nightly CI:

```sh
sbt -J-Xmx4G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'Test / runMain opengpu.graphics.QualifyIndexedDrawBufferBlend --reports .artifacts/qualification-reports'
```

Raw logs, source proofs and qualification reports remain local under `.artifacts/`.
