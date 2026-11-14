package opengpu.graphics

import org.scalatest.DoNotDiscover

/** Actual mixed source, four full-capacity layered images and two real CUs.
  * This is a physical-memory integration fixture, not protected-host or Mesa
  * qualification. Original ESSL capture and complete product remain required.
  */
@DoNotDiscover
class NativeLayeredSamplePlaneSpec extends NativeLayeredFourImageSpec {
  override protected def samplePlaneQualification:Boolean=true
}
