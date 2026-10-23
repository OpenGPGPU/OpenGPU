package opengpu.graphics

/** Full native renderer and translated host, with production thread/interface capacities. */
class NativeUniformRendererCapacityHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def uniformRendererCapacity: Boolean=true
}
