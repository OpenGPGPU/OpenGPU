package opengpu.graphics

import chisel3._
import chisel3.util._

class NativeBinnedGraphicsHeader extends Bundle {
  val attributes=UInt(32.W);val bindings=UInt(32.W);val varyings=UInt(32.W)
  val colors=UInt(32.W);val engineViews=UInt(32.W)
}
class NativeBinnedGraphicsJob extends Bundle {
  val identity=new GraphicsWorkIdentity
  val frameBase=UInt(32.W);val frameLimit=UInt(33.W)
  val drawBase=UInt(32.W);val drawLimit=UInt(33.W);val drawCount=UInt(32.W);val drawStride=UInt(32.W)
}
class NativeBinnedGraphicsJobRecord extends Bundle {
  val header=new NativeBinnedGraphicsHeader;val job=new NativeBinnedGraphicsJob
}
class NativeBinnedGraphicsFrameRecord extends Bundle {
  val header=new NativeBinnedGraphicsHeader;val frame=new TypedVertexBinImageDescriptor
}
class NativeBinnedGraphicsDrawRecord extends Bundle {
  val header=new NativeBinnedGraphicsHeader;val draw=new VertexBinDraw(VertexShaderStageConfig())
}

/** GBJ1/GBF1/GBD1 are separate from the existing GTJ1/GTI1/GTD1 ABI.
  * Fields use explicit little-endian 32-bit words, fixed 16-slot capacities,
  * canonical padding and line-contained multiword values. No Bundle packing
  * or reduced runtime capacity changes the published word offsets.
  */
object NativeBinnedGraphicsLayout {
  val version=1
  val jobMagic=BigInt("47424a01",16);val frameMagic=BigInt("47424601",16);val drawMagic=BigInt("47424401",16)
  val jobWords=128;val frameWords=512;val drawWords=512
  val jobBytes=jobWords*4;val frameBytes=frameWords*4;val drawBytes=drawWords*4
  private def headerFields(h: NativeBinnedGraphicsHeader): Seq[NativeTypedGraphicsLayout.Field]=
    Seq("attributes"->h.attributes,"bindings"->h.bindings,"varyings"->h.varyings,"colors"->h.colors,"engineViews"->h.engineViews)
      .zipWithIndex.map{case((name,value),i)=>NativeTypedGraphicsLayout.Field("header."+name,3+i,value)}
  private def payloadFields(prefix: String,d: Data): Seq[NativeTypedGraphicsLayout.Field]={
    var word=16
    WindowPrimitiveRecordLayout.leaves(d).map{case(name,value)=>
      val count=(value.getWidth+31)/32
      if(word%16+count>16)word=(word+15)/16*16
      val f=NativeTypedGraphicsLayout.Field(prefix+name,word,value);word+=count;f}
  }
  def jobFields(d: NativeBinnedGraphicsJobRecord): Seq[NativeTypedGraphicsLayout.Field]=headerFields(d.header)++payloadFields("job.",d.job)
  def frameFields(d: NativeBinnedGraphicsFrameRecord): Seq[NativeTypedGraphicsLayout.Field]=headerFields(d.header)++payloadFields("frame.",d.frame)
  def drawFields(d: NativeBinnedGraphicsDrawRecord): Seq[NativeTypedGraphicsLayout.Field]=headerFields(d.header)++payloadFields("draw.",d.draw)
  def headerLegal(h: NativeBinnedGraphicsHeader): Bool=
    h.attributes===16.U && h.bindings===16.U && h.varyings===16.U && h.colors===4.U && h.engineViews===4.U
  def fit(d: VertexBinDraw,p: VertexShaderStageConfig): Bool={
    val v=d.vertex.geometry.vertex;val i=v.input
    val active=i.attributes.zipWithIndex.map{case(a,n)=> !a.enabled || (n<p.input.attributes).B && a.bindingIndex<p.input.bindings.U}.reduce(_ && _)
    active && v.attributeSlots<=p.input.attributes.U && v.varyingSlots<=p.varyings.U &&
      !(v.varyingMask>>(v.varyingSlots<<2)).orR && !(d.vertex.geometry.flatMask>>(v.varyingSlots<<2)).orR
  }
}
