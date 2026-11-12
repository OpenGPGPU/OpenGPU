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
}
