package opengpu.graphics

import opengpu.config.GpuConfig
import opengpu.system.{TessellationNativeJobLayout,TessellationNativeTriangleJobLayout,TessellationNativeSequenceLayout,
  TessellationNativeTriangleSequenceLayout,TessellationPatchSequenceDescriptor,TessellationTrianglePatchSequenceDescriptor,
  TessellationNativeQuadJobLayout,TessellationNativeQuadSequenceLayout,TessellationQuadPatchSequenceDescriptor}
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.sys.process._

class TessellationNativeHostDomainFixtureSpec extends AnyFlatSpec {
  it should "prepare default-capacity owned texture snapshots without simulation" in {
    val healthy=TessellationOwnedTextureHostFixture.prepare(fresh=false)
    val fresh=TessellationOwnedTextureHostFixture.prepare(fresh=true)
    TessellationOwnedTextureHostFixture.checkCpuOracles(healthy)
    TessellationOwnedTextureHostFixture.checkCpuOracles(fresh)
    assert(healthy.textureBits!=fresh.textureBits && healthy.uniformBits!=fresh.uniformBits && healthy.baseTextureBits!=fresh.baseTextureBits)
    assert(healthy.textureLevelVa==fresh.textureLevelVa && healthy.textureLevelDma==fresh.textureLevelDma)
    assert(healthy.inputHashes.keySet==fresh.inputHashes.keySet && healthy.inputHashes!=fresh.inputHashes)
  }
  it should "construct distinct host job records with patchable point topology" in {
    val iso=TessellationNativeHostFixture.build();val tri=TessellationNativeTriangleHostFixture.build();val quad=TessellationNativeQuadHostFixture.build()
    assert(TessellationNativeQuadHostFixture.build(withoutSimulation=true).toMap==quad.toMap,
      "pure quad serialization differs from the actual Echo fixture")
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

  it should "serialize the quad rooted fixture without simulation" in {
    val fixture=TessellationNativeQuadHostFixture.build(withoutSimulation=true)
    val owned=TessellationRootedHostFixture.prepare(fixture,BigInt(0x3f400000L),BigInt(0x3f000000L),ownedUniforms=true)
    // Golden SHA-256s of all seven inputs from the original real Echo-generated
    // quad fixture. Includes zero padding and every field, not only active ones.
    val echoHashes=Map(
      "job.bin"->"4bec3d7f325edce1044617234d323b6c150e9ca45148ea287a803694c8e56017",
      "frame.bin"->"1083f71e12797114091a95625552a6df2a194cf8d848dbf9e70bf5952b51aa32",
      "draws.bin"->"b06849392e81345b87ead5f1d94df995772f2b0d06d0663e29703de249923fe0",
      "states.bin"->"533d582468dce5c744fa6a6ed3cf94c96642e0719d5c359699c579039ac63691",
      "code.bin"->"114875913637d8d133e371147130096f7b92ed4b4623ab36604b5ebf9dd06ab7",
      "patch.bin"->"901c6d85fd03334cbf9b7eab6d98a9c5b257a629e666922d4bec3125d00ba627",
      "resources.bin"->"01b428fe398db2f04057e380b0d46d01eaac2d9ee74b2b184648ee5723a6e245")
    assert(owned.inputHashes==echoHashes,s"pure fixture differs from original Echo input: ${owned.inputHashes}")
    assert(owned.uniformOwned && owned.uniformRootDma==owned.jobDma+512 && owned.uniformBindingDma==owned.jobDma+576)
    assert(owned.expectedPixel==Vector(191,128,0,255) && owned.clearPixel==Vector(26,51,77,102))
  }

  it should "decode actual host job snapshots for all three domains" in {
    assert(TessellationRootedHostFixture.isolineCoverage(GraphicsConfig(screenWidth=4,screenHeight=4))==Set((2,1),(3,1)))
    val executable=Files.createTempFile("opengpu-tessellation-fixture-", ".test")
    val record=Files.createTempFile("opengpu-tessellation-fixture-", ".bin")
    val frame=Files.createTempFile("opengpu-tessellation-frame-", ".bin")
    val draw=Files.createTempFile("opengpu-tessellation-draw-", ".bin")
    val states=Files.createTempFile("opengpu-tessellation-states-", ".bin")
    try {
      assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror",
        "driver/tests/opengpu_tessellation_job_fixture_test.c","-o",executable.toString).! == 0)
      val fixtures=Seq(TessellationNativeHostFixture.build(),TessellationNativeTriangleHostFixture.build(),
        TessellationNativeQuadHostFixture.build())
      val resources=Seq((BigInt(0x3f000000L),BigInt(0x3e800000L)),
        (BigInt(0x3f400000L),BigInt(0x3e800000L)),(BigInt(0x3f400000L),BigInt(0x3f000000L)))
      for((fixture,domain)<-fixtures.zipWithIndex){
        val bytes=Array.tabulate[Byte](512)(i=>fixture.getOrElse(BigInt(0x8d0000L+i),0).toByte)
        Files.write(record,bytes)
        Files.write(frame,Array.tabulate[Byte](2048)(i=>fixture.getOrElse(BigInt(0x8d1000L+i),0).toByte))
        Files.write(draw,Array.tabulate[Byte](2048)(i=>fixture.getOrElse(BigInt(0x8e0000L+i),0).toByte))
        Files.write(states,Array.tabulate[Byte](1024)(i=>fixture.getOrElse(BigInt(0x8000L+i),0).toByte))
        assert(Seq(executable.toString,record.toString,domain.toString,frame.toString,draw.toString).! == 0,s"domain $domain snapshot rejected")
        assert(Seq("python3","scripts/test_tessellation_schedule.py",record.toString,frame.toString,draw.toString,states.toString).! == 0,
          s"domain $domain scheduler snapshot rejected")
        val rooted=TessellationRootedHostFixture.prepare(fixture,resources(domain)._1,resources(domain)._2)
        assert(rooted.regions.map(_.name).toSet.contains("root-data"))
        assert(rooted.expectedPixel.take(2)==Seq(if(domain==0)128 else 191,if(domain==2)128 else 64))
        val owned=TessellationRootedHostFixture.prepare(fixture,resources(domain)._1,resources(domain)._2,ownedUniforms=true)
        assert(owned.uniformOwned && owned.uniformRootVa==owned.jobVa+512 && owned.uniformRootDma==owned.jobDma+512)
        assert(owned.uniformBindingDma==owned.jobDma+576 && owned.expectedPixel==rooted.expectedPixel)
        for(name<-Seq("root-data","code","patch-data"))
          assert(owned.regions.find(_.name==name).get.bytes.sameElements(rooted.regions.find(_.name==name).get.bytes))
      }
    } finally {Files.deleteIfExists(states);Files.deleteIfExists(draw);Files.deleteIfExists(frame);Files.deleteIfExists(record);Files.deleteIfExists(executable)}
  }
}
