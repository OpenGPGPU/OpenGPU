package opengpu.graphics

import chisel3._
import chisel3.util._

/** Internal layered attachment binding. The original view bounds cover the
  * complete immutable allocation; pitches advance layer zero to later layers.
  * A single count applies to every enabled attachment and resolve destination.
  * This is not a published GNF format or a product capability.
  */
class TypedLayeredImageDescriptor extends Bundle {
  val version=UInt(8.W);val layers=UInt(9.W)
  val image=new TypedTileImageDescriptor
  val sourcePitch=Vec(6,UInt(33.W));val resolvePitch=Vec(6,UInt(33.W))
}
class TypedLayeredImageAdmissionResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val invalid=Bool();val cancelled=Bool()
}
object TypedLayeredImageSelection {
  /** The caller must retain a successfully admitted descriptor through every
    * selected image owner's complete store/resolve and ownership drain. This
    * pure address selection does not acquire or retire that ownership.
    */
  def apply(d:TypedLayeredImageDescriptor,layer:UInt): (Bool,TypedTileImageDescriptor)={
    val result=WireDefault(d.image)
    val enabled=TypedTileImageRules.enabled(d.image.pixels)
    val loads=TypedTileImageRules.loads(d.image.pixels)
    val sizes=TypedTileImageRules.bytes(d.image.pixels)
    for(i<-0 until 6){
      def select(v:TypedImageView,pitch:UInt,mode:UInt,out:TypedImageView): Unit={
        val base=v.base.pad(68)+(layer*pitch).pad(68)
        val span=((v.imageHeight-1.U)*v.rowPitch).pad(68)+
          (v.imageWidth*(1.U(5.W)<<mode)*sizes(i)).pad(68)
        out.base:=base(31,0);out.limit:=(base+span)(32,0)
      }
      val needed=enabled(i) && (d.image.views(i).store || loads(i)===AttachmentLoadOp.load.U)
      when(needed){select(d.image.views(i),d.sourcePitch(i),d.image.pixels.colors.sampleMode,result.views(i))}
      when(d.image.resolves(i).enable){select(d.image.resolves(i).destination,d.resolvePitch(i),0.U,result.resolves(i).destination)}
    }
    // Compare the full caller value before any narrowing to the 9-bit count.
    (layer<d.layers && d.layers>=1.U && d.layers<=256.U,result)
  }
}

/** Admit all layers before any image memory access. Pairwise alias admission
  * merges two sorted layer-interval streams, rather than comparing large
  * envelopes (which would reject valid interleaved layouts) or instantiating
  * a quadratic bank of layer comparators. At most 511 advances per plane pair
  * are needed for the full 256-layer binding. No image request is issued here.
  */
class TypedLayeredImageAdmission(p:TypedTileColorConfig=TypedTileColorConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedLayeredImageDescriptor))
    val out=Decoupled(new TypedLayeredImageAdmissionResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,common,plane,pair,startPair,merge,respond=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedLayeredImageDescriptor)
  private val result=Reg(new TypedLayeredImageAdmissionResult)
  private val which=RegInit(0.U(4.W));private val a=RegInit(0.U(4.W));private val b=RegInit(1.U(4.W))
  private val spans=Reg(Vec(12,UInt(33.W)))
  private val aLayer=Reg(UInt(9.W));private val bLayer=Reg(UInt(9.W))
  private val aBase=Reg(UInt(33.W));private val bBase=Reg(UInt(33.W))
  private val d=descriptor;private val image=d.image;private val tile=image.pixels.colors
  private val enabled=TypedTileImageRules.enabled(image.pixels)
  private val loads=TypedTileImageRules.loads(image.pixels)
  private val sizes=TypedTileImageRules.bytes(image.pixels)
  private val planes=Wire(Vec(12,new TypedImageView));private val pitches=Wire(Vec(12,UInt(33.W)))
  private val needed=Wire(Vec(12,Bool()))
  for(i<-0 until 6){
    planes(i):=image.views(i);planes(i+6):=image.resolves(i).destination
    pitches(i):=d.sourcePitch(i);pitches(i+6):=d.resolvePitch(i)
    needed(i):=enabled(i) && (image.views(i).store || loads(i)===AttachmentLoadOp.load.U)
    needed(i+6):=image.resolves(i).enable
  }
  private val illegalPairs=Wire(Vec(12,Vec(12,Bool())))
  for(x<-0 until 12;y<-0 until 12){
    val forbidden=if(x<6 && y<6){
      image.views(x).store && image.views(y).store ||
        (image.resolves(x).enable && loads(x)===AttachmentLoadOp.load.U && image.views(y).store) ||
        (image.resolves(y).enable && loads(y)===AttachmentLoadOp.load.U && image.views(x).store)
    }else true.B
    illegalPairs(x)(y):=(x!=y).B && needed(x) && needed(y) && forbidden
  }
  private def reject(): Unit={result.invalid:=true.B;phase:=State.respond}
  private def nextPair(): Unit={
    phase:=State.pair
    when(a===10.U && b===11.U){phase:=State.respond}
      .elsewhen(b===11.U){a:=a+1.U;b:=a+2.U}.otherwise{b:=b+1.U}
  }
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.respond
  io.out.bits:=result;io.busy:=phase=/=State.idle
  switch(phase){
    is(State.idle){when(io.in.fire){descriptor:=io.in.bits;result:=0.U.asTypeOf(result)
      result.identity:=io.in.bits.image.pixels.colors.identity;which:=0.U;phase:=State.common}}
    is(State.common){
      when(d.version=/=1.U || d.layers===0.U || d.layers>256.U || image.version=/=1.U || TypedTilePixelRules.invalid(image.pixels,p)){
        reject()
      }.otherwise{phase:=State.plane}
    }
    is(State.plane){
      val v=planes(which);val pitch=pitches(which);val resolving=which>=6.U
      val index=Mux(resolving,which-6.U,which)(2,0)
      val mode=Mux(resolving,0.U,tile.sampleMode)
      val rowWidth=(v.imageWidth*(1.U(5.W)<<mode)*sizes(index)).pad(68)
      val span=((v.imageHeight-1.U)*v.rowPitch).pad(68)+rowWidth
      val last=v.base.pad(68)+((d.layers-1.U)*pitch).pad(68)+span
      val read= !resolving && loads(index)===AttachmentLoadOp.load.U
      val write=resolving || v.store
      val bad=v.imageWidth===0.U || v.imageHeight===0.U || tile.originX<0.S || tile.originY<0.S ||
        tile.originX.asUInt.pad(34)+tile.tileWidth>v.imageWidth ||
        tile.originY.asUInt.pad(34)+tile.tileHeight>v.imageHeight || rowWidth>v.rowPitch ||
        v.allocationBase(5,0)=/=0.U || v.allocationLimit(5,0)=/=0.U ||
        v.allocationLimit>(BigInt(1)<<32).U || v.allocationLimit<=v.allocationBase ||
        v.base<v.allocationBase || v.limit>v.allocationLimit || v.limit<=v.base || last>v.limit ||
        (d.layers>1.U && pitch<span) || (read && !v.readable) || (write && !v.writable) ||
        (resolving && (!enabled(index) || !v.store))
      when(needed(which) && bad){reject()}.otherwise{
        spans(which):=Mux(needed(which),span(32,0),0.U)
        when(which===11.U){a:=0.U;b:=1.U;phase:=State.pair}.otherwise{which:=which+1.U}
      }
    }
    is(State.pair){
      when(a>=11.U){phase:=State.respond}
        .elsewhen(illegalPairs(a)(b)){phase:=State.startPair}.otherwise{nextPair()}
    }
    is(State.startPair){aLayer:=0.U;bLayer:=0.U;aBase:=planes(a).base;bBase:=planes(b).base;phase:=State.merge}
    is(State.merge){
      val aEnd=aBase+&spans(a);val bEnd=bBase+&spans(b)
      when(aBase<bEnd && bBase<aEnd){reject()}
        .elsewhen(aEnd<=bBase){
          when(aLayer+&1.U>=d.layers){nextPair()}.otherwise{aLayer:=aLayer+1.U;aBase:=aBase+pitches(a)}
        }.otherwise{
          when(bLayer+&1.U>=d.layers){nextPair()}.otherwise{bLayer:=bLayer+1.U;bBase:=bBase+pitches(b)}
        }
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
  // A held result is immutable, including after a later cancel pulse.
  when(phase=/=State.idle && phase=/=State.respond && io.cancel){result.cancelled:=true.B;phase:=State.respond}
}
