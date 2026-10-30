package opengpu.system

/** Local qualification with the mobile four-CU execution profile. */
class GpuSystemFullResourceCoexistenceSpec extends GpuSystemResourceCoexistenceSpec {
  override protected def fullProfile: Boolean = true
}
