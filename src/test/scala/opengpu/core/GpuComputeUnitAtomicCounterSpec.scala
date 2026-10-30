package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.{AtomicMemoryOp, StorageDescriptorLayout}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitAtomicCounterSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def counter(fn: Int, rd: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(2)<<15)|(BigInt(rd)<<7)|0x2b

  for ((translated, denied) <- Seq((false,false),(true,false),(true,true))) {
    it should s"execute task-owned atomic counter instructions through the CU translated=$translated denied=$denied" in {
      val config = GpuConfig(lanes=4,warps=2)
      simulate(new GpuComputeUnit(config,finishOnTrap=true,textureAvailable=false,
        taskTextureAvailability=true,nativeResourcesAvailable=false,imageAvailable=true,storageAvailable=true,
        computeUniformAvailable=true,atomicCounterAvailable=true,nativeExecutionProfile=true,
        instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d =>
        zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.l1InvalidateDone.ready.poke(true.B)
        val memory=mutable.Map.empty[BigInt,Int]
        def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
        def line(a:BigInt):BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
        val program=Vector(BigInt("0104f057",16),vector(23,3,2,3),counter(18,10),counter(19,11),counter(17,12),BigInt("30500073",16))
        program.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
        val rootP=if(translated)0x408000 else 0x8000
        val words=Array.fill[BigInt](16)(0)
        for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
          6->0,7->1,8->1,9->1,10->0x2000,11->0x2020,13->0x2000,14->0x2040))words(i)=v
        words.zipWithIndex.foreach{case(v,i)=>put(rootP+i*4,v)}
        if(translated){put(0x90000,(BigInt(0x400)<<10)|(if(denied)0xc9 else 0xc7));d.io.vectorSatp.poke("h80000090".U)}
        val task=d.io.atomicCounter.get;task.enabled.poke(true.B);task.table.version.poke(1.U)
        task.table.base.poke(0x8000.U);task.table.limit.poke(0x8040.U)
        task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U);task.table.identity.generation.poke(13.U)
        d.io.kernel.bits.kernelPc.poke(0x1000.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)4 else 1).U)}
        d.io.kernel.valid.poke(true.B);d.io.kernel.ready.expect(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
        zero(task);d.io.vectorSatp.poke("h80000040".U);d.io.atomicCounterLease.get.expect(true.B)
        case class Reply(id:BigInt,data:BigInt,due:Int)
        case class AtomicReply(warp:BigInt,old:BigInt,due:Int)
        val pending=mutable.Queue.empty[Reply];val atomicPending=mutable.Queue.empty[AtomicReply]
        var cycle=0;var walks=0;var descriptors=0;var atomics=0;var value=BigInt(0);var trapped=false
        val results=mutable.Map.empty[Int,Seq[BigInt]]
        while(!d.io.completion.valid.peek().litToBoolean && cycle<20000){
          d.io.memoryRequest.ready.poke((cycle%3!=0).B)
          val reply=pending.headOption.filter(_.due<=cycle);d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
          if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val q=d.io.memoryRequest.bits;val id=q.transactionId.peek().litValue;val address=q.address.peek().litValue
            if(id==8){assert(address==rootP);descriptors+=1}
            if(id==10){assert(translated && address==0x90000);walks+=1}
            assert(id<4 || id==8 || id==10,s"unexpected transaction $id at $address")
            pending.enqueue(Reply(id,line(address),cycle+3))
          }
          d.io.globalAtomicRequest.ready.poke((cycle%4!=0).B)
          val ar=atomicPending.headOption.filter(_.due<=cycle);d.io.globalAtomicResponse.valid.poke(ar.nonEmpty.B)
          ar.foreach(r=>{d.io.globalAtomicResponse.bits.warpId.poke(r.warp.U);d.io.globalAtomicResponse.bits.oldValue.poke(r.old.U)})
          if(ar.nonEmpty && d.io.globalAtomicResponse.ready.peek().litToBoolean)atomicPending.dequeue()
          if(d.io.globalAtomicRequest.valid.peek().litToBoolean && d.io.globalAtomicRequest.ready.peek().litToBoolean){
            val q=d.io.globalAtomicRequest.bits;q.address.expect((if(translated)0x40200c else 0x200c).U)
            q.operation.expect(AtomicMemoryOp.add);val old=value;value=(value+q.operand.peek().litValue)&BigInt("ffffffff",16)
            atomicPending.enqueue(AtomicReply(q.warpId.peek().litValue,old,cycle+3));atomics+=1
          }
          if(d.io.committedVectorWriteback.valid.peek().litToBoolean){val w=d.io.committedVectorWriteback.bits;val vd=w.vd.peek().litValue.toInt
            if(Set(10,11,12)(vd))results(vd)=(0 until 4).map(i=>w.data(i).peek().litValue)}
          if(d.io.trap.valid.peek().litToBoolean){assert(denied);d.io.trap.bits.pc.expect(0x1008.U);trapped=true;d.io.trap.ready.poke(true.B)}
          d.clock.step();cycle+=1
        }
        d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect((!denied).B)
        assert(pending.isEmpty && atomicPending.isEmpty && (if(translated)walks>0 else walks==0))
        if(denied)assert(trapped && descriptors==0 && atomics==0 && results.isEmpty)
        else {assert(!trapped && descriptors==12 && atomics==12 && value==0)
          assert(results(10)==Seq(0,1,2,3) && results(11)==Seq(3,2,1,0) && results(12)==Seq(0,0,0,0))}
        d.io.atomicCounterLease.get.expect(true.B);d.io.completion.ready.poke(true.B);d.clock.step();d.io.atomicCounterLease.get.expect(false.B)
      }
    }
  }
}
