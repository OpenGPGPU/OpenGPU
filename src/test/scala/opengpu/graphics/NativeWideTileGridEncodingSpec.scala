package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class NativeWideTileGridEncodingSpec extends AnyFlatSpec {
  it should "discover wide frame payloads only in a 65536-tile native profile" in {
    val g=GraphicsConfig(screenWidth=640,screenHeight=480)
    for(virtual<-Seq(false,true)){
      val legacy=NativeBinnedGraphicsConfig.fourEngine.copy(translateImageViews=virtual)
      val before=NativeBinnedGraphicsCapabilities.words(legacy,4,g)
      val after=NativeBinnedGraphicsCapabilities.words(legacy.copy(pagedCapacity=65536),4,g)
      assert(before.head==(if(virtual)NativeBinnedGraphicsCapabilities.magic else NativeBinnedGraphicsCapabilities.physicalMagic))
      assert(after.head==(if(virtual)NativeBinnedGraphicsCapabilities.wideMagic else NativeBinnedGraphicsCapabilities.widePhysicalMagic))
      assert(before.tail==after.tail)
    }
  }
}
