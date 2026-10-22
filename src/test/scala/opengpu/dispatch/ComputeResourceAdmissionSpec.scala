package opengpu.dispatch

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeResourceAdmissionSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(full<-Seq(false,true))
  it should s"enforce the reported local capacity with an exact 48bit product and preserve full uint32 grid axes full=$full" in {
    val config=if(full)GpuConfig()else GpuConfig(lanes=4,warps=2)
    val capacity=if(full)128 else 8
    simulate(new GpuCommandProcessor(config,commandIdWidth=5,commandQueueDepth=2,completionQueueDepth=2)) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean): Unit={var n=0;while(!p && n<60){d.clock.step();n+=1};assert(p)}
      case class Case(local: Seq[Int],grid: Seq[BigInt]=Seq.fill(3)(BigInt("ffffffff",16)),pc: Int=0x1000,kernarg: Int=0x8000,status: Int=0)
      val valid=Seq(Case(Seq(capacity,1,1)),Case(Seq(1,capacity,1)),Case(Seq(1,1,capacity)),
        Case(if(full)Seq(4,4,8)else Seq(2,2,2)),Case(Seq(1,1,1),Seq(BigInt("80000000",16),1,1)))
      val invalid=Seq(Case(Seq(capacity+1,1,1),status=4),Case(Seq(capacity,2,1),status=4),
        Case(Seq(65535,65535,65535),status=4),Case(Seq(65535,65535,2),status=4),
        Case(Seq(0,1,1),status=4),Case(Seq(1,1,1),grid=Seq(1,0,1),status=3),
        Case(Seq(1,1,1),pc=0x1002,status=2),Case(Seq(1,1,1),kernarg=0x8002,status=5))
      for((c,id)<-(valid++invalid).zipWithIndex){
        d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
        d.io.command.bits.commandId.poke(id.U)
        d.io.command.bits.launch.kernelPc.poke(c.pc.U);d.io.command.bits.launch.kernargAddress.poke(c.kernarg.U)
        for(i<-0 until 3){d.io.command.bits.launch.gridSize(i).poke(c.grid(i).U);d.io.command.bits.launch.localSize(i).poke(c.local(i).U)}
        d.io.command.valid.poke(true.B);until(d.io.command.ready.peek().litToBoolean)
        d.clock.step();d.io.command.valid.poke(false.B)
        d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
        if(c.status==0){
          until(d.io.dispatch.valid.peek().litToBoolean)
          d.io.dispatch.bits.commandId.expect(id.U)
          d.io.dispatch.bits.launch.kernelPc.expect(c.pc.U);d.io.dispatch.bits.launch.kernargAddress.expect(c.kernarg.U)
          for(i<-0 until 3){d.io.dispatch.bits.launch.gridSize(i).expect(c.grid(i).U);d.io.dispatch.bits.launch.localSize(i).expect(c.local(i).U)}
          val held=d.io.dispatch.bits.peek().litValue
          for(_<-0 until 7){d.io.dispatch.valid.expect(true.B);assert(d.io.dispatch.bits.peek().litValue==held);d.clock.step()}
          d.io.dispatch.ready.poke(true.B);d.clock.step();d.io.dispatch.ready.poke(false.B)
          d.io.dispatchCompletion.bits.commandId.poke(id.U);d.io.dispatchCompletion.bits.success.poke(true.B)
          d.io.dispatchCompletion.valid.poke(true.B);until(d.io.dispatchCompletion.ready.peek().litToBoolean)
          d.clock.step();d.io.dispatchCompletion.valid.poke(false.B)
        } else {
          until { d.io.dispatch.valid.expect(false.B);d.io.completion.valid.peek().litToBoolean }
        }
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.commandId.expect(id.U);d.io.completion.bits.status.expect(c.status.U)
        d.io.completion.bits.success.expect((c.status==0).B)
        val held=d.io.completion.bits.peek().litValue
        d.io.command.bits.commandId.poke(id.U);d.io.command.valid.poke(true.B)
        for(_<-0 until 5){d.io.command.ready.expect(false.B);d.io.duplicateCommandId.expect(true.B)
          assert(d.io.completion.bits.peek().litValue==held);d.clock.step()}
        d.io.completion.ready.poke(true.B);d.clock.step()
        d.io.command.valid.poke(false.B);d.io.completion.ready.poke(false.B)
      }
      d.io.inFlight.expect(0.U)
      info(s"COMPUTE_RESOURCE_ADMISSION full=$full capacity=$capacity cases13=true exactAxisAnd3DMaximum=true fullUint32Grid=true topBitPreserved=true product48NoWrap=true invalidNoCUDispatch=true heldSnapshots=true duplicateUntilCompletionPop=true")
    }
  }
}
