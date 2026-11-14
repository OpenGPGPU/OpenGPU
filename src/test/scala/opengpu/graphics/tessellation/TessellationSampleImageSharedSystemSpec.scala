package opengpu.graphics.tessellation

import org.scalatest.DoNotDiscover

/** Actual control/evaluation and ABI6 fragment execution on two full-capacity
  * CUs, with 16 varying slots, four raw MRTs and 1x/2x/4x images. This bounded
  * 4x4 physical-memory component test is not full protected-host qualification.
  */
@DoNotDiscover
class TessellationSampleImageSharedSystemSpec extends TessellationSystemIsolineImageSharedSystemSpec {
  override protected def sampleImageQualification:Boolean=true
}
