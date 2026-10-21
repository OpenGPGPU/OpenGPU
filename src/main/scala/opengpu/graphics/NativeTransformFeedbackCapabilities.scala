package opengpu.graphics

object NativeTransformFeedbackCapabilities {
  val base=0x260;val wordCount=8;val end=base+wordCount*4
  val magic=BigInt("47464301",16) // GFC1
  val opcode=12;val activeObjects=8
  val rasterDiscardFlag=3;val captureFlag=4
  def words(sourceWords: Int): Seq[BigInt]={
    require(sourceWords>=9 && sourceWords<=69)
    Seq(magic,wordCount,BigInt(opcode)|(BigInt(1)<<8)|(BigInt(rasterDiscardFlag)<<16)|(BigInt(16)<<24),
      NativeTransformFeedbackRecord.magic,NativeTransformFeedbackRecord.bytes,
      BigInt(activeObjects)|(BigInt(4)<<8)|(BigInt(64)<<16)|(BigInt(4)<<24),sourceWords,BigInt(255))
  }
}
