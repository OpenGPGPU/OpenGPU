package opengpu.core

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.trap.ShaderTrapRetirement
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ShaderTrapRetirementSpec extends AnyFlatSpec {
  for(config<-Seq(GpuConfig(lanes=4,warps=2),GpuConfig()))
  it should s"hold trap ownership through peer cease and defer coincident retirement lanes=${config.lanes} warps=${config.warps}" in {
    simulate(new ShaderTrapRetirement(config)){d=>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
      d.io.normalFinish.valid.poke(false.B);d.io.normalFinish.bits.poke(0.U);d.io.kernelStart.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(collision<-Seq(false,true)){
        d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B);d.io.failed.expect(false.B)
        d.io.in.bits.warpId.poke(1.U);d.io.in.bits.pc.poke(0x1004.U)
        d.io.in.bits.tval.poke(0x00100073.U);d.io.in.bits.activeMask.poke(((BigInt(1)<<config.lanes)-1).U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        d.io.out.valid.expect(true.B);val held=d.io.out.bits.peek().litValue
        for(i<-0 until 9){
          d.io.normalFinish.valid.poke((!collision && i==3).B)
          d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)
          d.io.finish.valid.expect((!collision && i==3).B)
          if(!collision && i==3)d.io.finish.bits.expect(0.U)
          d.io.failed.expect(false.B);d.clock.step()
        }
        d.io.normalFinish.valid.poke(collision.B);d.io.out.ready.poke(true.B)
        d.io.finish.valid.expect(collision.B);if(collision)d.io.finish.bits.expect(0.U)
        d.clock.step();d.io.out.ready.poke(false.B);d.io.normalFinish.valid.poke(false.B)
        d.io.failed.expect(true.B);d.io.out.valid.expect(false.B)
        d.io.finish.valid.expect(true.B);d.io.finish.bits.expect(1.U)
        d.clock.step()
        for(_<-0 until 7){d.io.finish.valid.expect(false.B);d.io.out.valid.expect(false.B);d.clock.step()}
      }
      info(s"SHADER_TRAP_RETIREMENT lanes=${config.lanes} warps=${config.warps} heldTrapAcrossPeerCease=true normalFinishPriority=true coincidentTrapRetained=true oneFinishPerOwner=true kernelFailureReset=true")
    }
  }
  for(config<-Seq(GpuConfig(lanes=4,warps=8),GpuConfig()))
  it should s"retain all independent trap and cease owners through sustained backpressure lanes=${config.lanes}" in {
    simulate(new ShaderTrapRetirement(config)){d=>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
      d.io.normalFinish.valid.poke(false.B);d.io.normalFinish.bits.poke(0.U);d.io.kernelStart.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val offered=mutable.Queue(0,2,4,6);val expected=mutable.Set.empty[Int];val retired=mutable.Set.empty[Int]
      val normal=Map(4->1,5->3,9->5,10->7);var publicTraps=0;var priorHeld=Option.empty[BigInt]
      for(cycle<-0 until 40){
        d.io.in.valid.poke(offered.nonEmpty.B)
        if(offered.nonEmpty){val warp=offered.front;d.io.in.bits.warpId.poke(warp.U);d.io.in.bits.pc.poke((0x1000+warp*4).U)}
        d.io.out.ready.poke((cycle%4==1).B)
        d.io.normalFinish.valid.poke(normal.contains(cycle).B);d.io.normalFinish.bits.poke(normal.getOrElse(cycle,0).U)
        priorHeld.foreach(value=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==value)})
        normal.get(cycle).foreach(warp=>{assert(!retired(warp));expected+=warp})
        val valid=d.io.out.valid.peek().litToBoolean;val ready=d.io.out.ready.peek().litToBoolean
        if(valid && ready){val warp=d.io.out.bits.warpId.peek().litValue.toInt
          d.io.out.bits.pc.expect((0x1000+warp*4).U);assert(!retired(warp));expected+=warp;publicTraps+=1}
        if(d.io.finish.valid.peek().litToBoolean){val warp=d.io.finish.bits.peek().litValue.toInt
          assert(expected.remove(warp) && retired.add(warp),s"unexpected or duplicate finish $warp at $cycle")}
        priorHeld=if(valid && !ready)Some(d.io.out.bits.peek().litValue)else None
        if(d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean)offered.dequeue()
        d.clock.step()
      }
      assert(offered.isEmpty && expected.isEmpty && retired.size==8 && publicTraps==4)
      d.io.failed.expect(true.B)
      info(s"SHADER_TRAP_BURST lanes=${config.lanes} warps=8 traps=4 normalFinishes=4 retiredOwners=8 heldOffersStable=true coincidentFinishesSerialized=true noLossNoDuplicates=true")
    }
  }
}
