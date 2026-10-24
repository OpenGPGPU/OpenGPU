package opengpu.graphics

import chisel3._
import chisel3.util._

class TypedSampleMathInput extends Bundle {
  val token=UInt(8.W);val cancelled=Bool()
  val active=UInt(4.W);val colorActive=UInt(4.W);val channelMask=UInt(4.W)
  val colors=Vec(4,Vec(4,UInt(32.W)));val depths=Vec(4,UInt(32.W))
  val oldColors=Vec(2,UInt(64.W));val oldDepthStencil=Vec(2,UInt(64.W))
  val frontFacing=Bool();val tests=new TypedLateTestState;val blend=new TypedBlendState
}
class TypedSampleMathOutput extends Bundle {
  val token=UInt(8.W);val colors=Vec(2,UInt(64.W));val depthStencil=Vec(2,UInt(64.W))
  val colorWrite=UInt(2.W);val depthStencilWrite=UInt(2.W)
  // These are planned effects. The worker accounts actual effects at write ACKs.
  val result=new TypedSampleTerminal
}
private class SampleComparisons extends Bundle {
  val owner=new TypedSampleMathInput;val encoded=Vec(4,UInt(24.W))
  val stencilPass=UInt(4.W);val depthPass=UInt(4.W);val invalid=Bool()
}
private class SampleMathOwner extends Bundle {
  val token=UInt(8.W);val oldColors=Vec(2,UInt(64.W));val depthStencil=Vec(2,UInt(64.W))
  val channelMask=UInt(4.W);val result=new TypedSampleTerminal
}
private class SampleMathPlan extends Bundle {
  val owner=new SampleMathOwner;val colors=Vec(4,Vec(4,UInt(32.W)));val blend=new TypedBlendState
}

/** Four complete RGBA8+D24S8 sample records per admission. The caller supplies
  * owned resident/virtual old words, resolves source sample depths, and retains
  * word reservations through memory write acknowledgements. This is arithmetic
  * and full-word merging, not a memory terminal acknowledgement.
  *
  * Input is an internal drain port and remains open after sticky cancellation.
  * Every admitted packet traverses all four lanes. Arithmetic is never flushed;
  * queued offers stay immutable, and cancellation suppresses newly formed plans.
  * A worker must also suppress new writes from an older held output on cancel.
  */
class StreamingTypedSampleMath extends Module {
  val io=IO(new Bundle {
    val begin=Input(Bool());val cancel=Input(Bool())
    val in=Flipped(Decoupled(new TypedSampleMathInput));val out=Decoupled(new TypedSampleMathOutput)
    val drained=Output(Bool())
  })
  private val bound=RegInit(false.B);private val aborted=RegInit(false.B)
  private val stop=aborted || io.cancel
  private val count=RegInit(0.U(8.W))
  private val depth=Module(new PreparedD24Depths)
  private val owners=Module(new Queue(new TypedSampleMathInput,8))
  private val depths=Module(new Queue(new TypedDepthVectorResult(4),8))
  private val comparisons=Module(new Queue(new SampleComparisons,1,pipe=true))
  private val plans=Module(new Queue(new SampleMathPlan,1,pipe=true))
  private val metadata=Module(new Queue(new SampleMathOwner,64))
  private val output=Module(new Queue(new TypedSampleMathOutput,2,pipe=true))
  private val blend=Seq.fill(4)(Module(new StreamingRgba8Blend))
  private val pack=Seq.fill(4)(Module(new StreamingRgba8Encoder))
  // Reserve storage before entering the non-stallable depth conversion.
  private val depthCredits=RegInit(0.U(4.W))
  io.in.ready:=bound && !io.begin && count<128.U && depthCredits<8.U && owners.io.enq.ready
  owners.io.enq.valid:=io.in.fire;owners.io.enq.bits:=io.in.bits
  depth.io.valid:=io.in.fire;depth.io.raw:=io.in.bits.depths;depth.io.tag:=io.in.bits.token
  depths.io.enq.valid:=depth.io.out.valid;depths.io.enq.bits:=depth.io.out.bits
  when(depth.io.out.valid){assert(depths.io.enq.ready,"sample depth result lost its reserved storage")}
  private val compared=comparisons.io.enq.fire
  owners.io.deq.ready:=depths.io.deq.valid && comparisons.io.enq.ready
  depths.io.deq.ready:=owners.io.deq.valid && comparisons.io.enq.ready
  comparisons.io.enq.valid:=owners.io.deq.valid && depths.io.deq.valid
  private val incoming=owners.io.deq.bits
  private val face=Mux(incoming.frontFacing,incoming.tests.front,incoming.tests.back)
  private val stencilPass=Wire(Vec(4,Bool()));private val depthPass=Wire(Vec(4,Bool()))
  comparisons.io.enq.bits.owner:=incoming
  comparisons.io.enq.bits.invalid:=incoming.tests.version=/=1.U || TypedBlendState.invalid(incoming.blend) ||
    VecInit((0 until 4).map(s=>incoming.active(s) && incoming.tests.depthEnable && Fp32ViewportState.nan(incoming.depths(s)))).asUInt.orR
  for(s<-0 until 4){
    val old=(incoming.oldDepthStencil(s/2)>>(32*(s%2)))(31,0)
    stencilPass(s):= !incoming.tests.stencilEnable ||
      TypedDepthFormat.compare(face.reference&face.readMask,old(31,24)&face.readMask,face.function)
    depthPass(s):= !incoming.tests.depthEnable ||
      TypedDepthFormat.compare(depths.io.deq.bits.values(s)(23,0),old(23,0),incoming.tests.depthFunction)
    comparisons.io.enq.bits.encoded(s):=depths.io.deq.bits.values(s)(23,0)
  }
  comparisons.io.enq.bits.stencilPass:=stencilPass.asUInt;comparisons.io.enq.bits.depthPass:=depthPass.asUInt
  when(compared){assert(incoming.token===depths.io.deq.bits.tag,"sample depth and owner tags diverged")}
  switch(Cat(io.in.fire,compared)){
    is("b10".U){depthCredits:=depthCredits+1.U};is("b01".U){depthCredits:=depthCredits-1.U}
  }

  plans.io.enq.valid:=comparisons.io.deq.valid;comparisons.io.deq.ready:=plans.io.enq.ready
  private val c=comparisons.io.deq.bits;private val p=c.owner
  private val f=Mux(p.frontFacing,p.tests.front,p.tests.back)
  private val cancelled=p.cancelled || stop
  private val enabled= !cancelled && !c.invalid
  private val tested=p.active & Fill(4,enabled)
  private val passing=tested & c.stencilPass & c.depthPass
  private val colorWritten=passing & p.colorActive & Fill(4,p.channelMask.orR)
  private val depthWritten=passing & Fill(4,p.tests.depthEnable && p.tests.depthWrite)
  private val stencilWritten=Wire(Vec(4,Bool()));private val records=Wire(Vec(4,UInt(32.W)))
  plans.io.enq.bits.colors:=p.colors;plans.io.enq.bits.blend:=p.blend
  private val next=plans.io.enq.bits.owner
  next:=0.U.asTypeOf(next);next.token:=p.token;next.oldColors:=p.oldColors;next.channelMask:=p.channelMask
  next.result.status:=Mux(cancelled,4.U,Mux(c.invalid,6.U,0.U))
  next.result.tested:=tested;next.result.passing:=passing
  next.result.stencilRejected:=tested & ~c.stencilPass
  next.result.depthRejected:=tested & c.stencilPass & ~c.depthPass
  next.result.colorWritten:=colorWritten;next.result.depthWritten:=depthWritten
  for(s<-0 until 4){
    val old=(p.oldDepthStencil(s/2)>>(32*(s%2)))(31,0);val oldStencil=old(31,24)
    val op=Mux(!c.stencilPass(s),f.fail,Mux(!c.depthPass(s),f.depthFail,f.depthPass))
    val calculated=TypedDepthFormat.operation(oldStencil,f.reference,op)
    val updated=(calculated&f.writeMask)|(oldStencil& ~f.writeMask)
    stencilWritten(s):=tested(s) && p.tests.stencilEnable && updated=/=oldStencil
    records(s):=Cat(Mux(stencilWritten(s),updated,oldStencil),Mux(depthWritten(s),c.encoded(s),old(23,0)))
  }
  next.result.stencilWritten:=stencilWritten.asUInt
  for(pair<-0 until 2)next.depthStencil(pair):=Cat(records(2*pair+1),records(2*pair))

  // Atomic fork: all four lanes and their owner accept the same packet together.
  private val allBlendReady=blend.map(_.io.in.ready).reduce(_ && _)
  private val dispatch=plans.io.deq.valid && allBlendReady && metadata.io.enq.ready
  plans.io.deq.ready:=allBlendReady && metadata.io.enq.ready
  metadata.io.enq.valid:=dispatch;metadata.io.enq.bits:=plans.io.deq.bits.owner
  for(s<-0 until 4){
    blend(s).io.cancel:=false.B
    blend(s).io.in.valid:=dispatch;blend(s).io.in.bits.source:=plans.io.deq.bits.colors(s)
    blend(s).io.in.bits.destination:=(plans.io.deq.bits.owner.oldColors(s/2)>>(32*(s%2)))(31,0)
    blend(s).io.in.bits.format:=TypedColorFormat.id("RGBA8").U
    blend(s).io.in.bits.state:=plans.io.deq.bits.blend;blend(s).io.in.bits.tag:=plans.io.deq.bits.owner.token
  }
  private val allBlended=blend.map(_.io.out.valid).reduce(_ && _)
  private val allPackReady=pack.map(_.io.in.ready).reduce(_ && _)
  for(s<-0 until 4){
    blend(s).io.out.ready:=allBlended && allPackReady
    pack(s).io.in.valid:=allBlended && allPackReady
    pack(s).io.in.bits.rgba:=blend(s).io.out.bits.rgba
    pack(s).io.in.bits.format:=TypedColorFormat.id("RGBA8").U;pack(s).io.in.bits.colorType:=0.U
    pack(s).io.in.bits.mask:=15.U;pack(s).io.in.bits.tag:=blend(s).io.out.bits.tag
    when(pack(s).io.in.fire){assert(blend(s).io.out.bits.tag===blend.head.io.out.bits.tag,"sample blend lanes lost lockstep")}
  }
  private val allPacked=pack.map(_.io.out.valid).reduce(_ && _)
  private val owner=metadata.io.deq.bits
  private val suppress=stop || owner.result.status=/=0.U
  output.io.enq.valid:=allPacked && metadata.io.deq.valid
  metadata.io.deq.ready:=allPacked && output.io.enq.ready
  private val result=output.io.enq.bits
  result:=0.U.asTypeOf(result);result.token:=owner.token;result.result:=owner.result
  when(stop && owner.result.status===0.U){result.result.status:=4.U}
  when(suppress){
    result.result.colorWritten:=0.U;result.result.depthWritten:=0.U;result.result.stencilWritten:=0.U
  }
  private val colorRecords=Wire(Vec(4,UInt(32.W)))
  for(s<-0 until 4){
    pack(s).io.out.ready:=allPacked && metadata.io.deq.valid && output.io.enq.ready
    val old=(owner.oldColors(s/2)>>(32*(s%2)))(31,0)
    val mask=Cat((0 until 4).reverse.map(b=>Fill(8,owner.channelMask(b) && owner.result.colorWritten(s) && !suppress)))
    colorRecords(s):=(old& ~mask)|(pack(s).io.out.bits.value(31,0)&mask)
    when(output.io.enq.fire){assert(pack(s).io.out.bits.tag===owner.token,"sample packing and writeback tags diverged")}
  }
  for(pair<-0 until 2){
    result.colors(pair):=Cat(colorRecords(2*pair+1),colorRecords(2*pair))
    result.depthStencil(pair):=owner.depthStencil(pair)
  }
  result.colorWrite:=VecInit((0 until 2).map(pair=>result.result.colorWritten(2*pair+1,2*pair).orR)).asUInt
  result.depthStencilWrite:=VecInit((0 until 2).map(pair=>
    (result.result.depthWritten|result.result.stencilWritten)(2*pair+1,2*pair).orR)).asUInt
  io.out<>output.io.deq
  switch(Cat(io.in.fire,io.out.fire)){
    is("b10".U){count:=count+1.U};is("b01".U){count:=count-1.U}
  }
  io.drained:=count===0.U
  when(io.cancel){aborted:=true.B}
  when(io.begin){assert(io.drained && !io.cancel,"sample math begin requires complete drain");bound:=true.B;aborted:=false.B}
  when(io.drained){
    assert(depthCredits===0.U && !owners.io.deq.valid && !depths.io.deq.valid && !comparisons.io.deq.valid &&
      !plans.io.deq.valid && !metadata.io.deq.valid && !output.io.deq.valid &&
      !blend.map(_.io.busy).reduce(_ || _) && !pack.map(_.io.busy).reduce(_ || _),"sample math released an owned stage early")
  }
}
