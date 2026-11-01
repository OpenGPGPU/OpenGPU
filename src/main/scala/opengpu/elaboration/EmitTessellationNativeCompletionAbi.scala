package opengpu.elaboration

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Paths}
import opengpu.system.TessellationNativeCompletionInfo

/** Emit the experimental GNZ1 layout without advertising a host capability. */
object EmitTessellationNativeCompletionAbi {
  def main(args:Array[String]):Unit={
    val check=args.contains("--check");val paths=args.filterNot(_=="--check")
    require(paths.length<=2,"EmitTessellationNativeCompletionAbi [header] [schema] [--check]")
    val c=TessellationNativeCompletionInfo;val prefix="OPENGPU_NATIVE_TESSELLATION_COMPLETION_"
    require(c.fields.map(_.name).distinct.size==c.fields.size)
    for(f<-c.fields)require(f.word>=0 && f.word<c.wordCount && f.shift>=0 && f.bits>=1 && f.shift+f.bits<=32)
    for((a,i)<-c.fields.zipWithIndex;b<-c.fields.drop(i+1) if a.word==b.word)
      require((a.mask & b.mask)==0,s"overlapping completion fields ${a.name} and ${b.name}")
    val header=new StringBuilder("/* Generated experimental GNZ1 snapshot. Read before popping the held completion. */\n"+
      "#ifndef OPENGPU_NATIVE_TESSELLATION_COMPLETION_ABI_H\n#define OPENGPU_NATIVE_TESSELLATION_COMPLETION_ABI_H\n#include <stdint.h>\n\n")
    header.append(s"#define ${prefix}MAGIC 0x${c.magic.toString(16)}u\n#define ${prefix}BASE 0x${c.base.toHexString}u\n#define ${prefix}WORDS ${c.wordCount}u\n")
    for(f<-c.fields)header.append(s"#define $prefix${f.name}_WORD ${f.word}u\n#define $prefix${f.name}_SHIFT ${f.shift}u\n#define $prefix${f.name}_MASK 0x${f.mask.toString(16)}u\n")
    header.append(s"struct opengpu_native_tessellation_completion_info { uint32_t words[${prefix}WORDS]; };\n\n#endif\n")
    val fields=c.fields.map(f=>s"    {\"name\":\"${f.name}\",\"word\":${f.word},\"shift\":${f.shift},\"bits\":${f.bits},\"unit\":\"${f.unit}\"}").mkString(",\n")
    val json=s"{\n  \"magic\":\"0x${c.magic.toString(16)}\",\"base\":${c.base},\"words\":${c.wordCount},\"experimental\":true,\n  \"fields\":[\n$fields\n  ]\n}\n"
    for((name,value)<-Seq(paths.headOption.getOrElse("driver/opengpu_native_tessellation_completion_abi.h")->header.toString,
      paths.lift(1).getOrElse("docs/abi/native-tessellation-completion-v1.json")->json)){
      val path=Paths.get(name)
      if(check)require(Files.isRegularFile(path) && Files.readString(path,UTF_8)==value,s"GNZ1 ABI differs: $path")
      else{Option(path.getParent).foreach(p=>Files.createDirectories(p));Files.writeString(path,value,UTF_8)}
    }
    println(s"TESSELLATION_NATIVE_COMPLETION_ABI fields=${c.fields.size} words=${c.wordCount} checked=$check")
  }
}
