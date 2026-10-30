package opengpu.system

/** Bounded direct/indirect resource coexistence including compute textures. */
class GpuSystemTextureResourceCoexistenceSpec extends GpuSystemResourceCoexistenceSpec {
  override protected def textureEnabled: Boolean = true
}
