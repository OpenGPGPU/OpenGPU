package opengpu.graphics

private[graphics] object TileBinJobTestData {
  val descriptorBase=0x90000L
  val appendBase=0xa0000L
  case class Ref(tile: Long,primitive: Long,state: Long,sequence: Long,token: Long) {
    def words: Vector[Long]=Vector(tile,primitive,state,sequence,token,0,0,0)
  }
  def references(count: Int): Vector[Ref]=Vector.tabulate(count)(i=>Ref(i%2,
    TileBinRenderTestData.firstHandle+(i%3)*2,TileBinRenderTestData.firstHandle+32,i/2+1,100+i))
  def descriptor(count: Int=3,programmable: Boolean=true,mode: Int=0,resolve: Boolean=false): Vector[Long] = {
    import TileBinRenderTestData._
    val w=Array.fill[Long](128)(0)
    w(0)=TileBinJobMemoryLayout.magic.toLong; w(1)=128; w(2)=3; w(3)=7; w(4)=11
    w(5)=appendBase; w(6)=appendBase+((count.toLong+1)/2)*64; w(8)=count; w(9)=3
    w(10)=16|(8L<<16); w(11)=w(10); w(13)=3|(mode.toLong<<4)|(if (resolve) 1L<<16 else 0L)
    w(14)=2; w(15)=firstHandle; w(16)=binBase; w(17)=binBase+256; w(21)=1; w(22)=7
    w(23)=0x80000; w(24)=0x80080; w(26)=recordBase; w(27)=recordBase+4096
    if (programmable) { w(29)=0xb0000; w(30)=0xb8000; w(32)=0x4000 }
    for ((i,base,clear) <- Seq((33,colorBase,colorClear),(39,depthBase,depthClear))) {
      w(i)=base; w(i+1)=base+16*8*4L*(1<<mode); w(i+3)=16*4*(1<<mode); w(i+4)=5; w(i+5)=clear
    }
    w(45)=resolveBase; w(46)=resolveBase+512; w(48)=64
    w.toVector
  }
}
