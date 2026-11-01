package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TessellationPatchInputDmaSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "load strided patch words across cache lines and drain faults and cancelled offers" in {
    simulate(new TessellationPatchInputDma(GpuConfig(lanes=4,warps=2))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p,s"$label timeout")}
      def configure():Unit={val t=d.io.task.bits;t.taskId.poke(17.U);t.vertices.poke(2.U);t.components.poke(3.U)
        t.view.allocationBase.poke(0x1000.U);t.view.allocationLimit.poke(0x1100.U)
        t.view.base.poke(0x103c.U);t.view.limit.poke(0x1058.U);t.stride.poke(16.U)}
      def submit():Unit={d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean)
        d.clock.step();d.io.task.valid.poke(false.B)}
      def result(status:Int,words:Int):Unit={until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U);d.io.completion.bits.words.expect(words.U);d.io.completion.bits.taskId.expect(17.U)
        val held=d.io.completion.bits.peek().litValue;d.clock.step(3);assert(held==d.io.completion.bits.peek().litValue)
        d.io.task.ready.expect(false.B);d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)}
      configure();d.io.task.bits.view.base.poke(0x103d.U);submit();d.io.memoryRequest.valid.expect(false.B);result(1,0)
      configure();d.io.task.bits.view.limit.poke(0x1054.U);submit();result(1,0)
      configure();d.io.task.bits.components.poke(0.U);zero(d.io.task.bits.view);submit();result(0,0)
      configure();val boundary=d.io.task.bits;boundary.vertices.poke(2.U);boundary.components.poke(1.U);boundary.stride.poke(4.U)
      boundary.view.allocationBase.poke("hffffffc0".U);boundary.view.allocationLimit.poke((BigInt(1)<<32).U)
      boundary.view.base.poke("hfffffffc".U);boundary.view.limit.poke((BigInt(1)<<32).U);submit();result(1,0)
      boundary.vertices.poke(1.U);submit();until("last line")(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.bits.address.expect("hffffffc0".U);d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.bits.readData.poke((BigInt("deadbeef",16)<<480).U);d.io.memoryResponse.valid.poke(true.B)
      until("last line reply")(d.io.memoryResponse.ready.peek().litToBoolean);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      until("last word")(d.io.load.valid.peek().litToBoolean);d.io.load.bits.data.expect("hdeadbeef".U)
      d.io.load.ready.poke(true.B);d.clock.step();d.io.load.ready.poke(false.B);d.io.reply.valid.poke(true.B)
      until("last word reply")(d.io.reply.ready.peek().litToBoolean);d.clock.step();d.io.reply.valid.poke(false.B);result(0,1)
      configure();submit()
      val pending=mutable.Queue.empty[(BigInt,Int)];var replyAt=Option.empty[Int];var cycle=0;val lines=mutable.ArrayBuffer.empty[BigInt]
      val seen=mutable.ArrayBuffer.empty[(Int,Int,BigInt)];var heldMem=Option.empty[BigInt];var heldLoad=Option.empty[BigInt]
      def line(address:BigInt):BigInt=(0 until 16).foldLeft(BigInt(0))((acc,i)=>acc|((address/4+i)<<(32*i)))
      def tick():Unit={
        d.io.memoryRequest.ready.poke((cycle%4!=0).B);val mr=d.io.memoryRequest
        heldMem.foreach(x=>assert(mr.valid.peek().litToBoolean && mr.bits.peek().litValue==x))
        heldMem=if(mr.valid.peek().litToBoolean && !mr.ready.peek().litToBoolean)Some(mr.bits.peek().litValue)else None
        if(mr.valid.peek().litToBoolean && mr.ready.peek().litToBoolean){val addr=mr.bits.address.peek().litValue
          mr.bits.isWrite.expect(false.B);mr.bits.sizeLog2.expect(6.U);lines+=addr;pending.enqueue((addr,cycle+3))}
        val ready=pending.headOption.filter(_._2<=cycle);d.io.memoryResponse.valid.poke(ready.nonEmpty.B)
        ready.foreach{case(addr,_)=>d.io.memoryResponse.bits.readData.poke(line(addr).U)}
        if(ready.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        d.io.load.ready.poke((cycle%3!=0 && replyAt.isEmpty).B);val load=d.io.load
        heldLoad.foreach(x=>assert(load.valid.peek().litToBoolean && load.bits.peek().litValue==x))
        heldLoad=if(load.valid.peek().litToBoolean && !load.ready.peek().litToBoolean)Some(load.bits.peek().litValue)else None
        if(load.valid.peek().litToBoolean && load.ready.peek().litToBoolean){load.bits.write.expect(true.B)
          load.bits.output.expect(false.B);seen+=((load.bits.vertex.peek().litValue.toInt,load.bits.component.peek().litValue.toInt,load.bits.data.peek().litValue))
          replyAt=Some(cycle+2)}
        d.io.reply.valid.poke(replyAt.exists(_<=cycle).B)
        if(replyAt.exists(_<=cycle) && d.io.reply.ready.peek().litToBoolean)replyAt=None
        d.clock.step();cycle+=1
      }
      var n=0;while(!d.io.completion.valid.peek().litToBoolean && n<1000){tick();n+=1};assert(n<1000)
      assert(lines.toSeq==Seq(BigInt(0x1000),BigInt(0x1040)),s"line requests $lines")
      val expected=for(v<-0 until 2;c<-0 until 3)yield(v,c,BigInt((0x103c+v*16+c*4)/4))
      assert(seen.toSeq==expected,s"loaded words $seen");assert(pending.isEmpty && replyAt.isEmpty);result(0,6)
      d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
      d.io.load.ready.poke(false.B);d.io.reply.valid.poke(false.B)
      configure();submit();until("memory offer")(d.io.memoryRequest.valid.peek().litToBoolean)
      val offer=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offer);d.io.completion.valid.expect(false.B);d.clock.step()}
      d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(true.B);until("cancelled memory response")(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step();d.io.memoryResponse.valid.poke(false.B);result(4,0)
      configure();submit();until("load cancellation memory offer")(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(true.B);until("load cancellation memory reply")(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step();d.io.memoryResponse.valid.poke(false.B);until("held load")(d.io.load.valid.peek().litToBoolean)
      val heldPatch=d.io.load.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.load.valid.expect(true.B);assert(d.io.load.bits.peek().litValue==heldPatch);d.io.completion.valid.expect(false.B);d.clock.step()}
      d.io.load.ready.poke(true.B);d.clock.step();d.io.load.ready.poke(false.B);d.io.reply.valid.poke(true.B)
      until("cancelled patch reply")(d.io.reply.ready.peek().litToBoolean);d.clock.step();d.io.reply.valid.poke(false.B);result(4,0)
      configure();submit();until("patch fault memory offer")(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(true.B)
      until("patch fault memory reply")(d.io.memoryResponse.ready.peek().litToBoolean);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      until("patch fault load")(d.io.load.valid.peek().litToBoolean);d.io.load.ready.poke(true.B);d.clock.step();d.io.load.ready.poke(false.B)
      d.io.reply.bits.result.status.poke(1.U);d.io.reply.valid.poke(true.B);until("patch fault reply")(d.io.reply.ready.peek().litToBoolean)
      d.clock.step();d.io.reply.valid.poke(false.B);d.io.reply.bits.result.status.poke(0.U);result(3,0)
      configure();submit();until("fault offer")(d.io.memoryRequest.valid.peek().litToBoolean);d.io.memoryRequest.ready.poke(true.B)
      d.clock.step();d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.bits.fault.poke(true.B);d.io.memoryResponse.valid.poke(true.B)
      until("fault response")(d.io.memoryResponse.ready.peek().litToBoolean);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.fault.poke(false.B);until("fault completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.faultAddress.expect(0x103c.U);result(2,0)
      configure();d.io.task.bits.components.poke(0.U);submit();result(0,0)
    }
  }
}
