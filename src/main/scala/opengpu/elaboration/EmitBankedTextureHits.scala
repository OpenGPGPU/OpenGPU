package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics._

object EmitBankedTextureHits {
  def main(args: Array[String]): Unit={
    require(args.length<=2,"EmitBankedTextureHits [store|cluster|fills|readthrough|fp32|fp32-policy|translated|normalize] [target-dir]")
    val output=Array("--target-dir",args.lift(1).getOrElse("generated/mobile-h4/banked-texture-hits-rtl"))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args.headOption.getOrElse("cluster") match {
      case "store"=>ChiselStage.emitSystemVerilogFile(new BankedTextureLineStore(),output,lowering)
      case "cluster"=>ChiselStage.emitSystemVerilogFile(new BankedRgba8TextureHits(),output,lowering)
      case "readthrough"=>ChiselStage.emitSystemVerilogFile(new BankedRgba8TextureHits(readThrough=true),output,lowering)
      case "fp32"=>ChiselStage.emitSystemVerilogFile(new BankedRgba8FloatTextureService(),output,lowering)
      case "fp32-policy"=>ChiselStage.emitSystemVerilogFile(new BankedRgba8FloatTextureService(cachePolicyInput=true),output,lowering)
      case "translated"=>ChiselStage.emitSystemVerilogFile(new TranslatedBankedRgba8TextureService(),output,lowering)
      case "normalize"=>ChiselStage.emitSystemVerilogFile(new Rgba8WeightedToFp32(),output,lowering)
      case "fills"=>ChiselStage.emitSystemVerilogFile(new BankedTextureFillOwner(),output,lowering)
      case _=>throw new IllegalArgumentException("unknown banked texture hit profile")
    }
  }
}
