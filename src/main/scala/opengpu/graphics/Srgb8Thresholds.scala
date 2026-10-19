package opengpu.graphics

import chisel3._
import chisel3.util._

/** Smallest positive FP32 input reaching each nearest8-bit sRGB code.
  * Generated with100-digit decimal inverse transfer; fixed constants make
  * elaboration independent of host floating-point/libm behavior.
  */
private[graphics] object Srgb8Thresholds {
  def values: Vec[UInt]=VecInit(Seq(
    "h391f22b4".U(32.W),"h39eeb40e".U(32.W),"h3a46eb61".U(32.W),"h3a8b3e5e".U(32.W),"h3ab3070b".U(32.W),"h3adacfb8".U(32.W),"h3b014c33".U(32.W),"h3b153089".U(32.W),
    "h3b2914df".U(32.W),"h3b3cf936".U(32.W),"h3b50f2d1".U(32.W),"h3b65fb9b".U(32.W),"h3b7c3403".U(32.W),"h3b89d060".U(32.W),"h3b962333".U(32.W),"h3ba314bd".U(32.W),
    "h3bb0a731".U(32.W),"h3bbedcb7".U(32.W),"h3bcdb76d".U(32.W),"h3bdd3967".U(32.W),"h3bed64af".U(32.W),"h3bfe3b46".U(32.W),"h3c07df91".U(32.W),"h3c10f91b".U(32.W),
    "h3c1a6b32".U(32.W),"h3c2436c8".U(32.W),"h3c2e5cc7".U(32.W),"h3c38de1a".U(32.W),"h3c43bba4".U(32.W),"h3c4ef648".U(32.W),"h3c5a8ee4".U(32.W),"h3c668654".U(32.W),
    "h3c72dd71".U(32.W),"h3c7f950f".U(32.W),"h3c865702".U(32.W),"h3c8d148f".U(32.W),"h3c940396".U(32.W),"h3c9b247c".U(32.W),"h3ca277a6".U(32.W),"h3ca9fd78".U(32.W),
    "h3cb1b653".U(32.W),"h3cb9a298".U(32.W),"h3cc1c2a9".U(32.W),"h3cca16e3".U(32.W),"h3cd29fa4".U(32.W),"h3cdb5d4b".U(32.W),"h3ce45032".U(32.W),"h3ced78b5".U(32.W),
    "h3cf6d72e".U(32.W),"h3d0035fc".U(32.W),"h3d051bb4".U(32.W),"h3d0a1cec".U(32.W),"h3d0f39d0".U(32.W),"h3d14728a".U(32.W),"h3d19c745".U(32.W),"h3d1f382c".U(32.W),
    "h3d24c567".U(32.W),"h3d2a6f22".U(32.W),"h3d303584".U(32.W),"h3d3618b7".U(32.W),"h3d3c18e4".U(32.W),"h3d423632".U(32.W),"h3d4870ca".U(32.W),"h3d4ec8d2".U(32.W),
    "h3d553e73".U(32.W),"h3d5bd1d3".U(32.W),"h3d628318".U(32.W),"h3d69526a".U(32.W),"h3d703fee".U(32.W),"h3d774bca".U(32.W),"h3d7e7624".U(32.W),"h3d82df90".U(32.W),
    "h3d869372".U(32.W),"h3d8a56cb".U(32.W),"h3d8e29ab".U(32.W),"h3d920c27".U(32.W),"h3d95fe4f".U(32.W),"h3d9a0035".U(32.W),"h3d9e11ec".U(32.W),"h3da23384".U(32.W),
    "h3da66510".U(32.W),"h3daaa6a0".U(32.W),"h3daef847".U(32.W),"h3db35a15".U(32.W),"h3db7cc1b".U(32.W),"h3dbc4e6b".U(32.W),"h3dc0e114".U(32.W),"h3dc58429".U(32.W),
    "h3dca37b9".U(32.W),"h3dcefbd6".U(32.W),"h3dd3d08f".U(32.W),"h3dd8b5f5".U(32.W),"h3dddac19".U(32.W),"h3de2b30a".U(32.W),"h3de7cad9".U(32.W),"h3decf395".U(32.W),
    "h3df22d50".U(32.W),"h3df77817".U(32.W),"h3dfcd3fc".U(32.W),"h3e012087".U(32.W),"h3e03dfae".U(32.W),"h3e06a77b".U(32.W),"h3e0977f6".U(32.W),"h3e0c5126".U(32.W),
    "h3e0f3314".U(32.W),"h3e121dc5".U(32.W),"h3e151143".U(32.W),"h3e180d95".U(32.W),"h3e1b12c2".U(32.W),"h3e1e20d1".U(32.W),"h3e2137cb".U(32.W),"h3e2457b6".U(32.W),
    "h3e278099".U(32.W),"h3e2ab27d".U(32.W),"h3e2ded68".U(32.W),"h3e313161".U(32.W),"h3e347e70".U(32.W),"h3e37d49c".U(32.W),"h3e3b33ec".U(32.W),"h3e3e9c67".U(32.W),
    "h3e420e15".U(32.W),"h3e4588fb".U(32.W),"h3e490d22".U(32.W),"h3e4c9a90".U(32.W),"h3e50314c".U(32.W),"h3e53d15d".U(32.W),"h3e577aca".U(32.W),"h3e5b2d9a".U(32.W),
    "h3e5ee9d4".U(32.W),"h3e62af7e".U(32.W),"h3e667e9f".U(32.W),"h3e6a573e".U(32.W),"h3e6e3962".U(32.W),"h3e722511".U(32.W),"h3e761a52".U(32.W),"h3e7a192c".U(32.W),
    "h3e7e21a5".U(32.W),"h3e8119e2".U(32.W),"h3e8327c7".U(32.W),"h3e853a86".U(32.W),"h3e875222".U(32.W),"h3e896e9d".U(32.W),"h3e8b8ffc".U(32.W),"h3e8db641".U(32.W),
    "h3e8fe170".U(32.W),"h3e92118b".U(32.W),"h3e944696".U(32.W),"h3e968095".U(32.W),"h3e98bf89".U(32.W),"h3e9b0377".U(32.W),"h3e9d4c62".U(32.W),"h3e9f9a4c".U(32.W),
    "h3ea1ed38".U(32.W),"h3ea4452b".U(32.W),"h3ea6a226".U(32.W),"h3ea9042e".U(32.W),"h3eab6b44".U(32.W),"h3eadd76d".U(32.W),"h3eb048aa".U(32.W),"h3eb2bf00".U(32.W),
    "h3eb53a71".U(32.W),"h3eb7bb00".U(32.W),"h3eba40b1".U(32.W),"h3ebccb85".U(32.W),"h3ebf5b81".U(32.W),"h3ec1f0a7".U(32.W),"h3ec48af9".U(32.W),"h3ec72a7c".U(32.W),
    "h3ec9cf32".U(32.W),"h3ecc791e".U(32.W),"h3ecf2842".U(32.W),"h3ed1dca2".U(32.W),"h3ed49641".U(32.W),"h3ed75521".U(32.W),"h3eda1946".U(32.W),"h3edce2b2".U(32.W),
    "h3edfb168".U(32.W),"h3ee2856a".U(32.W),"h3ee55ebd".U(32.W),"h3ee83d63".U(32.W),"h3eeb215d".U(32.W),"h3eee0ab1".U(32.W),"h3ef0f95f".U(32.W),"h3ef3ed6b".U(32.W),
    "h3ef6e6d8".U(32.W),"h3ef9e5a8".U(32.W),"h3efce9de".U(32.W),"h3efff37e".U(32.W),"h3f018145".U(32.W),"h3f030b82".U(32.W),"h3f049877".U(32.W),"h3f062827".U(32.W),
    "h3f07ba92".U(32.W),"h3f094fb9".U(32.W),"h3f0ae79f".U(32.W),"h3f0c8244".U(32.W),"h3f0e1faa".U(32.W),"h3f0fbfd2".U(32.W),"h3f1162be".U(32.W),"h3f13086e".U(32.W),
    "h3f14b0e4".U(32.W),"h3f165c22".U(32.W),"h3f180a29".U(32.W),"h3f19bafa".U(32.W),"h3f1b6e96".U(32.W),"h3f1d24ff".U(32.W),"h3f1ede36".U(32.W),"h3f209a3c".U(32.W),
    "h3f225913".U(32.W),"h3f241abc".U(32.W),"h3f25df38".U(32.W),"h3f27a689".U(32.W),"h3f2970af".U(32.W),"h3f2b3dad".U(32.W),"h3f2d0d83".U(32.W),"h3f2ee032".U(32.W),
    "h3f30b5bd".U(32.W),"h3f328e24".U(32.W),"h3f346968".U(32.W),"h3f36478b".U(32.W),"h3f38288f".U(32.W),"h3f3a0c73".U(32.W),"h3f3bf33a".U(32.W),"h3f3ddce5".U(32.W),
    "h3f3fc975".U(32.W),"h3f41b8eb".U(32.W),"h3f43ab48".U(32.W),"h3f45a08f".U(32.W),"h3f4798bf".U(32.W),"h3f4993db".U(32.W),"h3f4b91e3".U(32.W),"h3f4d92d8".U(32.W),
    "h3f4f96bd".U(32.W),"h3f519d92".U(32.W),"h3f53a758".U(32.W),"h3f55b411".U(32.W),"h3f57c3be".U(32.W),"h3f59d65f".U(32.W),"h3f5bebf7".U(32.W),"h3f5e0486".U(32.W),
    "h3f60200e".U(32.W),"h3f623e90".U(32.W),"h3f64600c".U(32.W),"h3f668485".U(32.W),"h3f68abfb".U(32.W),"h3f6ad670".U(32.W),"h3f6d03e5".U(32.W),"h3f6f345a".U(32.W),
    "h3f7167d2".U(32.W),"h3f739e4d".U(32.W),"h3f75d7cc".U(32.W),"h3f781451".U(32.W),"h3f7a53dd".U(32.W),"h3f7c9671".U(32.W),"h3f7edc0e".U(32.W)))
}
