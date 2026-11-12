package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class NativeWideTileGridEncodingSpec extends AnyFlatSpec {
  it should "combine independent native bounds with the complete 2048-axis eight-pixel grid" in {
    val display=GraphicsConfig(screenWidth=640,screenHeight=480)
    val native=NativeBinnedGraphicsConfig.fourEngine.copy(
      renderTargetExtent=Some((2048,2048)),pagedCapacity=65536)
    val render=native.renderGraphics(display)
    assert(((render.screenWidth+7)/8)*((render.screenHeight+7)/8)==native.pagedCapacity)
    val words=NativeBinnedGraphicsCapabilities.words(native,4,display)
    assert(words.head==NativeBinnedGraphicsCapabilities.wideMagic)
    assert(words(5)==(BigInt(2048)|(BigInt(2048)<<16)))
    assert(display.screenWidth==640 && display.screenHeight==480)
  }

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

  it should "combine the 720p display with the complete native API capacity" in {
    // This is configuration/discovery arithmetic; it does not render a frame.
    val display=GraphicsConfig(screenWidth=1280,screenHeight=720)
    val legacy=NativeBinnedGraphicsConfig.fourEngine
    val combined=NativeBinnedGraphicsConfig.fourEngineApiExtent
    val render=combined.renderGraphics(display)
    assert((display.screenWidth,display.screenHeight)==((1280,720)))
    assert((render.screenWidth,render.screenHeight)==((2048,2048)))
    assert(combined.pagedCapacity==65536 && combined.engines==4)
    assert(combined.payload==legacy.payload && combined.storage==legacy.storage)
    val words=NativeBinnedGraphicsCapabilities.words(combined,4,display)
    assert(words.head==NativeBinnedGraphicsCapabilities.wideMagic)
    assert(words(5)==(BigInt(2048)|(BigInt(2048)<<16)))
    assert(((render.screenWidth+7)/8)*((render.screenHeight+7)/8)==combined.pagedCapacity)
  }
}
