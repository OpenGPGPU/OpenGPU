package opengpu.graphics

import chisel3._
import chisel3.util._

/** GXI1 optional image-view extension. Version0 is the original all-zero
  * reserved encoding; version1 selects RGBA/ZERO/ONE independently.
  * Capture with image admission and apply only after decode/filtering.
  */
class TextureImageSwizzleState extends Bundle {
  val version=UInt(2.W)
  val r=UInt(3.W);val g=UInt(3.W);val b=UInt(3.W);val a=UInt(3.W)
}
object TextureImageSwizzle {
  val red=0;val green=1;val blue=2;val alpha=3;val zero=4;val one=5
  def selectors(s: TextureImageSwizzleState): Seq[UInt]=Seq(s.r,s.g,s.b,s.a)
  def legal(s: TextureImageSwizzleState): Bool=
    (s.version===0.U && !Cat(selectors(s)).orR) || (s.version===1.U && selectors(s).map(_<=one.U).reduce(_ && _))
  def apply(rgba: Vec[UInt],kind: UInt,s: TextureImageSwizzleState): Vec[UInt]={
    require(rgba.length==4 && rgba.forall(_.getWidth==32))
    val typedOne=Mux(kind===0.U,"h3f800000".U(32.W),1.U(32.W))
    VecInit(selectors(s).zipWithIndex.map{case(selector,i)=>
      Mux(s.version===0.U,rgba(i),MuxLookup(selector,0.U(32.W))(
        (0 until 4).map(c=>c.U->rgba(c))++Seq(zero.U->0.U(32.W),one.U->typedOne)))
    })
  }
}
