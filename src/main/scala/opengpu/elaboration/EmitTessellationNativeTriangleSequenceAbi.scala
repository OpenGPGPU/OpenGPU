package opengpu.elaboration

import chisel3._
import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Paths}
import java.util.Locale
import opengpu.config.GpuConfig
import opengpu.graphics.VertexShaderStageConfig
import opengpu.system.{TessellationNativeTriangleSequenceLayout,TessellationTrianglePatchSequenceDescriptor}

/** Emit the fixed draft record, and compare it byte-for-byte in --check mode. */
object EmitTessellationNativeTriangleSequenceAbi {
  private case class Entry(name:String,word:Int,bits:Int,signed:Boolean)
  def main(args:Array[String]):Unit={
    val check=args.contains("--check");val paths=args.filterNot(_=="--check")
    require(paths.length<=2,"EmitTessellationNativeTriangleSequenceAbi [header] [schema] [--check]")
    var entries=Seq.empty[Entry]
    class Describe extends RawModule {
      val draw=Wire(new TessellationTrianglePatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      draw:=0.U.asTypeOf(draw)
      entries=TessellationNativeTriangleSequenceLayout.fields(draw).map(f=>Entry(f.name,f.word,f.width,f.value.isInstanceOf[SInt]))
    }
    ChiselStage.emitCHIRRTL(new Describe)
    val names=entries.map(_.name.replace("[","").replace("]","").replace('.','_').toUpperCase(Locale.ROOT))
    require(names.distinct.size==names.size && names.forall(_.matches("[A-Z0-9_]+")))
    val prefix="OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_"
    val header=new StringBuilder("/* Generated fixed little-endian draft. Narrow signed fields sign-extend; reserved words are zero. */\n"+
      "#ifndef OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_ABI_H\n#define OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_ABI_H\n#include <stdint.h>\n\n")
    header.append(s"#define ${prefix}MAGIC 0x${TessellationNativeTriangleSequenceLayout.magic.toString(16)}u\n")
    header.append(s"#define ${prefix}VERSION ${TessellationNativeTriangleSequenceLayout.version}u\n")
    header.append(s"#define ${prefix}WORDS ${TessellationNativeTriangleSequenceLayout.words}u\n")
    header.append(s"#define ${prefix}BYTES ${TessellationNativeTriangleSequenceLayout.bytes}u\n")
    for((f,name)<-entries.zip(names))header.append(s"#define ${prefix}${name}_WORD ${f.word}u\n#define ${prefix}${name}_BITS ${f.bits}u\n")
    header.append(s"struct opengpu_native_tessellation_triangle_sequence { uint32_t words[${prefix}WORDS]; };\n\n#endif\n")
    val fs=entries.map(f=>s"    {\"name\":\"${f.name}\",\"word\":${f.word},\"bits\":${f.bits},\"signed\":${f.signed}}").mkString(",\n")
    val json=s"{\n  \"magic\":\"0x${TessellationNativeTriangleSequenceLayout.magic.toString(16)}\",\n  \"version\":${TessellationNativeTriangleSequenceLayout.version},\n  \"words\":${TessellationNativeTriangleSequenceLayout.words},\n  \"fields\":[\n$fs\n  ]\n}\n"
    val outputs=Seq(paths.headOption.getOrElse("driver/opengpu_native_tessellation_triangle_sequence_abi.h")->header.toString,
      paths.lift(1).getOrElse("docs/abi/native-tessellation-triangle-sequence-v2.json")->json)
    for((name,value)<-outputs){val path=Paths.get(name)
      if(check)require(Files.isRegularFile(path) && Files.readString(path,UTF_8)==value,s"tessellation sequence ABI differs: $path")
      else{Option(path.getParent).foreach(p=>Files.createDirectories(p));Files.writeString(path,value,UTF_8)}}
    println(s"TESSELLATION_NATIVE_TRIANGLE_SEQUENCE_ABI fields=${entries.size} bytes=${TessellationNativeTriangleSequenceLayout.bytes} checked=$check")
  }
}
