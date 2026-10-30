package opengpu.system

/** Local qualification of texture coexistence with the four-CU mobile profile. */
class GpuSystemFullTextureResourceCoexistenceSpec extends GpuSystemResourceCoexistenceSpec {
  override protected def fullProfile: Boolean = true
  override protected def textureEnabled: Boolean = true
}
