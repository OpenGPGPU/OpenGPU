package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class PatchInputDmaContextHarness extends Module {
  val patchContext=Module(new TessellationPatchContext);val loader=Module(new TessellationPatchInputDma(GpuConfig(lanes=4,warps=2)))
  val io=IO(new Bundle {
    val context=chiselTypeOf(patchContext.io);val task=Flipped(Decoupled(new TessellationPatchInputDmaTask))
    val completion=Decoupled(new TessellationPatchInputDmaCompletion)
    val memoryRequest=chiselTypeOf(loader.io.memoryRequest);val memoryResponse=chiselTypeOf(loader.io.memoryResponse)
  })
  io.context<>patchContext.io;loader.io.task<>io.task;io.completion<>loader.io.completion
  io.memoryRequest<>loader.io.memoryRequest;loader.io.memoryResponse<>io.memoryResponse
  loader.io.cancel:=io.context.cancel;patchContext.io.load<>loader.io.load
  loader.io.reply.valid:=patchContext.io.reply.valid && patchContext.io.reply.bits.source===0.U;loader.io.reply.bits:=patchContext.io.reply.bits
  io.context.reply.valid:=patchContext.io.reply.valid && patchContext.io.reply.bits.source=/=0.U;io.context.reply.bits:=patchContext.io.reply.bits
  patchContext.io.reply.ready:=Mux(patchContext.io.reply.bits.source===0.U,loader.io.reply.ready,io.context.reply.ready)
}
class TessellationPatchInputDmaContextSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "make GPU-loaded inputs visible to the control patch context" in {
    simulate(new PatchInputDmaContextHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val c=d.io.context;val pending=mutable.Queue.empty[(BigInt,Int)];var cycle=0;var reads=0
      def line(address:BigInt):BigInt=(0 until 16).foldLeft(BigInt(0))((acc,i)=>acc|((address/4+i)<<(32*i)))
      def tick():Unit={d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        val r=pending.headOption.filter(_._2<=cycle);d.io.memoryResponse.valid.poke(r.nonEmpty.B)
        r.foreach{case(addr,_)=>d.io.memoryResponse.bits.readData.poke(line(addr).U)}
        if(r.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          pending.enqueue((d.io.memoryRequest.bits.address.peek().litValue,cycle+2));reads+=1}
        d.clock.step();cycle+=1}
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<2000){tick();n+=1};assert(p,s"$label timeout")}
      c.begin.bits.taskId.poke(17.U);c.begin.bits.layout.inputVertices.poke(2.U);c.begin.bits.layout.inputComponents.poke(3.U)
      c.begin.bits.layout.outputVertices.poke(1.U);c.begin.valid.poke(true.B);until("context begin")(c.begin.ready.peek().litToBoolean)
      tick();c.begin.valid.poke(false.B);c.opened.ready.poke(true.B);until("open")(c.opened.valid.peek().litToBoolean)
      c.opened.bits.expect(0.U);tick();c.opened.ready.poke(false.B)
      val t=d.io.task.bits;t.taskId.poke(17.U);t.vertices.poke(2.U);t.components.poke(3.U);t.stride.poke(16.U)
      t.view.allocationBase.poke(0x1000.U);t.view.allocationLimit.poke(0x1100.U);t.view.base.poke(0x103c.U);t.view.limit.poke(0x1058.U)
      d.io.task.valid.poke(true.B);until("loader task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
      until("loader completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U);d.io.completion.bits.words.expect(6.U)
      assert(reads==2 && pending.isEmpty);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      c.sealInput.valid.poke(true.B);until("seal")(c.sealInput.ready.peek().litToBoolean);tick();c.sealInput.valid.poke(false.B)
      for(v<-0 until 2;component<-0 until 3){val a=c.access.bits;a.invocation.poke(0.U);a.access.vertex.poke(v.U);a.access.component.poke(component.U)
        c.access.valid.poke(true.B);until("input read")(c.access.ready.peek().litToBoolean);tick();c.access.valid.poke(false.B)
        c.reply.ready.poke(true.B);until("input reply")(c.reply.valid.peek().litToBoolean);c.reply.bits.source.expect(1.U)
        c.reply.bits.result.status.expect(0.U);c.reply.bits.result.data.expect(((0x103c+v*16+component*4)/4).U)
        tick();c.reply.ready.poke(false.B)}
      c.finish.valid.poke(true.B);until("finish")(c.finish.ready.peek().litToBoolean);tick();c.finish.valid.poke(false.B)
      c.controlResult.ready.poke(true.B);until("control result")(c.controlResult.valid.peek().litToBoolean)
      c.controlResult.bits.status.expect(0.U);tick();c.controlResult.ready.poke(false.B)
      c.close.valid.poke(true.B);until("close")(c.close.ready.peek().litToBoolean);tick();c.close.valid.poke(false.B)
    }
  }
}
