package opengpu.core

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class PatchCuHarness(config:GpuConfig) extends Module {
  val cu=Module(new GpuComputeUnit(config,finishOnTrap=true,textureAvailable=false,nativeResourcesAvailable=false,
    tessellationAvailable=true,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  val patch=Module(new TessellationControlWarpScheduler(config.warps,config.lanes))
  val io=IO(new Bundle {
    val system=chiselTypeOf(cu.io);val begin=Flipped(Decoupled(new TessellationPatchContextTask));val opened=Decoupled(UInt(2.W))
    val seal=Flipped(Decoupled(Bool()));val control=Decoupled(new TessellationBarrierResult)
    val close=Flipped(Decoupled(Bool()));val cancel=Input(Bool());val holdFinish=Input(Bool());val finishOffered=Output(Bool())
    val holdResume=Input(Bool());val resumeOffered=Output(Bool())
    val observed=Output(Valid(new Bundle {val warp=UInt(config.warpIdWidth.W);val base=UInt(5.W)}))
  })
  io.system<>cu.io;patch.io.begin<>io.begin;io.opened<>patch.io.opened;patch.io.seal<>io.seal
  patch.io.load.valid:=false.B;patch.io.load.bits:=0.U.asTypeOf(patch.io.load.bits)
  patch.io.inspect.valid:=false.B;patch.io.inspect.bits:=0.U.asTypeOf(patch.io.inspect.bits);patch.io.reply.ready:=true.B
  patch.io.park<>cu.io.tessellationPark.get
  cu.io.tessellationResume.get.valid:=patch.io.resume.valid && !io.holdResume
  cu.io.tessellationResume.get.bits:=patch.io.resume.bits
  patch.io.resume.ready:=cu.io.tessellationResume.get.ready && !io.holdResume
  io.resumeOffered:=patch.io.resume.valid
  patch.io.finish.valid:=cu.io.tessellationFinish.get.valid && !io.holdFinish
  patch.io.finish.bits:=cu.io.tessellationFinish.get.bits
  cu.io.tessellationFinish.get.ready:=patch.io.finish.ready && !io.holdFinish
  io.finishOffered:=cu.io.tessellationFinish.get.valid
  io.control<>patch.io.control;patch.io.close<>io.close;patch.io.cancel:=io.cancel
  patch.io.access<>cu.io.tessellationRequest.get;cu.io.tessellationResult.get<>patch.io.accessResult
  io.observed.valid:=cu.io.tessellationRequest.get.fire;io.observed.bits.warp:=cu.io.tessellationRequest.get.bits.warp
  io.observed.bits.base:=cu.io.tessellationRequest.get.bits.base
  cu.io.tessellationTerminal.get:=patch.io.control.fire && patch.io.control.bits.status=/=0.U
}

class GpuComputeUnitTessellationSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn:Int,rd:Int=0,vs1:Int=0,vs2:Int=0):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
  for((warpCount,capacity)<-Seq((2,8),(8,32))) {
  it should s"execute task-owned patch instructions and barriers with $capacity invocation capacity" in {
    val cfg=GpuConfig(lanes=4,warps=warpCount)
    simulate(new PatchCuHarness(cfg)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val c=d.io.system;c.l1InvalidateDone.ready.poke(true.B);c.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int];val pending=mutable.Queue.empty[(BigInt,BigInt)]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def line(a:BigInt):BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      def program(address:Int,invalid:Boolean):Unit={val words=Vector(BigInt("0104f057",16),vector(23,3,2,0),vector(23,3,3,7),
        vector(23,3,4,if(invalid)2 else 3),patch(20,vs1=3),patch(22,vs1=2,vs2=4),BigInt("0000000b",16),patch(21,rd=10,vs1=2,vs2=4),BigInt("30500073",16))
        words.zipWithIndex.foreach{case(w,i)=>put(address+i*4,w)}}
      program(0x1000,false);program(0x1100,true);put(0x1200,BigInt("0000000b",16));put(0x1204,BigInt("30500073",16))
      var cycle=0;var observeDefault=false;val traps=mutable.ArrayBuffer.empty[(BigInt,BigInt,BigInt)]
      val writebacks=mutable.ArrayBuffer.empty[BigInt]
      val mappings=mutable.Set.empty[(BigInt,BigInt)]
      def tick():Unit={c.memoryRequest.ready.poke((cycle%3!=0).B);c.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.headOption.foreach{case(id,data)=>c.memoryResponse.bits.transactionId.poke(id.U);c.memoryResponse.bits.readData.poke(data.U)}
        if(pending.nonEmpty && c.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(c.memoryRequest.valid.peek().litToBoolean && c.memoryRequest.ready.peek().litToBoolean){
          c.memoryRequest.bits.isWrite.expect(false.B);pending.enqueue((c.memoryRequest.bits.transactionId.peek().litValue,line(c.memoryRequest.bits.address.peek().litValue)))}
        if(c.trap.valid.peek().litToBoolean)traps+=((c.trap.bits.pc.peek().litValue,c.trap.bits.cause.peek().litValue,c.trap.bits.laneFaultMask.peek().litValue))
        if(observeDefault){c.tessellationPark.get.valid.expect(false.B);c.tessellationFinish.get.valid.expect(false.B)}
        if(d.io.observed.valid.peek().litToBoolean)mappings+=((d.io.observed.bits.warp.peek().litValue,d.io.observed.bits.base.peek().litValue))
        if(c.committedVectorWriteback.valid.peek().litToBoolean && c.committedVectorWriteback.bits.vd.peek().litValue==10){
          c.committedVectorWriteback.bits.data.foreach(_.expect(7.U));writebacks+=c.committedVectorWriteback.bits.warpId.peek().litValue}
        d.clock.step();cycle+=1}
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<30000){tick();n+=1};assert(p,s"$label timeout")}
      def begin(items:Int=4):Unit={val t=d.io.begin.bits;t.taskId.poke(8.U);t.layout.inputVertices.poke(1.U);t.layout.outputVertices.poke(items.U);t.layout.patchComponents.poke(1.U)
        d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
        d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
        tick();d.io.opened.ready.poke(false.B);d.io.seal.valid.poke(true.B);until("seal")(d.io.seal.ready.peek().litToBoolean);tick();d.io.seal.valid.poke(false.B)}
      def kernel(pc:Int,success:Boolean,items:Int=4,holdFinish:Boolean=false):Unit={writebacks.clear();mappings.clear();val before=traps.size;c.tessellationEnabled.get.poke(true.B)
        d.io.holdFinish.poke(holdFinish.B)
        c.tessellationBases.get.zipWithIndex.foreach{case(b,i)=>b.poke((i*4).U)}
        c.kernel.bits.kernelPc.poke(pc.U);for(i<-0 until 3){c.kernel.bits.gridSize(i).poke(1.U);c.kernel.bits.localSize(i).poke((if(i==0)items else 1).U)}
        c.kernel.valid.poke(true.B);until("kernel")(c.kernel.ready.peek().litToBoolean);tick();c.kernel.valid.poke(false.B)
        c.tessellationEnabled.get.poke(false.B);c.tessellationBases.get.foreach(_.poke(31.U));c.tessellationLease.get.expect(true.B)
        if(holdFinish){until("held finish")(d.io.finishOffered.peek().litToBoolean)
          val held=c.tessellationFinish.get.bits.peek().litValue
          for(_<-0 until 5){c.completion.valid.expect(false.B);assert(c.tessellationFinish.get.bits.peek().litValue==held);tick()}
          d.io.holdFinish.poke(false.B)}
        until("kernel completion")(c.completion.valid.peek().litToBoolean);c.completion.bits.success.expect(success.B)
        assert(writebacks.size==(if(success)(items+3)/4 else 0) && writebacks.distinct.size==writebacks.size &&
          traps.size-before==(if(success)0 else 1));assert(pending.isEmpty)
        if(success && items==capacity)assert(mappings.toSet==(0 until warpCount).map(i=>(BigInt(i),BigInt(i*4))).toSet)
        if(!success)assert(traps.last==((BigInt(0x1114),BigInt(7),BigInt(15))))
        c.completion.ready.poke(true.B);tick();c.completion.ready.poke(false.B)}
      def close(success:Boolean,items:Int=4):Unit={if(!success) {d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)}
        d.io.control.ready.poke(true.B);until("control")(d.io.control.valid.peek().litToBoolean)
        d.io.control.bits.status.expect((if(success)0 else 2).U)
        if(success){d.io.control.bits.barriers.expect(1.U);d.io.control.bits.completed.expect(items.U)}
        tick();d.io.control.ready.poke(false.B)
        d.io.close.valid.poke(true.B);until("close")(d.io.close.ready.peek().litToBoolean);tick();d.io.close.valid.poke(false.B)}
      begin();kernel(0x1000,true);close(true)
      begin();kernel(0x1100,false);close(false)
      begin();kernel(0x1000,true);close(true)
      begin(capacity);kernel(0x1000,true,capacity,holdFinish=true);close(true,capacity)
      begin();writebacks.clear();val prior=traps.size;d.io.holdResume.poke(true.B);c.tessellationEnabled.get.poke(true.B)
      c.tessellationBases.get.zipWithIndex.foreach{case(b,i)=>b.poke((i*4).U)}
      c.kernel.bits.kernelPc.poke(0x1000.U);for(i<-0 until 3){c.kernel.bits.gridSize(i).poke(1.U);c.kernel.bits.localSize(i).poke((if(i==0)4 else 1).U)}
      c.kernel.valid.poke(true.B);until("cancel kernel")(c.kernel.ready.peek().litToBoolean);tick();c.kernel.valid.poke(false.B)
      until("held barrier resume")(d.io.resumeOffered.peek().litToBoolean);c.completion.valid.expect(false.B)
      d.io.cancel.poke(true.B);c.tessellationCancel.get.poke(true.B);tick();d.io.cancel.poke(false.B);c.tessellationCancel.get.poke(false.B)
      d.io.control.ready.poke(true.B);until("barrier cancel control")(d.io.control.valid.peek().litToBoolean)
      d.io.control.bits.status.expect(2.U);tick();d.io.control.ready.poke(false.B);d.io.holdResume.poke(false.B)
      until("barrier cancel completion")(c.completion.valid.peek().litToBoolean);c.completion.bits.success.expect(false.B)
      assert(writebacks.isEmpty && traps.size==prior+1 && traps.last==((BigInt(0x1018),BigInt(25),BigInt(15))))
      c.completion.ready.poke(true.B);tick();c.completion.ready.poke(false.B)
      d.io.close.valid.poke(true.B);until("barrier cancel close")(d.io.close.ready.peek().litToBoolean);tick();d.io.close.valid.poke(false.B)
      val beforeDefault=traps.size;observeDefault=true;c.tessellationEnabled.get.poke(false.B);c.kernel.bits.kernelPc.poke(0x1200.U)
      for(i<-0 until 3){c.kernel.bits.gridSize(i).poke(1.U);c.kernel.bits.localSize(i).poke((if(i==0)8 else 1).U)}
      c.kernel.valid.poke(true.B);until("ordinary kernel")(c.kernel.ready.peek().litToBoolean);tick();c.kernel.valid.poke(false.B)
      c.tessellationLease.get.expect(false.B);until("ordinary completion")(c.completion.valid.peek().litToBoolean)
      c.completion.bits.success.expect(true.B);assert(traps.size==beforeDefault && pending.isEmpty)
      c.completion.ready.poke(true.B);tick();c.completion.ready.poke(false.B);observeDefault=false
    }
  }
  }
}
