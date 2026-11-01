# Tessellation native job reader

`TessellationNativeJobReader` snapshots a draft GNQ1 job and a GNF1 frame via
four independently owned descriptor tags, then offers a bounded sequence of GNT1
views to `TessellationNativeSequenceImage`. GNQ1 has the same fixed word layout
and capacity header as GNJ1, but uses distinct magic `0x474e5101`; the draw
array contains GNT1 records rather than GND1 vertex draws. The job's frame and
draw ranges are exclusive-ended, and nonempty draw arrays require aligned
bounds and strides of at least 2048 bytes.

The owner rejects identity mismatches and writable virtual frame spans that
overlap job, frame, draw or active image storage before offering the frame. It
also prevents writable image spans from overlapping descriptors or fragment
state. Offers, outstanding reads, child results and frame completion remain
owned across cancellation and faults. The caller must pin address translation
and enforce physical allocation provenance: virtual overlap checks cannot
prove that two different mappings do not alias. Draw input and shader resource
aliasing, an attached translated job-memory fabric, host command submission,
other tessellation domains and product qualification remain open.

Two directed simulation tests pass: reordered descriptor replies and bounded
multi-draw views; malformed headers, bounds and virtual aliases; held offers,
cancellation, descriptor and child faults, terminal-frame flushing and reuse.
The changed-test selection checks also pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeJobReaderSpec'
python3 scripts/test_changed_test_suites.py
```
