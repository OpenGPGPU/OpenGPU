package opengpu.system

import chisel3._
import circt.stage.ChiselStage
import java.nio.file.{Files,Paths}
import java.util.Locale
import scala.jdk.CollectionConverters._
import opengpu.config.GpuConfig
import opengpu.graphics._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationUniformAbiSpec extends AnyFlatSpec {
  it should "preserve every published GNT1/2/3 word and append only the exact reserved line" in {
    class Check extends RawModule {
      val iso=Wire(new TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      val tri=Wire(new TessellationTrianglePatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      val quad=Wire(new TessellationQuadPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      iso:=0.U.asTypeOf(iso);tri:=0.U.asTypeOf(tri);quad:=0.U.asTypeOf(quad)
      def compare(file:String,prefix:String,old:Seq[(String,Int,Int)],extended:Seq[(String,Int,Int)]):Unit={
        val definition="^#define ([A-Z0-9_]+) ([0-9]+)u$".r
        val macros=Files.readAllLines(Paths.get(file)).asScala.collect{case definition(k,v)=>k->v.toInt}.toMap
        assert(macros.keys.count(k=>k.startsWith(prefix) && k.endsWith("_WORD"))==old.size)
        for((name,word,bits)<-old){val n=prefix+name.replace("[","").replace("]","").replace('.','_').toUpperCase(Locale.ROOT)
          assert(macros(n+"_WORD")==word,s"moved $name");assert(macros(n+"_BITS")==bits,s"changed width $name")}
        assert(extended.take(old.size)==old)
        val added=extended.drop(old.size)
        assert(added.size==11 && added.forall(x=>TessellationUniformSequenceLayout.isUniformField(x._1)))
        val occupied=added.flatMap{case(_,word,bits)=>word until word+(bits+31)/32}
        assert(occupied== (496 until 509) && occupied.distinct.size==13)
        assert(old.forall{case(_,w,b)=>w+(b+31)/32<=496})
      }
      compare("driver/opengpu_native_tessellation_sequence_abi.h","OPENGPU_NATIVE_TESSELLATION_SEQUENCE_",
        TessellationNativeSequenceLayout.fields(iso).map(f=>(f.name,f.word,f.width)),TessellationNativeSequenceLayout.fields(iso,true).map(f=>(f.name,f.word,f.width)))
      compare("driver/opengpu_native_tessellation_triangle_sequence_abi.h","OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_",
        TessellationNativeTriangleSequenceLayout.fields(tri).map(f=>(f.name,f.word,f.width)),TessellationNativeTriangleSequenceLayout.fields(tri,true).map(f=>(f.name,f.word,f.width)))
      compare("driver/opengpu_native_tessellation_quad_sequence_abi.h","OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_",
        TessellationNativeQuadSequenceLayout.fields(quad).map(f=>(f.name,f.word,f.width)),TessellationNativeQuadSequenceLayout.fields(quad,true).map(f=>(f.name,f.word,f.width)))
      assert(ShaderUniformLimits.blocksPerStage==12 && ShaderUniformLimits.bindings==24 && ShaderUniformLimits.blockBytes==16384)
      assert(ShaderUniformLimits.defaultComponents==Seq.fill(6)(1024))
      assert(NativeUniformCapabilities.version==1 && NativeUniformCapabilities.words(4)==3)
    }
    ChiselStage.emitCHIRRTL(new Check)
  }
}
