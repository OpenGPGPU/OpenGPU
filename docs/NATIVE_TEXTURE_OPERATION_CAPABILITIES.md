# Native resource texture operation discovery

GTO1 is a read-only record of16 words capability record at MMIO `0x240..0x27f`. It describes
existing native resource-backed instructions and their operand limits. GTX1
continues to describe fixed-coordinate policy and typed packet fetch;GTF1
continues to describe storage formats. The new record does not advertise the
banked sampling rate or complete GLES/API/compiler support.

A native host exposes GTX1 feature bit10 (`TEXTURE_CAP_OPERATION_DISCOVERY`)
when GTO1 is present. Software checks that bit before reading `0x240`,then checks
GTO1 magic,length and version. Older native hosts leave the bit clear. Legacy
and fixed binned profiles leave the optional native windows unmapped and return
AXI SLVERR. Every GTO1 write,including zero-strobe writes,returns SLVERR and
leaves all records unchanged. Unaligned accesses,address aliases and reads past
`0x27f` are invalid. AXI FIXED/INCR bursts and held responses follow the host's
existing transfer rules.

| Word | Value | Meaning |
|---|---|---|
| 0 | `0x47544f01` | GTO1 magic |
| 1 | `16` | Record words |
| 2 | `1` | Record version |
| 3 | `0x7c` | Scalar function mask:bits2..6 |
| 4 | `0x7c` | Vector function mask:bits2..6 |
| 5 | `0xff` | Parameter-pair mask:all eight pairs |
| 6 | `0xf` | Packet fetch,sampling and captured fetch components0..3 |
| 7 | `0x7` | Size query components0..2 |
| 8 | `0x7` | Automatic,explicit and gradient LOD modes |
| 9 | `0x3` | Vertex and fragment resource stages |
| 10 | `32` | Binding limit per stage |
| 11 | `16` | Captured32-bit operand words per lane/warp tuple |
| 12 | `0xf` | 2D,2D array,cube and3D target mask |
| 13 | `64` | Internal typed storage-format count;see GTF1 |
| 14 | `0x3f` | Semantic feature bits below |
| 15 | `0x0007fff8` | Signed16-bit minimum offset-8 in low half,maximum+7 in high half |

Function2 is the existing GPU-packet component fetch. Function3 writes one
pair of captured operands and requires destination register zero;its `funct3`
field selects a pair0..7. Function4 samples with captured floating operands.
Function5 fetches with captured integer operands. Function6 returns ordinary
integer dimensions from image metadata. Scalar encodings use custom0/funct7;
vector encodings use custom1/funct6 and retain the vector mask bit. Sample/fetch
component masks do not apply to the parameter-pair selector.

Word14 bits are:0 captured-fetch LOD relative to the selected image base;
1 size-query LOD relative to that base;2 fragment helper-quad LOD derivation;
3 trusted task-scoped captured operand banks;4 fault-atomic vector retirement;
5 metadata-only size queries without sampler/texel reads. Offset limits apply
to sampling and captured integer fetch,not packet addresses or size operands.

Target/format masks do not advertise every combination. Existing format,target,
filter,shadow,resource and offset validation still applies. Projective sampling
uses existing shader division before operand capture;GTO1 adds no projective
opcode and does not imply that an optional scalar FPU is enabled. Shader lane
counts,warp counts and product capacities remain in their existing records.

The three generated native typed,binned and fragment-state C headers publish
identical field/opcode/feature definitions under their established prefixes.
No descriptor layout or opcode encoding changes. Functional MMIO,decoder,ABI
and host-control synthesis qualification are required before main integration.

The [817-input qualification](performance/mobile-native-texture-operations-2026-10-21.json)
passes14 tests,nine ABI checks,two strict C compile/run checks and three RTL
emissions. Four native/fixed/legacy MMIO profiles verify the table independently
of the hardware encoder;4,096 decoder cases verify function and selector limits.
Fresh vertex/fragment texture endpoint RTL is byte-identical across all77 active
files per profile to the existing positive synthesis inputs. The changed native
host interface reached its 7200-second synthesis timeout during mapping, with
unchanged source and RTL. No mapped timing or area result is established. The
leftover owned container was stopped after the flow terminated. Main integration
continues to require completed host synthesis;the full hardware goal remains open.
