package opengpu.elaboration

import chisel3._
import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Paths}
import java.util.Locale
import opengpu.graphics.{NativeBinnedGraphicsJobRecord,NativeBinnedGraphicsLayout}
import opengpu.system.{TessellationNativeJobLayout,TessellationNativeSequenceLayout}

/** Emit the GNQ1 job word layout without implying a public host capability. */
object EmitTessellationNativeJobAbi {
  private case class Entry(name:String,word:Int,bits:Int,signed:Boolean)
  def main(args:Array[String]):Unit={
    val check=args.contains("--check");val paths=args.filterNot(_=="--check")
    require(paths.length<=2,"EmitTessellationNativeJobAbi [header] [schema] [--check]")
    var entries=Seq.empty[Entry]
    class Describe extends RawModule {
      val job=Wire(new NativeBinnedGraphicsJobRecord);job:=0.U.asTypeOf(job)
      entries=NativeBinnedGraphicsLayout.jobFields(job).map(f=>Entry(f.name,f.word,f.width,f.value.isInstanceOf[SInt]))
    }
    ChiselStage.emitCHIRRTL(new Describe)
    val names=entries.map(_.name.replace("[","").replace("]","").replace('.','_').toUpperCase(Locale.ROOT))
    require(names.distinct.size==names.size && names.forall(_.matches("[A-Z0-9_]+")))
    val prefix="OPENGPU_NATIVE_TESSELLATION_JOB_"
    val header=new StringBuilder("/* Generated GNQ1 draft. Words are little-endian; reserved words are zero. */\n"+
      "#ifndef OPENGPU_NATIVE_TESSELLATION_JOB_ABI_H\n#define OPENGPU_NATIVE_TESSELLATION_JOB_ABI_H\n#ifdef __KERNEL__\n#include <linux/types.h>\ntypedef u32 opengpu_native_tessellation_job_word;\n#else\n#include <stdint.h>\ntypedef uint32_t opengpu_native_tessellation_job_word;\n#endif\n\n")
    header.append(s"#define ${prefix}MAGIC 0x${TessellationNativeJobLayout.magic.toString(16)}u\n")
    header.append(s"#define ${prefix}VERSION ${NativeBinnedGraphicsLayout.version}u\n")
    header.append(s"#define ${prefix}WORDS ${NativeBinnedGraphicsLayout.jobWords}u\n")
    header.append(s"#define ${prefix}BYTES ${NativeBinnedGraphicsLayout.jobBytes}u\n")
    header.append(s"#define ${prefix}FRAME_MAGIC 0x${NativeBinnedGraphicsLayout.frameMagic.toString(16)}u\n")
    header.append(s"#define ${prefix}FRAME_BYTES ${NativeBinnedGraphicsLayout.frameBytes}u\n")
    header.append(s"#define ${prefix}DRAW_MAGIC 0x${TessellationNativeSequenceLayout.magic.toString(16)}u\n")
    header.append(s"#define ${prefix}DRAW_BYTES ${TessellationNativeSequenceLayout.bytes}u\n")
    for((f,name)<-entries.zip(names))header.append(s"#define ${prefix}${name}_WORD ${f.word}u\n#define ${prefix}${name}_BITS ${f.bits}u\n")
    header.append(s"struct opengpu_native_tessellation_job { opengpu_native_tessellation_job_word words[${prefix}WORDS]; };\n\n#endif\n")
    val fs=entries.map(f=>s"    {\"name\":\"${f.name}\",\"word\":${f.word},\"bits\":${f.bits},\"signed\":${f.signed}}").mkString(",\n")
    val json=s"{\n  \"magic\":\"0x${TessellationNativeJobLayout.magic.toString(16)}\",\n  \"version\":${NativeBinnedGraphicsLayout.version},\n  \"words\":${NativeBinnedGraphicsLayout.jobWords},\n  \"frame_magic\":\"0x${NativeBinnedGraphicsLayout.frameMagic.toString(16)}\",\n  \"draw_magic\":\"0x${TessellationNativeSequenceLayout.magic.toString(16)}\",\n  \"fields\":[\n$fs\n  ]\n}\n"
    val outputs=Seq(paths.headOption.getOrElse("driver/opengpu_native_tessellation_job_abi.h")->header.toString,
      paths.lift(1).getOrElse("docs/abi/native-tessellation-job-v1.json")->json)
    EmitCommandAbi.writeOrCheck(check, outputs)
    println(s"TESSELLATION_NATIVE_JOB_ABI fields=${entries.size} bytes=${NativeBinnedGraphicsLayout.jobBytes} checked=$check")
  }
}
