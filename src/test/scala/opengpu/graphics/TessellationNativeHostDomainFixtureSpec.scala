package opengpu.graphics

import opengpu.config.GpuConfig
import opengpu.system.{TessellationNativeJobLayout,TessellationNativeTriangleJobLayout,TessellationNativeSequenceLayout,
  TessellationNativeTriangleSequenceLayout,TessellationPatchSequenceDescriptor,TessellationTrianglePatchSequenceDescriptor,
  TessellationNativeQuadJobLayout,TessellationNativeQuadSequenceLayout,TessellationQuadPatchSequenceDescriptor}
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.sys.process._

class TessellationNativeHostDomainFixtureSpec extends AnyFlatSpec {
  it should "construct distinct host job records with patchable point topology" in {
    val iso=TessellationNativeHostFixture.build();val tri=TessellationNativeTriangleHostFixture.build();val quad=TessellationNativeQuadHostFixture.build()
    def word(bytes:scala.collection.mutable.Map[BigInt,Int],address:BigInt):BigInt=
      (0 until 4).foldLeft(BigInt(0))((v,i)=>v | (BigInt(bytes.getOrElse(address+i,0)) << (8*i)))
    val name="template.source.viewport.system.patch.control.patch.pointMode"
    val isoFields=TessellationNativeSequenceLayout.fields(new TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
    val triFields=TessellationNativeTriangleSequenceLayout.fields(new TessellationTrianglePatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
    val quadFields=TessellationNativeQuadSequenceLayout.fields(new TessellationQuadPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
    val isoPoint=isoFields.find(_.name==name).get;val triPoint=triFields.find(_.name==name).get
    assert(isoPoint.width==1 && triPoint.width==1)
    assert(word(iso,0x8d0000)==TessellationNativeJobLayout.magic)
    assert(word(tri,0x8d0000)==TessellationNativeTriangleJobLayout.magic)
    assert(word(quad,0x8d0000)==TessellationNativeQuadJobLayout.magic)
    assert(word(iso,0x8e0000)==TessellationNativeSequenceLayout.magic && word(iso,0x8e0008)==1)
    assert(word(tri,0x8e0000)==TessellationNativeTriangleSequenceLayout.magic && word(tri,0x8e0008)==TessellationNativeTriangleSequenceLayout.version)
    assert(word(quad,0x8e0000)==TessellationNativeQuadSequenceLayout.magic && word(quad,0x8e0008)==TessellationNativeQuadSequenceLayout.version)
    assert(quadFields.find(_.name==name).exists(_.width==1))
    assert(word(iso,0x8e0000+isoPoint.word*4)==0 && word(tri,0x8e0000+triPoint.word*4)==0)
    assert(word(iso,0x1000+6*4)!=word(tri,0x1000+6*4),"control programs must select their domain-specific level writes")
    val controlName="template.source.viewport.system.patch.control.kernel.kernelPc"
    val controlWords=Seq(isoFields.find(_.name==controlName).get.word,
      triFields.find(_.name==controlName).get.word,quadFields.find(_.name==controlName).get.word)
    val fixtures=Seq(iso,tri,quad)
    for ((fixture,pc)<-fixtures.zip(controlWords)) {
      assert(word(fixture,0x8e0000+pc*4)==0x1000,"each domain must reuse the same control-program address")
    }
    val programs=fixtures.map(fixture=>(0 until 256).map(i=>fixture.getOrElse(BigInt(0x1000)+i,0)))
    assert(programs.distinct.size==3,"each domain must provide a different control program")
  }

  it should "decode actual host job snapshots for all three domains" in {
    val executable=Files.createTempFile("opengpu-tessellation-fixture-", ".test")
    val record=Files.createTempFile("opengpu-tessellation-fixture-", ".bin")
    val frame=Files.createTempFile("opengpu-tessellation-frame-", ".bin")
    val draw=Files.createTempFile("opengpu-tessellation-draw-", ".bin")
    try {
      assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror",
        "driver/tests/opengpu_tessellation_job_fixture_test.c","-o",executable.toString).! == 0)
      val fixtures=Seq(TessellationNativeHostFixture.build(),TessellationNativeTriangleHostFixture.build(),
        TessellationNativeQuadHostFixture.build())
      for((fixture,domain)<-fixtures.zipWithIndex){
        val bytes=Array.tabulate[Byte](512)(i=>fixture.getOrElse(BigInt(0x8d0000L+i),0).toByte)
        Files.write(record,bytes)
        Files.write(frame,Array.tabulate[Byte](2048)(i=>fixture.getOrElse(BigInt(0x8d1000L+i),0).toByte))
        Files.write(draw,Array.tabulate[Byte](2048)(i=>fixture.getOrElse(BigInt(0x8e0000L+i),0).toByte))
        assert(Seq(executable.toString,record.toString,domain.toString,frame.toString,draw.toString).! == 0,s"domain $domain snapshot rejected")
        assert(Seq("python3","scripts/test_tessellation_schedule.py",record.toString,frame.toString,draw.toString).! == 0,
          s"domain $domain scheduler snapshot rejected")
      }
    } finally {Files.deleteIfExists(draw);Files.deleteIfExists(frame);Files.deleteIfExists(record);Files.deleteIfExists(executable)}
  }
}
