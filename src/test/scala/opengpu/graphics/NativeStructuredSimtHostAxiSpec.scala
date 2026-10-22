package opengpu.graphics

class NativeTypedStructuredSimtHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def shaderStructuredSimt: Boolean=true
}
class NativeBinnedStructuredSimtHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def shaderStructuredSimt: Boolean=true
}
class NativeDefaultStructuredSimtHostAxiSpec extends NativeDefaultProfileHostAxiSpec {
  override protected def structuredSimt: Boolean=true
}
