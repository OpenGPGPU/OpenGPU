package opengpu.graphics

import chisel3._
import chisel3.util._

class TextureCubeTap extends Bundle {val face=UInt(3.W);val x=UInt(14.W);val y=UInt(14.W)}
/** Remap one seamless cube border tap. Face order is +X,-X,+Y,-Y,+Z,-Z.
  * Edge orientations follow GLES3 table3.21. At a double-border corner choose
  * the current face's corner texel, one of the three available corner samples.
  * GLES3 permits this deterministic construction instead of their average.
  */
object TextureCubeTap {
  // Source codes: zero,last,x,last-x,y,last-y. Columns left,right,bottom,top.
  private val edges=Seq(
    Seq((4,1,4),(5,0,4),(2,1,3),(3,1,2)),
    Seq((5,1,4),(4,0,4),(2,0,2),(3,0,3)),
    Seq((1,4,0),(0,5,0),(5,3,0),(4,2,0)),
    Seq((1,5,1),(0,4,1),(4,2,1),(5,3,1)),
    Seq((1,1,4),(0,0,4),(2,2,1),(3,2,0)),
    Seq((0,1,4),(1,0,4),(2,3,0),(3,3,1)))
  def apply(face: UInt,x: SInt,y: SInt,size: UInt): TextureCubeTap={
    val n=size.zext;val last=size-1.U
    val edgeX=x<0.S || x>=n;val edgeY=y<0.S || y>=n
    val which=Mux(x<0.S,0.U,Mux(x>=n,1.U,Mux(y<0.S,2.U,3.U)))
    val table=VecInit(edges.map(row=>VecInit(row.map{case(f,a,b)=>Cat(f.U(3.W),a.U(3.W),b.U(3.W))})))
    val mapping=table(face)(which)
    val sources=VecInit(Seq(0.U(14.W),last,x.asUInt(13,0),last-x.asUInt(13,0),y.asUInt(13,0),last-y.asUInt(13,0)))
    val out=Wire(new TextureCubeTap);out.face:=face
    out.x:=Mux(x<0.S,0.U,Mux(x>=n,last,x.asUInt));out.y:=Mux(y<0.S,0.U,Mux(y>=n,last,y.asUInt))
    when(edgeX ^ edgeY){out.face:=mapping(8,6);out.x:=sources(mapping(5,3));out.y:=sources(mapping(2,0))}
    out
  }
}
