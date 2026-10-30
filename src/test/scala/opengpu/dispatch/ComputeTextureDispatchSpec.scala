package opengpu.dispatch

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ComputeTextureDispatchSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "retain compute texture ownership across a multi-workgroup shared CU dispatch" in {
    simulate(new SharedCuGridDispatcher(GpuConfig(lanes=4,warps=2),1,4,computeTextureAvailable=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.cuLaunch(0).ready.poke(true.B);d.io.launch.bits.commandId.poke(7.U)
      d.io.launch.bits.launch.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){d.io.launch.bits.launch.gridSize(i).poke((if(i==0)3 else 1).U);d.io.launch.bits.launch.localSize(i).poke((if(i==0)4 else 1).U)}
      val t=d.io.launch.bits.computeTexture.get;t.enabled.poke(true.B);t.resources.version.poke(1.U)
      t.resources.address.poke(0x8000.U);t.identity.context.poke(9.U);t.identity.pass.poke(11.U);t.identity.generation.poke(13.U)
      d.io.launch.valid.poke(true.B);d.io.launch.ready.expect(true.B);d.clock.step();d.io.launch.valid.poke(false.B);zero(t)
      var cycle=0;var due= -1;val seen=mutable.ArrayBuffer.empty[Int]
      while(!d.io.completion.valid.peek().litToBoolean && cycle<300){
        d.io.cuCompletion(0).valid.poke((due>=0 && cycle>=due).B);d.io.cuCompletion(0).bits.success.poke(true.B)
        if(d.io.cuCompletion(0).valid.peek().litToBoolean && d.io.cuCompletion(0).ready.peek().litToBoolean)due= -1
        if(d.io.cuLaunch(0).valid.peek().litToBoolean){
          val captured=d.io.cuComputeTexture.get(0);captured.enabled.expect(true.B);captured.resources.version.expect(1.U)
          captured.resources.address.expect(0x8000.U);captured.identity.context.expect(9.U);captured.identity.pass.expect(11.U);captured.identity.generation.expect(13.U)
          d.io.cuGridSlice(0).singleGroup.expect(true.B);seen+=d.io.cuGridSlice(0).groupId(0).peek().litValue.toInt
          assert(due<0);due=cycle+3
        }
        d.clock.step();cycle+=1
      }
      assert(seen.toVector==Vector(0,1,2));d.io.completion.valid.expect(true.B)
      d.io.completion.bits.commandId.expect(7.U);d.io.completion.bits.success.expect(true.B)
      val held=d.io.completion.bits.peek().litValue
      for(_<-0 until 4){assert(d.io.completion.bits.peek().litValue==held);d.clock.step()}
      d.io.completion.ready.poke(true.B);d.clock.step()
    }
  }
}
