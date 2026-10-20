# Converted native inputs and image ownership qualification

The [complete 56-check regression](performance/mobile-native-input-combined-2026-10-20.json)
passes on 626 unchanged source/ABI files. It combines version-2 vertex conversion,
GVI1 discovery/burst behavior, GNC1 physical and GNC2 translated image views,
GNX1 primary fault reporting, typed replay, two-CU execution and native AXI
reset/VM ownership. Four native local/global profiles use half and packed-SNORM
vertex data with exact independently checked images. All 22 fault cases run
without filtering and render exact healthy frames after failure.

The original GNC2 run passed 11/12 and attributed an image read-permission fault
to internally cancelled drawing (status 4). The corrected replay owner retains
the primary transfer fault; the complete run now returns memory status 3 for
that case, with no denied physical image reads and exact reuse.

Default host emission contains four CUs, one shared payload pool and two replay
image engines. The audit finds the same 1,240 256x64 and 146 256x32 SRAM macros
(2,689,024 physical data bytes), a 256-bit AXI data port and 10-bit read IDs.
The tests use small two-CU runtime configurations; default hierarchy emission
does not establish four-CU Mobile frame rates or complete resource capacity.

This snapshot precedes the later coordinate/preparation, AXI response-owner and
centroid changes. Their composed qualification remains separate. Physical
allocation provenance/PA aliases, complete resource/format coverage, workloads,
timing and H0–H8 remain open.
