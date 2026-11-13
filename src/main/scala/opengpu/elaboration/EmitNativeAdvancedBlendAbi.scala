package opengpu.elaboration

import chisel3._
import circt.stage.ChiselStage
import opengpu.graphics._

object EmitNativeAdvancedBlendAbi {
  def main(args: Array[String]): Unit={
    var offsets=Seq.empty[(String,Int)]
    class Describe extends RawModule {
      val state=Wire(new TypedFragmentState(FragmentShaderConfig()));state:=0.U.asTypeOf(state)
      val frame=Wire(new NativeBinnedGraphicsFrameRecord);frame:=0.U.asTypeOf(frame)
      val states=TypedFragmentStateLayout.fields(state).map(f=>f.name->f.word).toMap
      val frames=NativeBinnedGraphicsLayout.frameFields(frame).map(f=>f.name->f.word).toMap
      offsets=Seq("GFS_PAYLOAD_VERSION"->"version","GFS_BLEND_VERSION"->"blend.version",
        "GFS_BLEND_ENABLE"->"blend.enable","GFS_BLEND_SRC_RGB"->"blend.srcRGB","GFS_BLEND_DST_RGB"->"blend.dstRGB",
        "GFS_BLEND_SRC_ALPHA"->"blend.srcAlpha","GFS_BLEND_DST_ALPHA"->"blend.dstAlpha",
        "GFS_BLEND_EQUATION_RGB"->"blend.equationRGB","GFS_BLEND_EQUATION_ALPHA"->"blend.equationAlpha")
        .map{case(name,field)=>name->states(field)}++
        (0 until 4).flatMap(c=>Seq(s"GFS_BUFFER$c"->states(s"blend.buffers[$c]"),
          s"GFS_COLOR_TYPE$c"->states(s"fragment.colorTypes[$c]"),
          s"FRAME_COLOR_ENABLED$c"->frames(s"frame.image.pixels.colors.colors[$c].enabled"),
          s"FRAME_COLOR_FORMAT$c"->frames(s"frame.image.pixels.colors.colors[$c].format")))
    }
    ChiselStage.emitCHIRRTL(new Describe)
    val definitions=offsets.map{case(name,word)=>s"#define OPENGPU_NATIVE_ADVANCED_BLEND_${name}_WORD ${word}u\n"}.mkString
    val header=NativeAdvancedBlendCapabilities.header.stripSuffix("#endif\n")+definitions+"#endif\n"
    EmitCommandAbi.emitFixed(args,Seq("driver/opengpu_native_advanced_blend_abi.h"->header))
    println(s"NATIVE_ADVANCED_BLEND_ABI checked=${args.nonEmpty} words=${NativeAdvancedBlendCapabilities.wordCount}")
  }
}
