package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.NativeSimtProgram
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeSimtProgramCuSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for((lanes,warps,sets,ways)<-Seq((4,2,4,1),(16,8,64,2)))
  it should s"independently reject corrupt native SIMT results and reuse all warp slots lanes=$lanes warps=$warps" in {
    val config=GpuConfig(lanes=lanes,warps=warps)
    simulate(new GpuComputeUnit(config,instructionCacheSets=sets,instructionCacheWays=ways,
      vectorCacheSets=sets,vectorCacheWays=ways,finishOnTrap=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.memoryRequest.ready.poke(true.B);d.io.l1InvalidateDone.ready.poke(true.B)
      d.io.globalAtomicRequest.ready.poke(true.B)
      // addi x15,x14,0 externally exposes the independently reduced result.
      val body=Vector(BigInt("00070793",16),NativeSimtProgram.cease)
      val good=NativeSimtProgram.guarded(body);val bad=NativeSimtProgram.guarded(body,corrupt=true)
      val words=Seq((0x1000,good),(0x2000,bad),(0x3000,good)).flatMap{case(base,code)=>
        code.zipWithIndex.map{case(word,i)=>(base+i*4,word)}}.toMap
      case class Reply(id: BigInt,data: BigInt,due: Int)
      val pending=mutable.Queue.empty[Reply]
      val completed=Array.fill(3)(mutable.Set.empty[Int])
      var phase=0;var cycle=0;var traps=0;var peak=0;var held=0
      def tick(): Unit={
        peak=peak.max(d.io.active.peek().litValue.bitCount)
        val reply=pending.headOption.filter(_.due<=cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)}
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.memoryRequest.bits;val address=q.address.peek().litValue.toInt
          assert(!q.isWrite.peek().litToBoolean)
          assert(words.keys.exists(p=>p/64==address/64),s"unexpected fetch 0x${address.toHexString}")
          val data=(0 until 16).foldLeft(BigInt(0))((v,i)=>v|(words.getOrElse(address+4*i,NativeSimtProgram.cease)<<(32*i)))
          pending.enqueue(Reply(q.transactionId.peek().litValue,data,cycle+5))
        }
        if(d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue==15){
          assert(phase!=1,"corrupt result reached the success body")
          d.io.committedWriteback.bits.data.expect(0.U)
          val w=d.io.committedWriteback.bits.warpId.peek().litValue.toInt
          assert(!completed(phase).contains(w));completed(phase)+=w
        }
        if(d.io.trap.valid.peek().litToBoolean){
          assert(phase==1,"healthy native SIMT program faulted")
          d.io.trap.bits.cause.expect(2.U)
          d.io.trap.bits.pc.expect((0x2000+(bad.size-2)*4).U)
          d.io.trap.bits.tval.expect("hffffffff".U)
          d.io.trap.bits.activeMask.expect(((BigInt(1)<<lanes)-1).U)
          d.io.trap.ready.poke((held>=7).B)
          if(d.io.trap.ready.peek().litToBoolean){traps+=1;held=0}else held+=1
        }else d.io.trap.ready.poke(false.B)
        d.clock.step();cycle+=1
      }
      def until(c: =>Boolean): Unit={var n=0;while(!c && n<100000){tick();n+=1};assert(c,s"stalled phase=$phase cycle=$cycle")}
      for((pc,index)<-Seq(0x1000,0x2000,0x3000).zipWithIndex){
        phase=index;peak=0;held=0
        d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits));d.io.kernel.bits.kernelPc.poke(pc.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)lanes*warps else 1).U)}
        d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean);tick();d.io.kernel.valid.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.success.expect((index!=1).B);d.io.active.expect(0.U)
        assert(peak==warps,s"only $peak of $warps warp slots became active")
        val saved=d.io.completion.bits.peek().litValue
        for(_<-0 until 7){assert(d.io.completion.bits.peek().litValue==saved);tick()}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      }
      assert(traps==warps && completed(0).size==warps && completed(1).isEmpty && completed(2).size==warps && pending.isEmpty)
      info(s"NATIVE_SIMT_PROGRAM_CU lanes=$lanes warps=$warps actualPeakWarps=$peak " +
        "independentScalarReduction=true negativeControlRejectsEveryWarp=true scopeReuseAndLoops=true exactSuccessResults=true heldTraps=true healthyReuse=true")
    }
  }
}
