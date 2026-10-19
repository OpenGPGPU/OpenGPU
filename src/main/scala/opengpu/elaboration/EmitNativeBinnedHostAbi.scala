package opengpu.elaboration

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Paths}
import opengpu.graphics.{NativeBinnedGraphicsCapabilities,NativeBinnedCompletionInfo}

object EmitNativeBinnedHostAbi {
  def main(args: Array[String]): Unit={
    val check=args.contains("--check");val paths=args.filterNot(_=="--check");require(paths.length<=2)
    val c=NativeBinnedGraphicsCapabilities
    val prefix="OPENGPU_NATIVE_BINNED_HOST_"
    val header=new StringBuilder("/* Generated GNC1 discovery layout. Records/resources are virtual; image views are trusted physical addresses.\n"+
      " * Runtime limits below are separate from fixed GNJ1/GNF1/GND1 record capacities.\n"+
      " * Payload fields are zero when LOCAL_PAYLOAD is clear. Slot bytes = SLOT_LINES * 64. */\n"+
      "#ifndef OPENGPU_NATIVE_BINNED_HOST_ABI_H\n#define OPENGPU_NATIVE_BINNED_HOST_ABI_H\n#include <stdint.h>\n\n")
    header.append(s"#define ${prefix}MAGIC 0x${c.magic.toString(16)}u\n#define ${prefix}WORDS ${c.wordCount}u\n")
    for(f<-c.fields){header.append(s"#define $prefix${f.name}_WORD ${f.word}u\n#define $prefix${f.name}_SHIFT ${f.shift}u\n#define $prefix${f.name}_MASK 0x${f.mask.toString(16)}u\n")}
    header.append(s"struct opengpu_native_binned_host_caps { uint32_t words[${prefix}WORDS]; };\n\n")
    val info=NativeBinnedCompletionInfo;val ip="OPENGPU_NATIVE_BINNED_COMPLETION_"
    header.append("/* Read-only GNX1 belongs to the held completion. Empty/non-native slots read zero.\n"+
      " * POP/reset clear visibility. RECORDED_ADDRESS is a raw owner field; consult stage/valid bits. */\n")
    header.append(s"#define ${ip}MAGIC 0x${info.magic.toString(16)}u\n#define ${ip}BASE 0x${info.base.toHexString}u\n#define ${ip}WORDS ${info.wordCount}u\n")
    for(f<-info.fields)header.append(s"#define $ip${f.name}_WORD ${f.word}u\n#define $ip${f.name}_SHIFT ${f.shift}u\n#define $ip${f.name}_MASK 0x${f.mask.toString(16)}u\n")
    header.append(s"struct opengpu_native_binned_completion_info { uint32_t words[${ip}WORDS]; };\n\n#endif\n")
    val fields=c.fields.map(f=>s"    {\"name\":\"${f.name}\",\"word\":${f.word},\"shift\":${f.shift},\"bits\":${f.bits},\"unit\":\"${f.unit}\"}").mkString(",\n")
    val inf=info.fields.map(f=>s"      {\"name\":\"${f.name}\",\"word\":${f.word},\"shift\":${f.shift},\"bits\":${f.bits},\"unit\":\"${f.unit}\"}").mkString(",\n")
    val json=s"{\n  \"magic\":\"0x${c.magic.toString(16)}\",\"words\":${c.wordCount},\"fields\":[\n$fields\n  ],\n  \"completion\":{\"magic\":\"0x${info.magic.toString(16)}\",\"base\":${info.base},\"words\":${info.wordCount},\"fields\":[\n$inf\n  ]}\n}\n"
    for((name,contents)<-Seq(paths.headOption.getOrElse("driver/opengpu_native_binned_host_abi.h")->header.toString,
      paths.lift(1).getOrElse("docs/abi/native-binned-host-v1.json")->json)){
      val p=Paths.get(name)
      if(check)require(Files.isRegularFile(p) && Files.readString(p,UTF_8)==contents,s"GNC1 discovery ABI differs: $p")
      else{Option(p.getParent).foreach(Files.createDirectories(_));Files.writeString(p,contents,UTF_8)}
    }
    println(s"NATIVE_BINNED_HOST_ABI words=${c.wordCount} fields=${c.fields.size} completionWords=${info.wordCount} completionFields=${info.fields.size} checked=$check")
  }
}
