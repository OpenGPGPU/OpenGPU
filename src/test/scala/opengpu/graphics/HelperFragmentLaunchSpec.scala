package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class HelperFragmentLaunchSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  it should "snapshot helper masks and bounded private prefixes across overlapping fragment batches" in {
    val c=GpuConfig(lanes=4,warps=2);val g=GraphicsConfig(screenWidth=8,screenHeight=8)
    simulate(new KernelFragStage(c,g)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.shaderPc.poke(0x1000.U);d.io.kernargBase.poke(0x8000.U);d.io.kernargBankStride.poke(512.U)
      d.io.texWidth.poke(1.U);d.io.texHeight.poke(1.U);d.io.out.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,BigInt]
      case class Reply(id: BigInt,data: BigInt,due: Int)
      val pending=mutable.Queue.empty[Reply];var cycle=0;var outputs=0
      def tick(): Unit={
        d.io.wordMemReq.ready.poke((cycle%5<3).B)
        val r=pending.headOption.filter(_.due<=cycle)
        d.io.wordMemResp.valid.poke(r.nonEmpty.B);r.foreach(x=>{d.io.wordMemResp.bits.transactionId.poke(x.id.U);d.io.wordMemResp.bits.readData.poke(x.data.U)})
        if(r.nonEmpty && d.io.wordMemResp.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.wordMemReq.valid.peek().litToBoolean && d.io.wordMemReq.ready.peek().litToBoolean){
          val q=d.io.wordMemReq.bits;val a=q.address.peek().litValue
          if(q.isWrite.peek().litToBoolean){val v=q.writeData.peek().litValue;val mask=q.byteMask.peek().litValue;for(b<-0 until 64 if mask.testBit(b))memory(a+b)=(v>>(8*b))&255}
          val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(a+b,BigInt(0))<<(8*b)))
          pending.enqueue(Reply(q.transactionId.peek().litValue,data,cycle+3))
        }
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){d.io.out.bits.covered.expect(true.B);outputs+=1}
        d.clock.step();cycle+=1
      }
      def until(condition: => Boolean): Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,s"fragment helper launch stalled cycle=$cycle")}
      def send(mask: Int): Unit={
        for(i<-0 until 4){val lane=d.io.fragIn.bits.lanes(i);lane.covered.poke(((mask&(1<<i))!=0).B);lane.coverageMask.poke((if((mask&(1<<i))!=0)1 else 0).U)
          lane.x.poke((i%2).S);lane.y.poke((i/2).S);lane.alpha.poke(255.U)}
        d.io.fragIn.valid.poke(true.B);until(d.io.fragIn.ready.peek().litToBoolean);tick();d.io.fragIn.valid.poke(false.B)
      }
      send(5);send(13);until(d.io.kernelLaunch.valid.peek().litToBoolean)
      d.io.kernelLaunch.invocations.helpers.expect(0x2a.U);d.io.kernelLaunch.invocations.privateBase.expect(0x8000.U)
      d.io.kernelLaunch.invocations.privateBytes.expect(288.U)
      val first=d.io.kernelLaunch.invocations.peek().litValue
      send(10);send(5)
      for(_<-0 until 13){tick();d.io.kernelLaunch.valid.expect(true.B);assert(d.io.kernelLaunch.invocations.peek().litValue==first)}
      def completeKernel(): Unit={
        d.io.kernelLaunch.ready.poke(true.B);tick();d.io.kernelLaunch.ready.poke(false.B)
        for(_<-0 until 6)tick()
        d.io.kernelCompletion.bits.success.poke(true.B);d.io.kernelCompletion.valid.poke(true.B)
        until(d.io.kernelCompletion.ready.peek().litToBoolean);tick();d.io.kernelCompletion.valid.poke(false.B)
      }
      completeKernel();until(d.io.kernelLaunch.valid.peek().litToBoolean)
      d.io.kernelLaunch.invocations.helpers.expect(0xa5.U);d.io.kernelLaunch.invocations.privateBase.expect(0x8200.U)
      d.io.kernelLaunch.invocations.privateBytes.expect(288.U);completeKernel()
      d.io.flush.poke(true.B);until(d.io.drawRetire.valid.peek().litToBoolean)
      d.io.drawRetire.ready.poke(true.B);tick();d.io.drawRetire.ready.poke(false.B);d.io.flush.poke(false.B)
      until(d.io.drained.peek().litToBoolean);assert(pending.isEmpty);assert(outputs==9)
      info("HELPER_FRAGMENT batches=2 helperMasks=2a,a5 privateBytes=288 bankSnapshots=true overlap=true heldLaunch=true coveredOutputs=9 retirement=true")
    }
  }
}
