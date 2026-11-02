package opengpu.graphics

import opengpu.config.GpuConfig
import opengpu.system.{TessellationNativeJobLayout,TessellationNativeTriangleJobLayout,TessellationNativeSequenceLayout,
  TessellationNativeTriangleSequenceLayout,TessellationPatchSequenceDescriptor,TessellationTrianglePatchSequenceDescriptor}
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeHostDomainFixtureSpec extends AnyFlatSpec {
  it should "construct distinct host job records with patchable point topology" in {
    val iso=TessellationNativeHostFixture.build();val tri=TessellationNativeTriangleHostFixture.build()
    def word(bytes:scala.collection.mutable.Map[BigInt,Int],address:BigInt):BigInt=
      (0 until 4).foldLeft(BigInt(0))((v,i)=>v | (BigInt(bytes.getOrElse(address+i,0)) << (8*i)))
    val name="template.source.viewport.system.patch.control.patch.pointMode"
    val isoFields=TessellationNativeSequenceLayout.fields(new TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
    val triFields=TessellationNativeTriangleSequenceLayout.fields(new TessellationTrianglePatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
    val isoPoint=isoFields.find(_.name==name).get;val triPoint=triFields.find(_.name==name).get
    assert(isoPoint.width==1 && triPoint.width==1)
    assert(word(iso,0x8d0000)==TessellationNativeJobLayout.magic)
    assert(word(tri,0x8d0000)==TessellationNativeTriangleJobLayout.magic)
    assert(word(iso,0x8e0000)==TessellationNativeSequenceLayout.magic && word(iso,0x8e0008)==1)
    assert(word(tri,0x8e0000)==TessellationNativeTriangleSequenceLayout.magic && word(tri,0x8e0008)==TessellationNativeTriangleSequenceLayout.version)
    assert(word(iso,0x8e0000+isoPoint.word*4)==0 && word(tri,0x8e0000+triPoint.word*4)==0)
    assert(word(iso,0x1000+6*4)!=word(tri,0x1000+6*4),"control programs must select their domain-specific level writes")
  }
}
