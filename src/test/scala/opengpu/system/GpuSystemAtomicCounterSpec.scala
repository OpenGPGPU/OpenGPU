package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemAtomicCounterSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "execute contended counter increments on two CUs from direct and indirect commands" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=16,
      enableUnifiedCommands=true,enableIndirectCompute=true,storageAvailable=true,imageAvailable=true,
      computeUniformAvailable=true,computeTextureAvailable=true,atomicCounterAvailable=true,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B);d.io.graphicsShaderL1Invalidate.ready.poke(true.B);d.io.trap.foreach(_.ready.poke(true.B))
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def get(a:BigInt,n:Int):BigInt=(0 until n).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      val vectorIndex=(BigInt(23)<<26)|(BigInt(1)<<25)|(BigInt(3)<<15)|(BigInt(3)<<12)|(BigInt(2)<<7)|0x57
      val increment=(BigInt(18)<<26)|(BigInt(1)<<25)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
      Vector(BigInt("0104f057",16),vectorIndex,increment,BigInt("30500073",16)).zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      val words=Array.fill[BigInt](16)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
        6->0,7->1,8->1,9->1,10->0x2000,11->0x2020,13->0x2000,14->0x2040))words(i)=v
      words.zipWithIndex.foreach{case(v,i)=>put(0x8000+i*4,v)}
      put(0x3000,3);put(0x3004,1);put(0x3008,1)
      case class Reply(id:BigInt,address:BigInt,data:BigInt,mask:BigInt,write:Boolean,due:Int)
      val pending=mutable.Queue.empty[Reply];val results=mutable.ArrayBuffer.empty[BigInt];val completions=mutable.Set.empty[Int]
      var cycle=0;var argumentReads=0;var simultaneous=false
      def tick():Unit={
        simultaneous ||= d.io.busyComputeUnits.peek().litValue==3
        for(cu<-0 until 2){val wb=d.io.committedVectorWriteback(cu)
          if(wb.valid.peek().litToBoolean && wb.bits.vd.peek().litValue==10)
            for(lane<-0 until 4)results+=wb.bits.data(lane).peek().litValue}
        d.io.memoryRequest.ready.poke((cycle%5!=0).B)
        val reply=pending.headOption.filter(_.due<=cycle);d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){val r=pending.dequeue()
          if(r.write)for(i<-0 until 64 if r.mask.testBit(i))memory(r.address+i)=((r.data>>(8*i))&255).toInt}
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.memoryRequest.bits;val a=q.address.peek().litValue;val write=q.isWrite.peek().litToBoolean
          if(!write && a==0x3000)argumentReads+=1
          pending.enqueue(Reply(q.transactionId.peek().litValue,a,if(write)q.writeData.peek().litValue else get(a,64),
            q.byteMask.peek().litValue,write,cycle+(if(write)19 else 3)))
        }
        if(d.io.gpuCompletion.valid.peek().litToBoolean && d.io.gpuCompletion.ready.peek().litToBoolean){
          d.io.gpuCompletion.bits.success.expect(true.B);val id=d.io.gpuCompletion.bits.commandId.peek().litValue.toInt
          assert(!completions(id));completions+=id}
        d.io.trap.foreach(_.valid.expect(false.B));d.clock.step();cycle+=1
      }
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<60000){tick();n+=1};assert(p,s"$label stalled after $cycle cycles")}
      for(id<-1 to 2){val cmd=d.io.gpuCommand.bits;zero(cmd);cmd.commandId.poke(id.U)
        cmd.opcode.poke(if(id==1)GpuCommandOpcode.kernel else GpuCommandOpcode.kernelIndirect)
        if(id==2){cmd.sourceAddress.poke(0x3000.U);cmd.bytes.poke(12.U)}
        cmd.launch.kernelPc.poke(0x1000.U)
        for(axis<-0 until 3){cmd.launch.gridSize(axis).poke((if(axis==0)3 else 1).U);cmd.launch.localSize(axis).poke((if(axis==0)4 else 1).U)}
        val t=cmd.atomicCounter.get;t.enabled.poke(true.B);t.table.version.poke(1.U);t.table.base.poke(0x8000.U);t.table.limit.poke(0x8040.U)
        t.table.identity.context.poke(7.U);t.table.identity.pass.poke(11.U);t.table.identity.generation.poke(13.U)
        d.io.gpuCommand.valid.poke(true.B);until("command admission")(d.io.gpuCommand.ready.peek().litToBoolean);tick();d.io.gpuCommand.valid.poke(false.B)}
      zero(d.io.gpuCommand.bits);d.io.atomicCounterLease.get.expect(true.B)
      until("first completion")(d.io.gpuCompletion.valid.peek().litToBoolean)
      val held=d.io.gpuCompletion.bits.peek().litValue
      for(_<-0 until 8){tick();assert(d.io.gpuCompletion.bits.peek().litValue==held);d.io.atomicCounterLease.get.expect(true.B)}
      d.io.gpuCompletion.ready.poke(true.B);until("both commands")(completions.size==2)
      until("memory drain")(pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean)
      assert(results.size==24 && results.sorted==((0 until 24).map(BigInt(_))),s"incorrect atomic results: $results")
      assert(simultaneous && argumentReads==1,s"simultaneous=$simultaneous argumentReads=$argumentReads")
      d.io.atomicCounterLease.get.expect(false.B)
    }
  }
}
