# Native host resource publication

`QualifyNativeHostResourcePublication` connects the full resource workload from
[the system qualifier](NATIVE_SYSTEM_RESOURCE_PUBLICATION.md) to the actual
`GpuHostSystemAxi` MMIO staging, SUBMIT, interrupt and software-completion POP
path. It retains two default CUs, sixteen lanes/eight warps per CU, 128 items
per group, real SRAM/FPU and default instruction/vector/shared-L2 caches. The
graphics frontend stays idle; no frame is rendered.

Each healthy group uses twelve simultaneous 16 KiB uniform blocks and all 1024
default components, sixteen texture/sampler views, four image units, four
sparse 128 MiB storage views, eight counters and all 16 KiB shared storage.
Both groups complete resource and counter work before warming cached aliases.
Actual readiness/data/fence/flag observations then publish producer writes to
the other CU through image, storage, texture and ordinary cached aliases.
The second healthy parent reverses group roles after a bounds-fault parent.

All five resource descriptions enter through real MMIO and are poisoned after
SUBMIT. Instruction and vector SATP select the same fresh page table. Code is
readable/executable and not writable; result pages are uncached. VM mutations
must fail while the command is queued/running and while software retains its
completion. The fixture holds AXI MMIO replies, checks a stable completion and
IRQ, then POPs and drains before publishing the next task and VM root. Drain
also requires no valid or held, unaccepted lower AR/AW/W offer.

An optional program mode writes actual per-item results to a sparse, guarded
128 KiB result region. The external byte scoreboard checks all 20,384 words,
including independent uniform endpoints, every default component, FPU/shared
results, resource values, contended counter return-value sets, actual local/group
markers and observed readiness/publication tokens. It checks final backing and
read-only bytes separately. Results are not accepted through a checksum alone.
The original program mode remains the default; four pre-extraction program
and complete initial-memory baselines must remain identical.

The lower AXI port uses the product's 256-bit data width. Every 64-byte line
requires two W or R beats; narrow page-table reads retain their actual single
beat. Address/data backpressure, inter-beat gaps and tagged replies exercise
the real bridge. Only accepted B replies update the lower byte oracle. Each
healthy parent requires exactly 772 original publication/data acknowledgements
plus 1274 result-line acknowledgements: 2046 AW/B handshakes and 4092 W beats.
The bounds parent must issue no write. No delay substitutes for synchronization.

Post-warm result planes and executable code avoid the watched private-cache
and shared-L2 sets. The common resource phase and both groups' warm records
must complete before the first alias write, with exactly one lower warm read.
Every subsequent accepted lower address must belong to the bounded instruction,
flag, result, page-table, checked-descriptor or intentional resource-alias
classes. Cacheability follows the actual PTE/service and nonallocating RTL
paths; translated policy is not observable on this external AXI port.
Checked image/storage/texture clients use their actual uncached service paths;
this does not establish general sampler-cache coherence.

The native qualification passes direct, bounds-fault and indirect parents in
one simulation without reset. Both healthy parents check all 20,384 result
words and exactly 2046 acknowledged two-beat writes, observe both CUs active
concurrently and retain the single cached warm read. Actual AR/AW/W offers
remain stable under observed backpressure. The bounds parent writes nothing;
the subsequent healthy parent uses fresh protected data/code VM roots and
reversed group roles. Held MMIO completion, IRQ, rejected VM mutations, POP
and drain all pass. No production RTL or capacity change is needed.

Run pure program, publication-baseline, PTE and result-layout checks without
hardware elaboration:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyNativeSystemResourcePublication.scala", "QualifyNativeHostResourcePublication.scala").contains(f.getName))' \
  'Test/runMain opengpu.system.QualifyNativeHostResourcePublication --check-program'
```

Run the explicit native qualification outside routine PR/nightly lanes:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.nativeHostResourcePublicationQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyNativeSystemResourcePublication.scala", "QualifyNativeHostResourcePublication.scala").contains(f.getName))' \
  'Test/runMain opengpu.system.QualifyNativeHostResourcePublication --reports .artifacts/native-host-resource-publication'
```

The fixture is `@DoNotDiscover` and property gated. It adds no routine CI case,
lane or rendering work. This is trusted raw-MMIO qualification; its program
does not pass through the Linux shader validator or an API compiler. The
recording program has 944 instructions (3776 bytes) and polling backedges;
the driver's existing 512-instruction and forward-control-flow limits remain
unchanged. Driver admission/protection/compiler contracts, Linux reservation
fences, precise
per-warp trap reporting, graphics/DMA interaction, four-CU product coexistence,
API conformance, physical timing and full H7 remain separate gates.
