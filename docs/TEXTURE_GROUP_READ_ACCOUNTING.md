# Physical reads per texture group

The development candidate `13d6e5a` preserves actual physical data-line reads
through shared fills, raw RGBA8 taps, translation and native FP32 filtering.
Its [qualification record](performance/mobile-native-texture-read-accounting-2026-10-21.json)
contains 77 passing checks across ten suites and nine successful RTL emissions
on 828 frozen inputs. This component is not yet integrated with shared native
shader sampling. PPA evaluation remains synthesis only.

Construct the service with `readCountOutput = true` to expose `dataReadLines`.
Each accepted physical memory read is charged once to the initiating fill
waiter. Joined waiters and cache hits report zero; repeated caller tags do not
identify fill ownership. Duplicate tap aliases cannot multiply the charge.
An unoffered cancelled fill reports zero. An irrevocable offered read that is
later accepted remains charged even when its reply faults or its group is
cancelled. Page-table reads are excluded.

All thirteen measured native-service cases assert at complete drain that the
sum of reported reads equals the number of accepted physical data requests.
They cover precision, repeated caller tags, permissions, uncached remapping,
physical/PTE faults, cancellation, owner saturation and output backpressure.
Thirteen parallel cases verify the unmeasured interface.

The initial 75-check run retained one failure: a delayed cache miss could reach
fill admission after that line's refill completed, causing a redundant read.
Forwarding owners now accept a late join to a successful installed fill while
its slot remains leased. They retain exact forwarded data even if another
refill evicts the cache entry. Completed failed or uncached fills still require
a new physical transaction. Owners that return only an installation
acknowledgement retain their previous completion behavior.

The optional counters leave disabled interfaces unchanged. The late-join fix
changes `BankedTextureFillOwner.sv` in six existing read-through profiles, so
their previous synthesis timing does not qualify this revision. The standalone
FP32 filter's sixteen active SV files remain byte exact; the seven-file
resident cluster remains equivalent after removing comments and empty lines.
Synthesis of the modified services, native routing and render-to-texture
visibility remain open.
