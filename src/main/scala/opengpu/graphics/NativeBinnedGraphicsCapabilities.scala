package opengpu.graphics

import chisel3.util.log2Ceil

/** GNC2 discovers virtual-image native jobs; GNC1 retains the physical profile.
  * Wire-record capacities remain
  * fixed; these words report the implemented runtime capacities separately.
  */
object NativeBinnedGraphicsCapabilities {
  val magic=BigInt("474e4302",16)
  val physicalMagic=BigInt("474e4301",16)
  val wordCount=8
  case class Field(name: String,word: Int,shift: Int,bits: Int,unit: String){val mask=((BigInt(1)<<bits)-1)<<shift}
  val fields=Seq(Field("MAGIC",0,0,32,"GNC2: virtual images; GNC1: trusted physical images"),Field("WORD_COUNT",1,0,32,"words"),Field("JOB_MAGIC",2,0,32,"GNJ1"),
    Field("JOB_BYTES",3,0,16,"bytes"),Field("FRAME_BYTES",3,16,16,"bytes"),Field("DRAW_BYTES",4,0,16,"bytes"),
    Field("ATTRIBUTES",4,16,5,"slots"),Field("BINDINGS",4,21,5,"slots"),Field("VARYINGS",4,26,5,"vec4 slots"),
    Field("COMPLETION_INFO",4,31,1,"GNX1 completion detail registers present"),
    Field("FRAME_WIDTH",5,0,16,"pixels"),Field("FRAME_HEIGHT",5,16,16,"pixels"),Field("STORAGE_BYTES",6,0,17,"resident bytes per engine"),
    Field("SUBPIXEL_BITS",6,17,4,"bits"),Field("COLORS",6,21,3,"locations"),Field("SAMPLES",6,24,3,"samples"),
    Field("ENGINES",6,27,3,"engines"),Field("TILE8",6,30,1,"supported"),Field("TILE16",6,31,1,"supported"),
    Field("CUS",7,0,8,"CUs"),Field("NATIVE_ID_BITS",7,8,4,"bits"),Field("PAYLOAD_OWNERS",7,12,3,"owners"),
    Field("LOCAL_PAYLOAD",7,15,1,"enabled"),Field("PAYLOAD_CAPACITY_LOG2",7,16,5,"log2 bytes; zero if absent"),
    Field("PAYLOAD_SLOT_LINES",7,21,11,"64-byte lines; zero if absent"))
  def words(n: NativeBinnedGraphicsConfig,cus: Int,g: GraphicsConfig): Seq[BigInt]={
    val render=n.renderGraphics(g)
    require(cus>0 && cus<256 && g.subPixelBits>=0 && g.subPixelBits<16 && g.maxSampleCount<8)
    require(g.screenWidth>0 && g.screenWidth<65536 && g.screenHeight>0 && g.screenHeight<65536)
    n.payload.foreach(q=>require(q.owners<8 && q.slotLines<2048,"GNC1 payload fields must represent the configured pool"))
    Seq(if(n.translateImageViews)magic else physicalMagic,BigInt(wordCount),NativeBinnedGraphicsLayout.jobMagic,
      BigInt(NativeBinnedGraphicsLayout.jobBytes)|(BigInt(NativeBinnedGraphicsLayout.frameBytes)<<16),
      BigInt(NativeBinnedGraphicsLayout.drawBytes)|(BigInt(n.vertex.input.attributes)<<16)|(BigInt(n.vertex.input.bindings)<<21)|(BigInt(n.vertex.varyings)<<26)|(BigInt(1)<<31),
      BigInt(render.screenWidth)|(BigInt(render.screenHeight)<<16),
      BigInt(n.storage.bytes)|(BigInt(g.subPixelBits)<<17)|(BigInt(n.fragment.colors)<<21)|(BigInt(g.maxSampleCount)<<24)|
        (BigInt(n.engines)<<27)|(BigInt(3)<<30),
      BigInt(cus)|(BigInt(log2Ceil(MemoryNativeBinnedGraphicsPipeline.transactions(n)))<<8)|
        (BigInt(n.payload.map(_.owners).getOrElse(0))<<12)|(if(n.payload.nonEmpty)BigInt(1)<<15 else BigInt(0))|
        (BigInt(n.payload.map(q=>log2Ceil(q.capacityBytes)).getOrElse(0))<<16)|(BigInt(n.payload.map(_.slotLines).getOrElse(0))<<21))
  }
}
