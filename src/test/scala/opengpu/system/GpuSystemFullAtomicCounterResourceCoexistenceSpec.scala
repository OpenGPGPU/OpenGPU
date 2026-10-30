package opengpu.system

/** Local four-CU, 128-thread counter/resource coexistence qualification. */
class GpuSystemFullAtomicCounterResourceCoexistenceSpec extends GpuSystemResourceCoexistenceSpec {
  override protected def fullProfile: Boolean = true
  override protected def textureEnabled: Boolean = true
  override protected def counterEnabled: Boolean = true
}
