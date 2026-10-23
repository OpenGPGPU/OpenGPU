package opengpu.graphics

/** Full uniform/varying runtime with four CUs and mobile/default cache sizes.
  * The small typed renderer is not the complete two-engine binned product.
  */
class NativeUniformRendererFourCuHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def uniformRendererCapacity: Boolean=true
  override protected def uniformRendererFullCaches: Boolean=true
  override protected def nativeComputeUnits: Int=4
}
