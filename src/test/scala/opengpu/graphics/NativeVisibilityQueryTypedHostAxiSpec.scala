package opengpu.graphics

class NativeVisibilityQueryTypedHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def visibilityQueries: Boolean=true
  override protected def groupedTexture: Boolean=true
  override protected def nativeComputeUnits: Int=4
  override protected def capturedFloatingFormats: Seq[Int]=Seq(3)
  override protected def capturedDefaultFormat: Int=3
}
