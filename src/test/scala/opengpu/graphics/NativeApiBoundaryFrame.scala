package opengpu.graphics

/** Sparse geometry, but a complete admitted target: every stored background,
  * color/depth sample and pitched guard must be verified after GPU execution.
  */
object NativeApiBoundaryFrame {
  import NativeProductWorkloads._
  final case class Inputs(scene: Scene,scissors: Map[Int,NativeProductFrameReference.Scissor])
  def apply(samples: Int=1,width: Int=2048,height: Int=2048): Inputs = {
    require(Set(1,4)(samples))
    require(width>=2 && width<=2048 && height>=2 && height<=2048)
    def vertex(x: Int,y: Int): Vertex=Vertex(2f*x/width-1f,2f*y/height-1f,0.5f,1f)
    val a=vertex(width-2,height-2);val b=vertex(width,height-2)
    val c=vertex(width-2,height);val d=vertex(width,height)
    val draw=Draw(Vector(Triangle(a,b,c),Triangle(d,c,b)),Vector(1f,0f,0f,1f),depthTest=true,depthWrite=true)
    Inputs(Scene("native-api-extent",width,height,samples,Vector(draw)),
      Map(0->NativeProductFrameReference.Scissor(width-1,height-1,width,height)))
  }
}
