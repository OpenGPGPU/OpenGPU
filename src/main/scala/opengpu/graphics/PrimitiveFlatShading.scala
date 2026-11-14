package opengpu.graphics

import chisel3._

/** The original provoking-vertex rule, shared by direct mapping and post-TF export. */
object PrimitiveFlatShading {
  def vertex(index:UInt,provoking:UInt,flat:Bool):UInt=Mux(flat,provoking,index)
  def apply(in:AssembledPrimitive,p:VertexShaderStageConfig):AssembledPrimitive={
    val out=WireDefault(in)
    for(v<-0 until 3;n<-0 until p.varyings;c<-0 until 4){
      val source=vertex(v.U,in.provokingVertex,v.U<in.vertexCount && in.flatMask(n*4+c))
      out.vertices(v).varyings(n)(c):=in.vertices(source).varyings(n)(c)
    }
    out
  }
}
