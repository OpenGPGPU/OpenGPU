package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitStorageFaultSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private val load = (BigInt(15)<<26)|(BigInt(1)<<25)|(BigInt(4)<<20)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
  for(enabled <- Seq(false,true)) {
    it should s"trap disabled or out-of-range storage access before data traffic enabled=$enabled" in {
      simulate(new GpuComputeUnit(GpuConfig(lanes=4,warps=2),finishOnTrap=true,textureAvailable=false,
        imageAvailable=true,storageAvailable=true,nativeExecutionProfile=true,
        instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d =>
        zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.l1InvalidateDone.ready.poke(true.B)
        val memory=mutable.Map.empty[BigInt,Int]
        def put(a: BigInt,v: BigInt): Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
        def line(a: BigInt): BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
        val program=Vector(BigInt("0104f057",16),vector(23,3,2,0),BigInt("10000513",16),
          vector(23,4,4,10),load,BigInt("30500073",16))
        program.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
        val words=Array.fill[BigInt](16)(0)
        for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
          6->0,7->1,8->1,9->1,10->0x9000,11->0x9100,13->0x9000,14->0x9100))words(i)=v
        words.zipWithIndex.foreach{case(v,i)=>put(0x8000+i*4,v)}
        val task=d.io.storage.get;task.enabled.poke(enabled.B);task.table.version.poke(1.U)
        task.table.base.poke(0x8000.U);task.table.limit.poke(0x8100.U)
        task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U);task.table.identity.generation.poke(13.U)
        d.io.kernel.bits.kernelPc.poke(0x1000.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)4 else 1).U)}
        d.io.kernel.valid.poke(true.B);d.io.kernel.ready.expect(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
        zero(task);d.io.storageLease.get.expect(enabled.B)
        case class Reply(id:BigInt,data:BigInt)
        val pending=mutable.Queue.empty[Reply];var cycle=0;var descriptors=0;var dataRequests=0
        def tick(): Unit={
          d.io.memoryRequest.ready.poke((cycle%3!=0).B);d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
          pending.headOption.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)}
          if(pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue;val a=r.address.peek().litValue
            r.isWrite.expect(false.B)
            if(id==8){assert(a==0x8000);descriptors+=1}
            if(id==9)dataRequests+=1
            assert(id<4 || id==8 || id==9);pending.enqueue(Reply(id,line(a)))
          }
          d.clock.step();cycle+=1
        }
        while(!d.io.trap.valid.peek().litToBoolean && cycle<10000)tick()
        d.io.trap.valid.expect(true.B);d.io.trap.bits.pc.expect(0x1010.U)
        d.io.trap.bits.cause.expect((if(enabled)5 else 2).U)
        if(!enabled)d.io.trap.bits.tval.expect(load.U)
        val held=d.io.trap.bits.peek().litValue
        for(_<-0 until 5){tick();assert(d.io.trap.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);d.io.storageLease.get.expect(enabled.B)}
        d.io.trap.ready.poke(true.B);tick();d.io.trap.ready.poke(false.B)
        while(!d.io.completion.valid.peek().litToBoolean && cycle<20000)tick()
        d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(false.B)
        assert(dataRequests==0 && descriptors==(if(enabled)1 else 0) && pending.isEmpty)
        d.io.storageLease.get.expect(enabled.B);d.io.completion.ready.poke(true.B);tick()
        d.io.storageLease.get.expect(false.B)
      }
    }
  }
}
