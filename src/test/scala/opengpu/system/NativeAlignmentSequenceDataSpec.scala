package opengpu.system

import opengpu.testutil.{NativeFrameMemoryCodec,PagedFrameMemory}
import org.scalatest.flatspec.AnyFlatSpec

class NativeAlignmentSequenceDataSpec extends AnyFlatSpec {
  it should "keep independent compute epochs and detect every read-only or sparse-page corruption" in {
    val initial=new PagedFrameMemory
    NativeAlignmentSequenceData.seed(initial)
    for(epoch<-0 until 2) {
      assert(NativeAlignmentSequenceData.program(epoch).size==16)
      assert(initial.readWord(NativeAlignmentSequenceData.pc(epoch)+60)==BigInt("30500073",16))
    }
    val first=NativeAlignmentSequenceData.completed(initial,1)
    val second=NativeAlignmentSequenceData.completed(initial,2)
    assert(first.readWord(0x20000)==3 && first.readWord(0x207fc)==133)
    assert(first.readWord(0x30000)==NativeAlignmentSequenceData.guard)
    assert(second.readWord(0x30000)==11 && second.readWord(0x307fc)==141)
    assert(second.readWord(0x21000)==BigInt("40000000",16))
    assert(second.readWord(0x31000)==BigInt("40c00000",16))
    assert(NativeFrameMemoryCodec.unchangedOutside(first,first.snapshot(),Nil))
    for(address<-Seq(0x1000L,0x1fffcL,0x20000L,0x21004L,0x30000L,0x40000L,0x6ffffL,0x80000L)) {
      val changed=first.snapshot();changed.writeByte(address,changed.readByte(address)^1)
      assert(!NativeFrameMemoryCodec.unchangedOutside(first,changed,Nil),address.toHexString)
    }
    val allowed=first.snapshot();allowed.writeWord(0x30000,0)
    assert(NativeFrameMemoryCodec.unchangedOutside(first,allowed,Seq(0x30000L->0x30004L)))
    assert(!NativeFrameMemoryCodec.unchangedOutside(first,allowed,Seq(0x30000L->0x30003L)))
    val metadata=first.snapshot();metadata.mapRange(0x80000,0x81000,0)
    assert(!NativeFrameMemoryCodec.unchangedOutside(first,metadata,Nil))
    intercept[IllegalArgumentException](NativeAlignmentSequenceData.seed(initial))
    intercept[IllegalArgumentException](NativeAlignmentSequenceData.completed(initial,0))
  }
}
