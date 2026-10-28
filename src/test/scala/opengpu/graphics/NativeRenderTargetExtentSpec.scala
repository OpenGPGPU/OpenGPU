package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

/** Configuration/discovery checks only; no frame is rendered here. */
class NativeRenderTargetExtentSpec extends AnyFlatSpec {
  private val display=GraphicsConfig(screenWidth=640,screenHeight=480)
  private val native=NativeBinnedGraphicsConfig.fourEngine

  it should "retain the configured display bounds when no native extent is supplied" in {
    assert(native.renderGraphics(display) eq display)
    assert(NativeBinnedGraphicsCapabilities.words(native,4,display)(5)==
      (BigInt(640)|(BigInt(480)<<16)))
  }

  it should "use independent native bounds consistently in rendering configuration and discovery" in {
    val candidate=native.copy(renderTargetExtent=Some((2048,2048)))
    val render=candidate.renderGraphics(display)
    assert(render==display.copy(screenWidth=2048,screenHeight=2048))
    assert(display.screenWidth==640 && display.screenHeight==480)
    val before=NativeBinnedGraphicsCapabilities.words(native,4,display)
    val after=NativeBinnedGraphicsCapabilities.words(candidate,4,display)
    assert(after(5)==(BigInt(2048)|(BigInt(2048)<<16)))
    assert(before.zipWithIndex.forall{case(word,index)=>index==5 || after(index)==word})
    assert(candidate.storage==native.storage && candidate.payload==native.payload)
  }

  it should "reject invalid dimensions and native maxima smaller than the display" in {
    for(extent<-Seq((0,2048),(2048,0),(-1,2048),(65536,2048),(2048,65536))){
      intercept[IllegalArgumentException]{native.copy(renderTargetExtent=Some(extent))}
    }
    for(extent<-Seq((639,480),(640,479))){
      val candidate=native.copy(renderTargetExtent=Some(extent))
      intercept[IllegalArgumentException]{candidate.renderGraphics(display)}
      intercept[IllegalArgumentException]{NativeBinnedGraphicsCapabilities.words(candidate,4,display)}
    }
  }
}
