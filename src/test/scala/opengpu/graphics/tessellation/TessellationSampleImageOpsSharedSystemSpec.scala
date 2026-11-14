package opengpu.graphics.tessellation

import org.scalatest.DoNotDiscover

/** Physical-memory image ISA qualification in a two-CU 128-thread-capacity
  * configuration. This does not prove both CUs execute image instructions,
  * concurrent fragment throughput, original ESSL or protected full-host work.
  */
@DoNotDiscover
class TessellationSampleImageOpsSharedSystemSpec extends TessellationSystemIsolineImageSharedSystemSpec {
  override protected def sampleImageQualification:Boolean=true
  override protected def sampleImageOpcodes:Boolean=true
}
