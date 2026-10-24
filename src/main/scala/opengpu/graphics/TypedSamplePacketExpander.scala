package opengpu.graphics

import chisel3._
import chisel3.util._

class TaggedTypedPixelQuad extends Bundle {
  val tag=UInt(16.W);val quad=new TypedTilePixelQuad
}
class TypedSamplePacket extends Bundle {
  val tag=UInt(16.W);val packet=UInt(2.W);val last=Bool();val cancelled=Bool()
  val active=UInt(4.W);val colorActive=UInt(4.W);val channelMask=UInt(4.W)
  val lanes=Vec(4,UInt(2.W));val samples=Vec(4,UInt(2.W));val indices=Vec(4,UInt(10.W))
  // Color pair0/1, then depth/stencil pair0/1; addresses are64-bit words.
  val words=Vec(4,UInt(32.W))
  val colors=Vec(4,Vec(4,UInt(32.W)));val depths=Vec(4,UInt(32.W))
  val frontFacing=Bool();val tests=new TypedLateTestState;val blend=new TypedBlendState
}
object TypedSamplePacketLayout {
  /** Subset selection follows the caller's ordinary descriptor validation. */
  def supported(d: TypedTilePixelDescriptor): Bool={
    val c=d.colors
    d.version===1.U && c.version===1.U && (c.tileLog2===3.U || c.tileLog2===4.U) && c.sampleMode<=2.U &&
      c.colors(0).enabled && c.colors(0).format===TypedColorFormat.id("RGBA8").U &&
      !VecInit(c.colors.drop(1).map(_.enabled)).asUInt.orR && d.depth.format===TypedDepthFormat.d24s8.U &&
      d.stencil.format===TypedDepthFormat.none.U && !c.originX(0) && !c.originY(0) &&
      c.colors(0).offset(2,0)===0.U && d.depth.offset(2,0)===0.U
  }
  /** A caller routes other legal quads through its general-format backend. */
  def eligible(q: TypedTilePixelQuad,d: TypedTilePixelDescriptor): Bool={
    val c=d.colors;val pixels=q.color.quad.lanes
    val side=1.U(6.W)<<c.tileLog2
    val inside=pixels.map{p=>
      val x=p.x.pad(33)-c.originX.pad(33);val y=p.y.pad(33)-c.originY.pad(33)
      x>=0.S && y>=0.S && x<side.zext && y<side.zext
    }.reduce(_ && _)
    val paired=Seq(0,2).map{i=>
      !pixels(i).x(0) && pixels(i+1).x.pad(33)===pixels(i).x.pad(33)+1.S && pixels(i+1).y===pixels(i).y
    }.reduce(_ && _)
    supported(d) && q.color.quad.sampleMode===c.sampleMode && inside && (c.sampleMode=/=0.U || paired)
  }
}

/** Expand a validated native quad into contiguous pairs of sample records.
  * 1x: four pixels; 2x: two pixels, two samples each; 4x: one pixel, four samples.
  * The input FIFO and registered packet FIFO overlap consecutive native quads.
  * Accepted quads always emit every packet, including through sticky cancel.
  * Cancellation is sampled at packet formation; queued/offered packets remain
  * immutable. The enclosing owner also applies its global cancellation state.
  */
class TypedSamplePacketExpander extends Module {
  val io=IO(new Bundle {
    val bind=Flipped(Decoupled(new TypedTilePixelDescriptor))
    val in=Flipped(Decoupled(new TaggedTypedPixelQuad));val out=Decoupled(new TypedSamplePacket)
    val cancel=Input(Bool());val supported=Output(Bool());val drained=Output(Bool())
  })
  private val descriptor=Reg(new TypedTilePixelDescriptor)
  private val bound=RegInit(false.B);private val supported=RegInit(false.B)
  private val aborted=RegInit(false.B);private val packet=RegInit(0.U(2.W))
  private val quads=Module(new Queue(new TaggedTypedPixelQuad,2,pipe=true))
  private val packets=Module(new Queue(new TypedSamplePacket,2,pipe=true))
  private val stop=aborted || io.cancel
  io.out<>packets.io.deq
  io.drained:= !quads.io.deq.valid && !packets.io.deq.valid
  io.supported:=bound && supported
  io.bind.ready:=io.drained && !io.cancel
  quads.io.enq.valid:=io.in.fire
  quads.io.enq.bits:=io.in.bits
  io.in.ready:=quads.io.enq.ready && bound && supported && !stop && !io.bind.valid
  when(io.cancel){aborted:=true.B}
  when(io.bind.fire){descriptor:=io.bind.bits;bound:=true.B;supported:=TypedSamplePacketLayout.supported(io.bind.bits);aborted:=false.B}
  when(io.in.fire){assert(TypedSamplePacketLayout.eligible(io.in.bits.quad,descriptor),"sample packet requires a validated eligible quad")}

  private val input=quads.io.deq.bits
  private val native=input.quad.color.quad
  private val mode=descriptor.colors.sampleMode
  private val last=packet===(Mux(mode===0.U,1.U,Mux(mode===1.U,2.U,4.U))-1.U)
  packets.io.enq.valid:=quads.io.deq.valid && packets.io.enq.ready
  quads.io.deq.ready:=packets.io.enq.ready && last
  private val output=packets.io.enq.bits
  output.tag:=input.tag;output.packet:=packet;output.last:=last;output.cancelled:=stop
  output.channelMask:=descriptor.colors.colors(0).channelMask
  output.frontFacing:=native.frontFacing;output.tests:=input.quad.state;output.blend:=input.quad.color.blend
  private val active=Wire(Vec(4,Bool()));private val colorActive=Wire(Vec(4,Bool()))
  for(s<-0 until 4){
    val lane=Mux(mode===0.U,s.U(2.W),Mux(mode===1.U,Cat(packet(0),(s/2).U(1.W)),packet))
    val sample=Mux(mode===0.U,0.U(2.W),Mux(mode===1.U,(s%2).U(2.W),s.U(2.W)))
    val pixel=native.lanes(lane)
    val x=(pixel.x.asUInt(3,0)-descriptor.colors.originX.asUInt(3,0))(3,0)
    val y=(pixel.y.asUInt(3,0)-descriptor.colors.originY.asUInt(3,0))(3,0)
    val index=Mux(descriptor.colors.tileLog2===3.U,Cat(y(2,0),x(2,0)).pad(8),Cat(y,x))
    output.indices(s):=Mux(mode===0.U,index.pad(10),Mux(mode===1.U,Cat(index,sample(0)).pad(10),Cat(index,sample)))
    output.lanes(s):=lane;output.samples(s):=sample
    active(s):=input.quad.color.samples(lane)(sample)
    colorActive(s):=active(s) && pixel.colorMask(0) && descriptor.colors.colors(0).channelMask.orR
    output.colors(s):=pixel.colors(0)
    output.depths(s):=Mux(pixel.sampleDepthValid && !pixel.depthOverride,pixel.sampleDepth(sample),pixel.depth)
  }
  output.active:=active.asUInt;output.colorActive:=colorActive.asUInt
  for(pair<-0 until 2){
    val index=output.indices(pair*2)
    output.words(pair):=(descriptor.colors.colors(0).offset>>3)+&(index>>1)
    output.words(pair+2):=(descriptor.depth.offset>>3)+&(index>>1)
    when(packets.io.enq.fire){
      assert(!index(0) && output.indices(pair*2+1)===index+1.U,"sample pair crossed a64-bit word")
    }
  }
  when(packets.io.enq.fire){packet:=Mux(last,0.U,packet+1.U)}
  when(io.drained){assert(packet===0.U,"sample packet stream lost a native quad boundary")}
}
