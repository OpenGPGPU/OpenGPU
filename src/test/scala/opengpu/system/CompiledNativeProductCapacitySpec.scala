package opengpu.system

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.Files
import opengpu.graphics.{NativeProductFrameInputs, NativeProductWorkloads, TileBinHostRegs}
import org.scalatest.flatspec.AnyFlatSpec

/** Checks command encoding only; no frame is rendered. */
class CompiledNativeProductCapacitySpec extends AnyFlatSpec {
  private def resources(bytes: Array[Byte]): (Vector[(Long, Long)], Int) = {
    val input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    input.position(32) // magic, frame dimensions/sample/id and cycle limit
    val count=input.getInt()
    val words=Vector.fill(count)((input.getInt().toLong & 0xffffffffL,
      input.getInt().toLong & 0xffffffffL))
    (words,input.position())
  }

  it should "encode two-CU VGA commands with full API resources and two-CU discovery" in {
    val path=Files.createTempFile("native-two-cu-vga-", ".commands")
    try {
      val scene=NativeProductWorkloads.suite(16,16).head
      val input=CompiledNativeProductFixtures.input(scene,twoCuVga=true)
      CompiledNativeProductFixtures.writeCommands(path,scene,input,1,product=false,
        apiCapacity=true,twoCuVga=true)
      val (words,_)=resources(Files.readAllBytes(path))
      assert(words.size==24)
      assert(words(3)==(0x36cL,2L))
      assert((words(23)._2 & 255)==2)
      assert(((words(22)._2 >> 27) & 7)==4)
      assert(words(21)._2==0x08000800L)
      assert(input.frame("frame.version")==2)
      assert(input.frame("frame.bins.producer.tileLog2")==3)
      assert(CompiledNativeProductFixtures.frameSize(product=true,twoCuVga=true)==(640,480))
      assert(CompiledNativeProductFixtures.frameSize(product=false,twoCuVga=true)==(32,32))
      intercept[IllegalArgumentException] {
        CompiledNativeProductFixtures.writeCommands(path,scene,input,1,product=false,twoCuVga=true)
      }
    } finally Files.deleteIfExists(path)
  }

  it should "preserve product commands and add exact API-capacity discovery checks" in {
    val dir=Files.createTempDirectory("native-combined-capacity-")
    val old=dir.resolve("ordinary.commands");val combined=dir.resolve("combined.commands")
    try {
      val scene=NativeProductWorkloads.suite(16,16).head
      val input=NativeProductFrameInputs.encode(scene)
      CompiledNativeProductFixtures.writeCommands(old,scene,input,1,product=false,apiCapacity=false)
      CompiledNativeProductFixtures.writeCommands(combined,scene,input,1,product=false,apiCapacity=true)
      val original=Files.readAllBytes(old);val candidate=Files.readAllBytes(combined)
      val (oldWords,oldTail)=resources(original);val (newWords,newTail)=resources(candidate)
      assert(oldWords.size==16 && newWords.size==24)
      assert(newWords.take(16)==oldWords)
      assert(original.take(32).sameElements(candidate.take(32)))
      assert(original.drop(oldTail).sameElements(candidate.drop(newTail)))
      val native=newWords.drop(16)
      assert(native.map(_._1)==Vector.tabulate(8)(i=>(TileBinHostRegs.CAPABILITIES+i*4).toLong))
      assert(native.head._2==0x474e4303L) // GNC3, wide native payloads and translated views
      assert(native(1)._2==8L)
      assert(native(5)._2==0x08000800L) // 2048 by 2048 native maximum
    } finally {
      Files.deleteIfExists(old);Files.deleteIfExists(combined);Files.deleteIfExists(dir)
    }
  }

  it should "bind owner fixtures to all 96 words while preserving original scene commands" in {
    val dir=Files.createTempDirectory("native-owner-capacity-")
    val old=dir.resolve("v2.commands"); val owner=dir.resolve("owner.commands")
    try {
      val scene=NativeProductWorkloads.suite(16,16).head
      val input=CompiledNativeProductFixtures.input(scene,twoCuVga=true)
      CompiledNativeProductFixtures.writeCommands(old,scene,input,1,product=false,
        apiCapacity=true,twoCuVga=true,alignmentV2=true)
      CompiledNativeProductFixtures.writeCommands(owner,scene,input,1,product=false,
        apiCapacity=true,twoCuVga=true,nativeOwner=true)
      val original=Files.readAllBytes(old); val candidate=Files.readAllBytes(owner)
      val (oldWords,oldTail)=resources(original); val (newWords,newTail)=resources(candidate)
      assert(oldWords.size==72 && oldWords(2)._2==0x7fffL)
      assert(newWords==NativeOwnerHostProfile.target.requiredDiscovery.map { case (a,v) =>
        (a.toLong,v.toLong)
      })
      assert(newWords.size==96 && newWords(2)._2==0x3ffffL)
      assert(original.take(32).sameElements(candidate.take(32)))
      assert(original.drop(oldTail).sameElements(candidate.drop(newTail)))
      intercept[IllegalArgumentException] {
        CompiledNativeProductFixtures.writeCommands(owner,scene,input,1,product=false,
          apiCapacity=true,twoCuVga=true,alignmentV2=true,nativeOwner=true)
      }
    } finally {
      Files.deleteIfExists(old); Files.deleteIfExists(owner); Files.deleteIfExists(dir)
    }
  }

  it should "record explicit owner tiling without changing the full hardware capacity" in {
    for(log2<-Seq(3,4)) {
      val manifest=PrepareNativeOwnerHost.manifest(rtlEmitted=false,
        workload=Some((true,640,480)),workloadTileLog2=log2)
      assert(manifest.contains(s"\"workloadTileLog2\":$log2"))
      assert(manifest.contains("\"resourceDiscoveryWords\":96"))
      assert(manifest.contains("\"nativeMaxWidth\":2048"))
      assert(manifest.contains("\"workloadWidth\":640"))
      assert(manifest.contains("\"rtlEmitted\":false"))
      assert(!PrepareNativeOwnerHost.manifest(rtlEmitted=true).contains("workloadTileLog2"))
    }
    intercept[IllegalArgumentException] {
      PrepareNativeOwnerHost.manifest(rtlEmitted=false,workload=Some((false,32,32)),workloadTileLog2=5)
    }
  }
}
