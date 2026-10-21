package opengpu.graphics

/** Real four-CU host, both shader stages, local/spilled payloads and Sv32.
  * The inherited focused floating matrix checks exact MRT/depth/resolve output,
  * cache publication, fallback formats, denied descriptors and healthy reuse.
  * Counter assertions require actual shared data and page-walk traffic.
  */
class SharedNativeTypedGraphicsHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def groupedTexture: Boolean=true
  override protected def nativeComputeUnits: Int=4
  override protected def capturedFloatingFormats: Seq[Int]=Seq(3,0,54)
  override protected def capturedDefaultFormat: Int=3
}
