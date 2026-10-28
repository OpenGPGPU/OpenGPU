package opengpu.graphics

/** Sparse geometry, but a complete admitted target: every stored background,
  * color/depth sample and pitched guard must be verified after GPU execution.
  */
object NativeApiBoundaryFrame {
  import NativeProductWorkloads._
  final case class Inputs(scene: Scene,scissors: Map[Int,NativeProductFrameReference.Scissor])
  def apply(samples: Int=1): Inputs = {
    require(Set(1,4)(samples))
    def vertex(x: Int,y: Int): Vertex=Vertex(2f*x/2048-1f,2f*y/2048-1f,0.5f,1f)
    val a=vertex(2046,2046);val b=vertex(2048,2046);val c=vertex(2046,2048);val d=vertex(2048,2048)
    val draw=Draw(Vector(Triangle(a,b,c),Triangle(d,c,b)),Vector(1f,0f,0f,1f),depthTest=true,depthWrite=true)
    Inputs(Scene("native-api-extent",2048,2048,samples,Vector(draw)),
      Map(0->NativeProductFrameReference.Scissor(2047,2047,2048,2048)))
  }
}
