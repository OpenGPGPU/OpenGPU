package opengpu.core

/** Local qualification for the full 16-lane, eight-warp compute profile. */
class GpuComputeUnitStorageFullProfileSpec extends GpuComputeUnitStorageSpec {
  override protected def fullProfile: Boolean = true
}
