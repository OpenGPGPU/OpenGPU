package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.system.GpuSystem
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ComputeTextureSystemSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int,vs2:Int=0):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  it should "retain sixteen compute texture bindings across direct and indirect multi-CU workgroups" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=16,
      enableUnifiedCommands=true,enableIndirectCompute=true,imageAvailable=true,storageAvailable=true,
      computeUniformAvailable=true,computeTextureAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
      vectorCacheSets=4,vectorCacheWays=1)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B);d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.foreach(_.ready.poke(true.B));d.io.memoryRequest.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def byte(a:BigInt,v:Int):Unit={memory(a)=v}
      def line(a:BigInt):BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      val expected=mutable.Map.empty[(Int,Int),BigInt]
      for(id<-1 to 2) {
        val offset=BigInt(id)*0x100000
        roots(put,stage=2,offset=offset);coordinates(put,offset=offset)
        for(unit<-0 until 16) {
          val view=install(put,byte,unit,3,stage=2,offset=offset,raw=BigInt(0xff102000L+id*32+unit))
          expected((id,unit))=view.expected(0)
        }
        val packetAddress=packet+offset
        val program=Vector(BigInt("0104f057",16),((packetAddress >> 12)<<12)|(BigInt(1)<<7)|0x37,
          vector(23,4,2,1)) ++ (0 until 16).flatMap { unit =>
          val fetch=(BigInt(2)<<26)|(BigInt(1)<<25)|(BigInt(3)<<20)|(BigInt(2)<<15)|(BigInt(10+unit)<<7)|0x2b
          Vector(vector(23,3,3,unit),fetch)
        } ++ Vector(BigInt("30500073",16))
        program.zipWithIndex.foreach{case(v,i)=>put(id*0x1000+i*4,v)}
      }
      put(0x3000,3);put(0x3004,1);put(0x3008,1)
      case class Reply(id:BigInt,data:BigInt,due:Int)
      val pending=mutable.Queue.empty[Reply];val results=mutable.Map.empty[(Int,BigInt),Int].withDefaultValue(0)
      val completed=mutable.Set.empty[Int];var cycle=0;var indirectReads=0;var bothBusy=false
      def tick():Unit={
        bothBusy ||= d.io.busyComputeUnits.peek().litValue==3
        for(cu<-0 until 2){val wb=d.io.committedVectorWriteback(cu)
          if(wb.valid.peek().litToBoolean){val rd=wb.bits.vd.peek().litValue.toInt
            if(rd>=10 && rd<26){val unit=rd-10;val value=wb.bits.data(0).peek().litValue
              assert(value==expected((1,unit)) || value==expected((2,unit)))
              for(lane<-0 until 4)wb.bits.data(lane).expect(value.U)
              results((unit,value))+=1}}}
        d.io.memoryRequest.ready.poke((cycle%5!=0).B)
        val reply=pending.headOption.filter(_.due<=cycle);d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.memoryRequest.bits;val a=q.address.peek().litValue;q.isWrite.expect(false.B)
          if(a==0x3000)indirectReads+=1
          pending.enqueue(Reply(q.transactionId.peek().litValue,line(a),cycle+3))
        }
        if(d.io.gpuCompletion.valid.peek().litToBoolean && d.io.gpuCompletion.ready.peek().litToBoolean){
          d.io.gpuCompletion.bits.success.expect(true.B);completed+=d.io.gpuCompletion.bits.commandId.peek().litValue.toInt}
        d.io.trap.foreach(_.valid.expect(false.B));d.clock.step();cycle+=1
      }
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<100000){tick();n+=1};assert(p,s"$label stalled")}
      for(id<-1 to 2){val cmd=d.io.gpuCommand.bits;zero(cmd);cmd.commandId.poke(id.U)
        cmd.opcode.poke(if(id==1)GpuCommandOpcode.kernel else GpuCommandOpcode.kernelIndirect)
        if(id==2){cmd.sourceAddress.poke(0x3000.U);cmd.bytes.poke(12.U)}
        cmd.launch.kernelPc.poke((id*0x1000).U)
        for(axis<-0 until 3){cmd.launch.gridSize(axis).poke((if(id==2)0xdead else if(axis==0)3 else 1).U)
          cmd.launch.localSize(axis).poke((if(axis==0)4 else 1).U)}
        val task=cmd.computeTexture.get;task.enabled.poke(true.B);task.resources.version.poke(1.U)
        task.resources.address.poke((root+BigInt(id)*0x100000).U)
        task.identity.context.poke(7.U);task.identity.pass.poke(11.U);task.identity.generation.poke(13.U)
        d.io.gpuCommand.valid.poke(true.B);until("command admission")(d.io.gpuCommand.ready.peek().litToBoolean)
        tick();d.io.gpuCommand.valid.poke(false.B)}
      zero(d.io.gpuCommand.bits);d.io.computeTextureLease.get.expect(true.B)
      until("held completion")(d.io.gpuCompletion.valid.peek().litToBoolean)
      val held=d.io.gpuCompletion.bits.peek().litValue
      for(_<-0 until 8){tick();assert(d.io.gpuCompletion.bits.peek().litValue==held);d.io.computeTextureLease.get.expect(true.B)}
      d.io.gpuCompletion.ready.poke(true.B);until("both completions")(completed.size==2)
      until("memory drain")(pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean)
      val wanted=(for(id<-1 to 2; unit<-0 until 16)yield (unit,expected((id,unit)))->3).toMap
      assert(results.toMap==wanted && bothBusy && indirectReads==1,s"results=$results indirectReads=$indirectReads")
      d.io.computeTextureLease.get.expect(false.B)
    }
  }
}
