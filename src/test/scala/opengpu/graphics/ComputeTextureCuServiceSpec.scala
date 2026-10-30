package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.memory.ComputeTextureCuService
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ComputeTextureCuServiceSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  for ((translated, denied) <- Seq((false,false),(true,false),(true,true))) {
    it should s"capture compute texture resources and translation with Sv32=$translated denied=$denied" in {
      simulate(new ComputeTextureCuService(GpuConfig(lanes=4,warps=2))) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        val memory=mutable.Map.empty[BigInt,Int]
        def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
        def byte(a:BigInt,v:Int):Unit={memory(a)=v}
        roots(put,stage=2); val view=install(put,byte,15,format=3,stage=2); coordinates(put)
        d.io.task.enabled.poke(true.B);d.io.task.resources.version.poke(1.U);d.io.task.resources.address.poke(root.U)
        d.io.task.identity.context.poke(7.U);d.io.task.identity.pass.poke(11.U);d.io.task.identity.generation.poke(13.U)
        d.io.satp.poke((if(translated) BigInt("80000080",16) else BigInt(0)).U)
        d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B)
        d.io.task.enabled.poke(false.B);d.io.task.resources.address.poke(0.U);d.io.satp.poke(0.U)
        d.io.enabled.expect(true.B)
        val q=d.io.in.bits;zero(q);q.decode.instruction.poke(scalar(2).U)
        q.decode.warpId.poke(1.U);q.decode.pc.poke(0x400.U);q.decode.activeMask.poke(15.U)
        q.rs1Data.poke(packet.U);q.rs2Data.poke(15.U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        case class Reply(id:BigInt,data:BigInt,due:Int)
        val pending=mutable.Queue.empty[Reply];var walks=0;var reads=0;var cycle=0
        while(!d.io.commit.valid.peek().litToBoolean && cycle<15000){
          d.io.memoryRequest.ready.poke((cycle%3!=0).B)
          val reply=pending.headOption.filter(_.due<=cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U) }
          if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue;val address=r.address.peek().litValue
            r.isWrite.expect(false.B)
            val value=if(id==2){assert(translated && address==0x80000);walks+=1;(BigInt(0x400)<<10)|(if(denied) 0xc9 else 0xc3)}
              else {assert(id<2);reads+=1;val virtual=address-(if(translated) BigInt(0x400000) else BigInt(0));
                (0 until 64).map(i=>BigInt(memory.getOrElse(virtual+i,0))<<(8*i)).sum}
            pending.enqueue(Reply(id,value,cycle+3))
          }
          d.clock.step();cycle+=1
        }
        d.io.commit.valid.expect(true.B);d.io.responseFault.expect(denied.B);d.io.commit.bits.writeRd.expect((!denied).B)
        if(denied){d.io.faultAddress.expect(root.U);assert(reads==0)}
        else {d.io.commit.bits.data.expect(view.expected(2).U);assert(reads>0)}
        assert(pending.isEmpty && (if(translated) walks>0 else walks==0))
        val held=d.io.commit.bits.peek().litValue;d.clock.step(3);assert(d.io.commit.bits.peek().litValue==held)
        d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
        d.io.busy.expect(false.B);d.io.kernelCompletion.poke(true.B);d.clock.step()
      }
    }
  }
}
