package opengpu.graphics

/** GNC2 runs the complete native host contract with VM-addressed images. */
class NativeBinnedGraphicsVirtualImageHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def virtualImages: Boolean=true
}
