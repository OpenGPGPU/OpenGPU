# Task-owned native resource availability

Legacy sampling and descriptor/captured-operand operations share a CU texture
handoff but require different services. A legacy `TexSampleUnit` must not
interpret native resource operands as ordinary sampling coordinates.

`SharedCuGraphicsPort.nativeResources` identifies the service for a prospective
launch. `KernelShaderStage` defaults to a legacy service; native vertex and
fragment stages explicitly select their instantiated native service. Private
adapters configure the distinction statically. Generic decoder/CU constructors
retain explicit static configuration for external-service harnesses.

Each task-router slot captures the provider bit on client acceptance. The
queued launch's rewritten task ID selects it through arbitration, backpressure,
reordered completion and slot reuse. The CU captures sampler and native
permissions on its actual `kernel.fire` edge. Later live pins cannot grant or
revoke service for that task. Compute admission gets neither graphics
permission, including after a graphics owner. Host command/native descriptor
layouts and kernel payloads are unchanged.

| Accepted service | Legacy sampling | Native packet/parameter/sample/fetch/size |
| --- | --- | --- |
| No sampler | Illegal instruction | Illegal instruction |
| Legacy sampler | Serviced | Illegal instruction |
| Native resource service | Serviced | Serviced |

Static availability and task permission must both allow native operations.
Unavailable operations retain the original instruction, PC, warp and lane mask
for a precise illegal-instruction trap. Held decode packets remain stable when
live permissions change. Ordinary scalar/vector/FP, barriers and quad
derivatives retain their existing routes. Physical service permission does not
validate resource descriptors; native units still check binding/range/lifetime
contracts. Unimplemented instruction encodings remain illegal.

Twenty-three directed checks pass: six decode profiles, seven router cases,
four actual-CU permission/reuse configurations, two private-adapter rejection
cases, and four native vertex/fragment local/global cases. CU tests alternate
eight task policies at eight-thread and default 128-thread capacity, including
late replies, held traps/completions and exact healthy writeback after rejection.
The focused native profile submits sixteen draws per case across families 2–6
with scalar/vector operands, parameter reset, immutable roots, faults,
cancellation and reuse. The complete 64-format vertex/local matrix also passes.
The focused profile makes no all-format claim for the other stage/payload
combinations; full matrices remain the default broad regression. Host script
checks pass.

Complete component synthesis passes 1000 ps TC/SLVT with 25% I/O, standard ABC,
no retiming/false paths, zero skipped operators and no SRAM:

| Profile | Core / I/O slack (ps) | Cells | Synthesis library area |
| --- | ---: | ---: | ---: |
| DecodePipe, task permissions, default lanes/warps | +446.834 / +467.987 | 1,266 | 176.710 |
| DecodePipe, static legacy provider | +435.076 / +467.983 | 1,288 | 181.317 |
| SharedShaderTaskRouter, four CUs, three clients, five slots/client | +405.321 / +276.105 | 9,565 | 1,527.94 |

```sh
sbt 'testOnly opengpu.core.frontend.decode.TextureExecutionAvailabilityDecodeSpec opengpu.graphics.SharedShaderTaskRouterSpec' \
  'testOnly opengpu.core.GpuComputeUnitTexturePolicySpec' \
  'testOnly opengpu.graphics.KernelShaderAvailabilitySpec -- -z "legacy-no-native"'
sbt -Dopengpu.nativeResourceAvailabilityOnly=true \
  'testOnly opengpu.graphics.VertexShaderTextureMatrixSpec -- -z "execute native typed component"' \
  'testOnly opengpu.graphics.FragmentShaderTextureMatrixSpec -- -z "execute native typed component"'
```

Emit `mobile-resource-decode`, `mobile-resource-legacy-decode` and
`mobile-native-resource-router`, then qualify `DecodePipe` or
`SharedShaderTaskRouter` with `scripts/qualify_ppa.py --stage synthesis
--period-ps 1000 --io-delay 0.25`. These gates do not qualify an enclosing
CU/host clock. Uniform instructions, native uniform bindings/discovery, full
resource/coherence/rate qualification and H0–H8 remain open.
