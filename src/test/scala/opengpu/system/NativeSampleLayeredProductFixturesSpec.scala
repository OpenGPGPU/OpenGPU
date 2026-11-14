package opengpu.system

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path, Paths}
import opengpu.graphics.{NativeLayeredProductFrameInputs, NativeProductWorkloads}
import org.scalatest.flatspec.AnyFlatSpec
import scala.jdk.CollectionConverters._

/** Serialized inputs only. Original ABI1 recipes do not qualify sample builtins. */
class NativeSampleLayeredProductFixturesSpec extends AnyFlatSpec {
  private def temporary(body: Path => Unit): Unit = {
    val parent=Paths.get(".artifacts");Files.createDirectories(parent)
    val directory=Files.createTempDirectory(parent,"sample-layered-fixtures-")
    try body(directory) finally {
      val stream=Files.walk(directory)
      try stream.iterator().asScala.toVector.reverse.foreach(Files.delete)
      finally stream.close()
    }
  }
  private def parse(path: Path,sample: Boolean): Vector[Byte] = {
    val bytes=Files.readAllBytes(path)
    assert(new String(bytes.take(8),"US-ASCII")== (if(sample)"OGLAY002" else "OGLAY001"))
    val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);data.position(32)
    def word(): Long=data.getInt().toLong&0xffffffffL
    assert(word()== (if(sample)112 else 104))
    val discovery=Vector.fill(if(sample)112 else 104)((word(),word()))
    val base=OwnerResourceProfile.target.requiredDiscovery.map{case(address,value)=>(address.toLong,value.toLong)}
    assert(discovery.take(104)==(if(sample)base.updated(2,0x368L->0x1fffffL)else base))
    if(sample)assert(discovery.takeRight(8)==Vector[Long](0x47535301,8,1,31,192,0x4d041004,0x7ff,7)
      .zipWithIndex.map{case(value,index)=>(0x520L+4*index,value)})
    bytes.drop(data.position()).toVector
  }
  it should "select separate exact containers while retaining every original command and shader" in temporary { directory =>
    for(tile<-Seq(3,4);(scene,index)<-NativeProductWorkloads.suite(32,32).zipWithIndex){
      val input=NativeLayeredProductFrameInputs.encode(scene,tile)
      assert(input.states.forall(_("fragment.version")==1))
      val old=directory.resolve("old.commands");val sample=directory.resolve("sample.commands")
      NativeLayeredProductFixtures.writeCommands(old,scene,input,index+1,product=false)
      NativeLayeredProductFixtures.writeCommands(sample,scene,input,index+1,product=false,
        factory=NativeLayeredProductFixtures.Sample)
      assert(parse(old,sample=false)==parse(sample,sample=true))
      assert(Files.readAllBytes(old).slice(8,32).toVector==Files.readAllBytes(sample).slice(8,32).toVector)
    }
  }
  it should "produce identical original backing with a distinct sample factory manifest and verifier" in temporary { directory =>
    val previous=sys.props.get("opengpu.fullHostQualification")
    sys.props("opengpu.fullHostQualification")="1"
    try {
      val old=directory.resolve("old");val sample=directory.resolve("sample")
      Files.createDirectory(old);Files.createDirectory(sample)
      val args=Array("--smoke","--output",sample.toString,"--tile-log2","4")
      PrepareNativeLayeredProductFixtures.main(Array("--smoke","--output",old.toString,"--tile-log2","4"))
      PrepareNativeSampleLayeredProductFixtures.main(args)
      for(name<-NativeProductWorkloads.ids){
        assert(java.util.Arrays.equals(Files.readAllBytes(old.resolve(s"fixtures/$name.memory")),
          Files.readAllBytes(sample.resolve(s"fixtures/$name.memory"))))
        assert(parse(old.resolve(s"fixtures/$name.commands"),sample=false)==
          parse(sample.resolve(s"fixtures/$name.commands"),sample=true))
      }
      val manifest=Files.readString(sample.resolve("sample-layer-product-fixture-manifest.json"))
      assert(manifest==NativeLayeredProductFixtures.manifest(false,32,32,4,NativeLayeredProductFixtures.Sample))
      assert(manifest.startsWith(PrepareOwnerResourceSampleHost.manifest(false).trim.dropRight(1)))
      assert(manifest.contains("\"workloadContainerMagic\":\"OGLAY002\""))
      assert(manifest.contains("\"workloadFragmentVersion\":1,\"sampleBuiltinsQualified\":false"))
      assert(!Files.exists(sample.resolve("layer-product-fixture-manifest.json")))
      assert(!Files.exists(sample.resolve("rtl")))
      intercept[IllegalArgumentException](PrepareNativeSampleLayeredProductFixtures.main(args))
      intercept[AssertionError] {
        VerifyNativeSampleLayeredProduct.main(Array("--smoke","--output",sample.resolve("fixtures").toString,"--tile-log2","4"))
      }
    } finally previous match {
      case Some(value)=>sys.props("opengpu.fullHostQualification")=value
      case None=>sys.props.remove("opengpu.fullHostQualification")
    }
  }
}
