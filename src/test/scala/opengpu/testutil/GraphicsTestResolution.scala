package opengpu.testutil

/** Routine frame workloads stay at VGA or below. Full product-resolution
  * execution is explicit and never enabled by the regular CI workflows.
  */
object GraphicsTestResolution {
  private val requested = sys.props.get("opengpu.productQualification")
  require(requested.forall(Set("0", "1")), "opengpu.productQualification must be 0 or 1")
  val productQualification: Boolean = requested.contains("1")
  val width: Int = if (productQualification) 1280 else 640
  val height: Int = if (productQualification) 720 else 480
  val label: String = s"${width}x${height}"
  def tileColumns(edge: Int): Int = (width + edge - 1) / edge
  def tileCount(edge: Int): Int = tileColumns(edge) * ((height + edge - 1) / edge)
}
