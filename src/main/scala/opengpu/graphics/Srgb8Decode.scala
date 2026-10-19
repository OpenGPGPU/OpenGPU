package opengpu.graphics

import chisel3._
import chisel3.util._

/** FP32 RNE inverse sRGB for each8-bit code, generated at100decimal digits. */
private[graphics] object Srgb8Decode {
  def values: Vec[UInt]=VecInit(Seq(
    "h00000000".U(32.W),"h399f22b4".U(32.W),"h3a1f22b4".U(32.W),"h3a6eb40e".U(32.W),"h3a9f22b4".U(32.W),"h3ac6eb61".U(32.W),"h3aeeb40e".U(32.W),"h3b0b3e5d".U(32.W),
    "h3b1f22b4".U(32.W),"h3b33070a".U(32.W),"h3b46eb61".U(32.W),"h3b5b518e".U(32.W),"h3b70f18f".U(32.W),"h3b83e1c6".U(32.W),"h3b8fe616".U(32.W),"h3b9c87fd".U(32.W),
    "h3ba9c9b6".U(32.W),"h3bb7ad6f".U(32.W),"h3bc6354a".U(32.W),"h3bd56360".U(32.W),"h3be539c1".U(32.W),"h3bf5ba71".U(32.W),"h3c0373b6".U(32.W),"h3c0c6153".U(32.W),
    "h3c15a705".U(32.W),"h3c1f45be".U(32.W),"h3c293e6b".U(32.W),"h3c3391f7".U(32.W),"h3c3e4149".U(32.W),"h3c494d44".U(32.W),"h3c54b6c9".U(32.W),"h3c607eb4".U(32.W),
    "h3c6ca5df".U(32.W),"h3c792d22".U(32.W),"h3c830aa9".U(32.W),"h3c89af9f".U(32.W),"h3c9085dc".U(32.W),"h3c978dc6".U(32.W),"h3c9ec7c2".U(32.W),"h3ca63433".U(32.W),
    "h3cadd37d".U(32.W),"h3cb5a602".U(32.W),"h3cbdac21".U(32.W),"h3cc5e63a".U(32.W),"h3cce54ac".U(32.W),"h3cd6f7d5".U(32.W),"h3cdfd010".U(32.W),"h3ce8ddba".U(32.W),
    "h3cf2212d".U(32.W),"h3cfb9ac3".U(32.W),"h3d02a56a".U(32.W),"h3d0798dd".U(32.W),"h3d0ca7e6".U(32.W),"h3d11d2af".U(32.W),"h3d171964".U(32.W),"h3d1c7c30".U(32.W),
    "h3d21fb3c".U(32.W),"h3d2796b2".U(32.W),"h3d2d4ebb".U(32.W),"h3d332381".U(32.W),"h3d39152b".U(32.W),"h3d3f23e4".U(32.W),"h3d454fd2".U(32.W),"h3d4b991d".U(32.W),
    "h3d51ffec".U(32.W),"h3d588468".U(32.W),"h3d5f26b6".U(32.W),"h3d65e6fd".U(32.W),"h3d6cc563".U(32.W),"h3d73c20e".U(32.W),"h3d7add24".U(32.W),"h3d810b65".U(32.W),
    "h3d84b793".U(32.W),"h3d88732e".U(32.W),"h3d8c3e48".U(32.W),"h3d9018f4".U(32.W),"h3d940344".U(32.W),"h3d97fd49".U(32.W),"h3d9c0715".U(32.W),"h3da020ba".U(32.W),
    "h3da44a4a".U(32.W),"h3da883d6".U(32.W),"h3daccd6f".U(32.W),"h3db12727".U(32.W),"h3db5910f".U(32.W),"h3dba0b38".U(32.W),"h3dbe95b3".U(32.W),"h3dc33090".U(32.W),
    "h3dc7dbe0".U(32.W),"h3dcc97b4".U(32.W),"h3dd1641d".U(32.W),"h3dd6412b".U(32.W),"h3ddb2eee".U(32.W),"h3de02d76".U(32.W),"h3de53cd4".U(32.W),"h3dea5d18".U(32.W),
    "h3def8e51".U(32.W),"h3df4d090".U(32.W),"h3dfa23e5".U(32.W),"h3dff885e".U(32.W),"h3e027f06".U(32.W),"h3e05427f".U(32.W),"h3e080ea2".U(32.W),"h3e0ae377".U(32.W),
    "h3e0dc104".U(32.W),"h3e10a753".U(32.W),"h3e13966a".U(32.W),"h3e168e51".U(32.W),"h3e198f0f".U(32.W),"h3e1c98ac".U(32.W),"h3e1fab30".U(32.W),"h3e22c6a1".U(32.W),
    "h3e25eb07".U(32.W),"h3e29186a".U(32.W),"h3e2c4ed0".U(32.W),"h3e2f8e42".U(32.W),"h3e32d6c5".U(32.W),"h3e362862".U(32.W),"h3e39831f".U(32.W),"h3e3ce703".U(32.W),
    "h3e405417".U(32.W),"h3e43ca60".U(32.W),"h3e4749e6".U(32.W),"h3e4ad2af".U(32.W),"h3e4e64c3".U(32.W),"h3e520029".U(32.W),"h3e55a4e7".U(32.W),"h3e595305".U(32.W),
    "h3e5d0a89".U(32.W),"h3e60cb7a".U(32.W),"h3e6495df".U(32.W),"h3e6869be".U(32.W),"h3e6c471f".U(32.W),"h3e702e07".U(32.W),"h3e741e7e".U(32.W),"h3e78188b".U(32.W),
    "h3e7c1c33".U(32.W),"h3e8014bf".U(32.W),"h3e822039".U(32.W),"h3e84308b".U(32.W),"h3e8645b8".U(32.W),"h3e885fc3".U(32.W),"h3e8a7eb0".U(32.W),"h3e8ca281".U(32.W),
    "h3e8ecb3b".U(32.W),"h3e90f8df".U(32.W),"h3e932b72".U(32.W),"h3e9562f6".U(32.W),"h3e979f6f".U(32.W),"h3e99e0e0".U(32.W),"h3e9c274c".U(32.W),"h3e9e72b6".U(32.W),
    "h3ea0c321".U(32.W),"h3ea31890".U(32.W),"h3ea57307".U(32.W),"h3ea7d288".U(32.W),"h3eaa3716".U(32.W),"h3eaca0b6".U(32.W),"h3eaf0f68".U(32.W),"h3eb18332".U(32.W),
    "h3eb3fc15".U(32.W),"h3eb67a14".U(32.W),"h3eb8fd34".U(32.W),"h3ebb8576".U(32.W),"h3ebe12de".U(32.W),"h3ec0a56e".U(32.W),"h3ec33d2a".U(32.W),"h3ec5da14".U(32.W),
    "h3ec87c30".U(32.W),"h3ecb2380".U(32.W),"h3ecdd008".U(32.W),"h3ed081ca".U(32.W),"h3ed338c9".U(32.W),"h3ed5f508".U(32.W),"h3ed8b68a".U(32.W),"h3edb7d52".U(32.W),
    "h3ede4963".U(32.W),"h3ee11abf".U(32.W),"h3ee3f169".U(32.W),"h3ee6cd65".U(32.W),"h3ee9aeb5".U(32.W),"h3eec955b".U(32.W),"h3eef815c".U(32.W),"h3ef272b8".U(32.W),
    "h3ef56974".U(32.W),"h3ef86593".U(32.W),"h3efb6716".U(32.W),"h3efe6e00".U(32.W),"h3f00bd2b".U(32.W),"h3f02460c".U(32.W),"h3f03d1a5".U(32.W),"h3f055ff7".U(32.W),
    "h3f06f104".U(32.W),"h3f0884cd".U(32.W),"h3f0a1b54".U(32.W),"h3f0bb499".U(32.W),"h3f0d509f".U(32.W),"h3f0eef65".U(32.W),"h3f1090ef".U(32.W),"h3f12353d".U(32.W),
    "h3f13dc50".U(32.W),"h3f15862a".U(32.W),"h3f1732cc".U(32.W),"h3f18e237".U(32.W),"h3f1a946e".U(32.W),"h3f1c4970".U(32.W),"h3f1e0140".U(32.W),"h3f1fbbde".U(32.W),
    "h3f21794d".U(32.W),"h3f23398c".U(32.W),"h3f24fc9f".U(32.W),"h3f26c285".U(32.W),"h3f288b41".U(32.W),"h3f2a56d2".U(32.W),"h3f2c253c".U(32.W),"h3f2df67f".U(32.W),
    "h3f2fca9c".U(32.W),"h3f31a194".U(32.W),"h3f337b6a".U(32.W),"h3f35581d".U(32.W),"h3f3737b0".U(32.W),"h3f391a24".U(32.W),"h3f3aff7a".U(32.W),"h3f3ce7b2".U(32.W),
    "h3f3ed2cf".U(32.W),"h3f40c0d2".U(32.W),"h3f42b1bc".U(32.W),"h3f44a58e".U(32.W),"h3f469c49".U(32.W),"h3f4895ef".U(32.W),"h3f4a9280".U(32.W),"h3f4c91ff".U(32.W),
    "h3f4e946c".U(32.W),"h3f5099c9".U(32.W),"h3f52a216".U(32.W),"h3f54ad56".U(32.W),"h3f56bb88".U(32.W),"h3f58ccaf".U(32.W),"h3f5ae0cc".U(32.W),"h3f5cf7df".U(32.W),
    "h3f5f11ea".U(32.W),"h3f612eef".U(32.W),"h3f634eee".U(32.W),"h3f6571e9".U(32.W),"h3f6797e0".U(32.W),"h3f69c0d5".U(32.W),"h3f6becca".U(32.W),"h3f6e1bbf".U(32.W),
    "h3f704db5".U(32.W),"h3f7282ae".U(32.W),"h3f74baab".U(32.W),"h3f76f5ae".U(32.W),"h3f7933b6".U(32.W),"h3f7b74c6".U(32.W),"h3f7db8de".U(32.W),"h3f800000".U(32.W)))
}
