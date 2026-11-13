package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage
import opengpu.elaboration.EmitNativeUniformAbi
import org.scalatest.flatspec.AnyFlatSpec

class ComputeUniformRecordAbiSpec extends AnyFlatSpec {
  it should "retain graphics record offsets while extending only compute stage fields" in {
    var rootFields=Map.empty[String,(Int,Int)]
    var bindingFields=Map.empty[String,(Int,Int)]
    class Describe extends RawModule {
      val root=Wire(new ShaderUniformRootRecord(2));root:=0.U.asTypeOf(root)
      val binding=Wire(new ShaderUniformBindingRecord(3));binding:=0.U.asTypeOf(binding)
      rootFields=ShaderUniformRootLayout.fields(root).map(f=>f.name->(f.word,f.width)).toMap
      bindingFields=ShaderUniformBindingLayout.fields(binding).map(f=>f.name->(f.word,f.width)).toMap
    }
    ChiselStage.emitCHIRRTL(new Describe)
    assert(rootFields("stage")==(6,2) && bindingFields("stageMask")==((7,3)))
    assert(rootFields("activeMask")==(7,12) && rootFields("bindingsBase")==(10,32))
    assert(rootFields("bindingsLimit")==(11,33) && rootFields("defaultView")==(13,32))
    assert(bindingFields("bindingIndex")==(6,5) && bindingFields("base")==(8,32))
    assert(bindingFields("limit")==(9,33) && bindingFields("allocationBase")==(11,32))
    assert(bindingFields("allocationLimit")==(12,33) && bindingFields("readOnly")==(14,1))
    EmitNativeUniformAbi.main(Array("--check"))
  }
  it should "keep geometry records separate from frozen graphics and compute schemas" in {
    var roots=Map.empty[String,(Int,Int)];var bindings=Map.empty[String,(Int,Int)]
    class GeometryDescribe extends RawModule {
      val root=Wire(new ShaderUniformRootRecord(2));root:=0.U.asTypeOf(root)
      val binding=Wire(new ShaderUniformBindingRecord(4));binding:=0.U.asTypeOf(binding)
      roots=ShaderUniformRootLayout.fields(root).map(f=>f.name->(f.word,f.width)).toMap
      bindings=ShaderUniformBindingLayout.fields(binding).map(f=>f.name->(f.word,f.width)).toMap
    }
    ChiselStage.emitCHIRRTL(new GeometryDescribe)
    assert(roots("stage")==(6,2) && bindings("stageMask")==((7,4)))
    assert(roots("bindingsLimit")==(11,33) && bindings("limit")==((9,33)))
    assert(bindings("allocationLimit")==(12,33))
    assert(GeometryUniformResources.stage==3 && GeometryUniformResources.stageMask==8)
    assert(GeometryUniformResources.version==2 && GeometryUniformResources.rootMagic==BigInt("47555202",16))
    assert(GeometryUniformResources.bindingMagic==BigInt("47554202",16))
    assert(ShaderUniformRootLayout.magic==BigInt("47555201",16))
    assert(ShaderUniformBindingLayout.magic==BigInt("47554201",16))
    assert(ShaderUniformLimits.defaultComponents==Seq(1024,1024,1024,1024))
    opengpu.elaboration.EmitGeometryUniformAbi.main(Array("--check"))
    EmitNativeUniformAbi.main(Array("--check"))
  }

}
