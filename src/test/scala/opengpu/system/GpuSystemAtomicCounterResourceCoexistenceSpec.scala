package opengpu.system

/** Compact counter coexistence coverage for regular CI. */
class GpuSystemAtomicCounterResourceCoexistenceSpec extends GpuSystemResourceCoexistenceSpec {
  override protected def textureEnabled: Boolean = true
  override protected def counterEnabled: Boolean = true
}
