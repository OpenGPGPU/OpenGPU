package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.memory.ComputeUniformCuService
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeUniformCuServiceSpec extends AnyFlatSpec {
  import WindowRecordTest._
  for ((translated, denied) <- Seq((false, false), (true, false), (true, true))) {
    it should s"capture compute uniform publication and Sv32 owner with translation=$translated denied=$denied" in {
      simulate(new ComputeUniformCuService(GpuConfig(lanes=4,warps=2))) { d =>
        zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
        d.io.task.enabled.poke(true.B);d.io.task.resources.version.poke(1.U)
        d.io.task.resources.address.poke(0x1000.U)
        d.io.task.identity.context.poke(7.U);d.io.task.identity.pass.poke(11.U)
        d.io.task.identity.generation.poke(13.U)
        d.io.satp.poke((if(translated) BigInt("80000080",16) else BigInt(0)).U)
        d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B)
        d.io.task.enabled.poke(false.B);d.io.task.resources.address.poke(0.U);d.io.satp.poke(0.U)
        d.io.enabled.expect(true.B)
        val q=d.io.in.bits;zero(q);q.decode.instruction.poke(((BigInt(7)<<25)|(BigInt(7)<<7)|0x0b).U)
        q.decode.warpId.poke(1.U);q.decode.pc.poke(0x400.U);q.decode.activeMask.poke(15.U)
        q.rs1Data.poke(0.U);q.rs2Data.poke(0.U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        def record(magic: BigInt): Array[BigInt] = {
          val w=Array.fill[BigInt](16)(0);w(0)=magic;w(1)=64;w(2)=1;w(3)=7;w(4)=11;w(5)=13;w
        }
        def pack(w:Array[BigInt]):BigInt=w.zipWithIndex.map { case(v,i) => v << (32*i) }.sum
        val root=record(BigInt("47555201",16));root(6)=2;root(7)=1;root(10)=0x2000;root(11)=0x2600
        val binding=record(BigInt("47554201",16));binding(6)=0;binding(7)=4;binding(8)=0x10000
        binding(9)=0x10100;binding(11)=0x10000;binding(12)=0x10100;binding(14)=1
        val physical=if(translated) 0x400000 else 0
        var pending:Option[(BigInt,BigInt)]=None;var walks=0;var reads=0;var cycle=0
        while(!d.io.commit.valid.peek().litToBoolean && cycle<500){
          d.io.memoryRequest.ready.poke((cycle%3!=0).B);d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
          pending.foreach{case(id,data)=>d.io.memoryResponse.bits.transactionId.poke(id.U);d.io.memoryResponse.bits.readData.poke(data.U)}
          val taken=pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
          val next=if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            assert(pending.isEmpty || taken);val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue
            val a=r.address.peek().litValue.toInt;r.isWrite.expect(false.B)
            val value=if(id==1){assert(translated && a==0x80000);walks+=1;(BigInt(0x400)<<10)|(if(denied) 0xc9 else 0xc3)}
              else {assert(id==0);reads+=1;a match {
                case x if x==physical+0x1000 => pack(root)
                case x if x==physical+0x2000 => pack(binding)
                case x if x==physical+0x10000 => BigInt("12345678",16)
                case _ => fail(s"unexpected physical address $a")
              }}
            Some(id->value)
          }else None
          if(taken)pending=None;d.clock.step();cycle+=1;next.foreach(x=>pending=Some(x))
        }
        d.io.commit.valid.expect(true.B);d.io.status.expect((if(denied) 3 else 0).U)
        d.io.commit.bits.writeRd.expect((!denied).B)
        if(denied) {
          d.io.faultSource.expect(1.U);d.io.faultAddressValid.expect(true.B)
          d.io.faultAddress.expect(0x1000.U);assert(reads==0)
        } else { d.io.commit.bits.data.expect("h12345678".U);assert(reads==3) }
        assert(pending.isEmpty)
        assert(if(translated) walks>0 else walks==0)
        val held=d.io.commit.bits.peek().litValue;d.clock.step(4);assert(d.io.commit.bits.peek().litValue==held)
        d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
        d.io.busy.expect(false.B);d.io.kernelCompletion.poke(true.B);d.clock.step()
      }
    }
  }
}
