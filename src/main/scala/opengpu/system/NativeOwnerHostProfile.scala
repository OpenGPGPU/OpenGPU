package opengpu.system

/** Full protected-source factory for captured Mesa/Linux native submissions.
  * The existing v2 product profile remains its separate frozen 72-word target.
  * These quantities describe connected hardware; correctness, clock and FPS
  * qualification require a fresh complete DUT generated from this factory.
  */
object NativeOwnerHostProfile {
  val id = "two-cu-vga60-owner-v1"
  val targetFps = 60
  val target: NativeAlignmentHostConfig = {
    val base = NativeAlignmentHostProfile.target
    base.copy(native = base.native.copy(storage = base.native.storage.copy(
      advancedBlend = true, nativeAdvancedDraws = true)), nativeProtectedSources = true)
  }
  require(target.requiredDiscovery.size == 96 &&
    target.requiredDiscovery.toMap.apply(0x368) == 0x3ffff,
    "captured-owner factory requires all protected native discovery words")
}
