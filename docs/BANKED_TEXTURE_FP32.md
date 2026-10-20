# Banked RGBA8 normalized FP32 output

The [component qualification](performance/mobile-banked-texture-fp32-2026-10-21.json)
passes nine tests on817 frozen inputs and an exhaustive RTL stream of66,846,724
scalar conversions. Native shader integration and complete-service timing
closure remain open. The standalone32-bit-tag normalizer synthesis is running
at1000ps;that screen does not qualify the entire sampler.

`BankedRgba8FloatTextureService` completes physical RGBA8 tap groups through the
banked cache and joined fills,then returns four binary32 channel values. The
filter preserves the full24-bit sum of each byte times its Q16 weight. Weights
sum to65,536. `Rgba8WeightedToFp32` rounds the exact rational value
`numerator / (255 * 65536)` to nearest-even once,with explicit positive zero.
For example,the first cold test returns51.5/255 in red,which differs from
truncating the filtered byte to51 before conversion.

Five elastic stages normalize the integer,fold the significand bytes for exact
division by255,correct the quotient/remainder and round the final significand.
The odd divisor has no halfway remainder. Tags and work/fault metadata follow
the result. Cancellation poisons unoffered internal rows,including blocked
stages;an already offered public result stays immutable. Busy remains asserted
through final FP32 retirement so the parent can retain resource and VM leases.

An independent rational integer oracle checks all16,711,681 admitted numerators
in each of four channels through emitted Verilator RTL with assertions enabled.
Rotated streams exercise each channel's entire domain. Output stalls cover
4,610,120 held cycles and retain values,tags and order. Component tests add
random/boundary vectors,all cancellation stage cuts,a full blocked pipeline,
16 cold fills,96 complete hot groups at input/output II1,192 backpressured
groups,conflict forwarding,fault fanout,publication and healthy reuse.

Run the component tests with:

```sh
sbt 'testOnly opengpu.graphics.Rgba8WeightedToFp32Spec opengpu.graphics.BankedRgba8FloatTextureServiceSpec'
```

To reproduce the exhaustive RTL stream:

```sh
repo=$(pwd)
sbt 'runMain opengpu.elaboration.EmitBankedTextureHits normalize generated/rgba8-fp32-rtl'
(
  cd generated/rgba8-fp32-rtl
  verilator --cc --exe --build --assert --top-module Rgba8WeightedToFp32 \
    --prefix VRgba8WeightedToFp32 --Mdir obj -j 4 -Wno-fatal \
    -CFLAGS '-O2 -std=c++17' -f filelist.f "$repo/src/test/cpp/rgba8_weighted_fp32.cpp"
  ./obj/VRgba8WeightedToFp32
)
```

This contract starts at admitted physical taps and Q16 weights. Coordinate,
LOD,weight-generation and general-format precision require native integration
and their own evidence. The packed-byte resident default keeps its existing
interface. No whole-native shader rate,power,route or GPU clock is claimed.
