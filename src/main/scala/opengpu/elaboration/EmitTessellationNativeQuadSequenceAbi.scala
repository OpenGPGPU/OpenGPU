package opengpu.elaboration

import chisel3._
import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Paths}
import java.util.Locale
import opengpu.config.GpuConfig
import opengpu.graphics.VertexShaderStageConfig
import opengpu.system.{TessellationNativeQuadSequenceLayout,TessellationQuadPatchSequenceDescriptor}

/** Emit the fixed draft record, and compare it byte-for-byte in --check mode. */
object EmitTessellationNativeQuadSequenceAbi {
  private case class Entry(name:String,word:Int,bits:Int,signed:Boolean)
  def main(args:Array[String]):Unit={
    val check=args.contains("--check");val paths=args.filterNot(_=="--check")
    require(paths.length<=2,"EmitTessellationNativeQuadSequenceAbi [header] [schema] [--check]")
    var entries=Seq.empty[Entry]
    class Describe extends RawModule {
      val draw=Wire(new TessellationQuadPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      draw:=0.U.asTypeOf(draw)
      entries=TessellationNativeQuadSequenceLayout.fields(draw).map(f=>Entry(f.name,f.word,f.width,f.value.isInstanceOf[SInt]))
    }
    ChiselStage.emitCHIRRTL(new Describe)
    val names=entries.map(_.name.replace("[","").replace("]","").replace('.','_').toUpperCase(Locale.ROOT))
    require(names.distinct.size==names.size && names.forall(_.matches("[A-Z0-9_]+")))
    val prefix="OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_"
    val header=new StringBuilder("/* Generated fixed little-endian draft. Narrow signed fields sign-extend; reserved words are zero. */\n"+
      "#ifndef OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_ABI_H\n#define OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_ABI_H\n#ifdef __KERNEL__\n#include <linux/types.h>\ntypedef u32 opengpu_native_tessellation_quad_sequence_word;\n#else\n#include <stdint.h>\ntypedef uint32_t opengpu_native_tessellation_quad_sequence_word;\n#endif\n\n")
    header.append(s"#define ${prefix}MAGIC 0x${TessellationNativeQuadSequenceLayout.magic.toString(16)}u\n")
    header.append(s"#define ${prefix}VERSION ${TessellationNativeQuadSequenceLayout.version}u\n")
    header.append(s"#define ${prefix}WORDS ${TessellationNativeQuadSequenceLayout.words}u\n")
    header.append(s"#define ${prefix}BYTES ${TessellationNativeQuadSequenceLayout.bytes}u\n")
    for((f,name)<-entries.zip(names))header.append(s"#define ${prefix}${name}_WORD ${f.word}u\n#define ${prefix}${name}_BITS ${f.bits}u\n")
    header.append(s"struct opengpu_native_tessellation_quad_sequence { opengpu_native_tessellation_quad_sequence_word words[${prefix}WORDS]; };\n\n#endif\n")
    val fs=entries.map(f=>s"    {\"name\":\"${f.name}\",\"word\":${f.word},\"bits\":${f.bits},\"signed\":${f.signed}}").mkString(",\n")
    val json=s"{\n  \"magic\":\"0x${TessellationNativeQuadSequenceLayout.magic.toString(16)}\",\n  \"version\":${TessellationNativeQuadSequenceLayout.version},\n  \"words\":${TessellationNativeQuadSequenceLayout.words},\n  \"fields\":[\n$fs\n  ]\n}\n"
    val outputs=Seq(paths.headOption.getOrElse("driver/opengpu_native_tessellation_quad_sequence_abi.h")->header.toString,
      paths.lift(1).getOrElse("docs/abi/native-tessellation-quad-sequence-v3.json")->json)
    EmitCommandAbi.writeOrCheck(check, outputs)
    println(s"TESSELLATION_NATIVE_QUAD_SEQUENCE_ABI fields=${entries.size} bytes=${TessellationNativeQuadSequenceLayout.bytes} checked=$check")
  }
}
