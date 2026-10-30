package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuCommandMmioComputeTextureSpec extends AnyFlatSpec {
  private val R=ComputeTextureCommandMmioRegs
  private def init(d:GpuCommandMmio):Unit={
    d.io.reg.req.valid.poke(false.B);d.io.reg.resp.ready.poke(true.B);d.io.command.ready.poke(false.B)
    d.io.completion.valid.poke(false.B);d.io.completion.bits.poke(0.U.asTypeOf(d.io.completion.bits))
    d.io.resetDone.poke(false.B);d.io.vmContextIdle.foreach(_.poke(true.B))
    d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
  }
  private def access(d:GpuCommandMmio,a:Int,v:BigInt=0,write:Boolean=true,ok:Boolean=true):BigInt={
    d.io.reg.req.bits.addr.poke(a.U);d.io.reg.req.bits.data.poke(v.U);d.io.reg.req.bits.strb.poke(15.U)
    d.io.reg.req.bits.isWrite.poke(write.B);d.io.reg.req.valid.poke(true.B);d.io.reg.resp.bits.ok.expect(ok.B)
    val out=d.io.reg.resp.bits.data.peek().litValue;d.clock.step();d.io.reg.req.valid.poke(false.B);out
  }
  it should "capture independent compute texture publications and reject malformed writes" in {
    simulate(new GpuCommandMmio(GpuConfig(lanes=4,warps=2),queueDepth=2,
      protectVmState=true,imageAvailable=true,storageAvailable=true,computeUniformAvailable=true,computeTextureAvailable=true)){d=>
      init(d);assert(access(d,R.MAGIC,write=false)==R.magic)
      def stage(id:Int):Unit={
        access(d,GpuCommandMmioRegs.COMMAND_ID,id);access(d,GpuCommandMmioRegs.OPCODE,
          if(id==1)GpuCommandOpcode.kernel.litValue else GpuCommandOpcode.kernelIndirect.litValue)
        access(d,R.CONTROL,0x101);access(d,R.ROOT,id*0x1000);access(d,R.CONTEXT,7+id)
        access(d,R.PASS,11+id);access(d,R.GENERATION,13+id)
        access(d,GpuCommandMmioRegs.SUBMIT,1)
      }
      stage(1);stage(2);access(d,R.ROOT,0x9000);access(d,R.CONTROL,0x103,ok=false)
      access(d,R.MAGIC,0,ok=false)
      for(id<-1 to 2){
        d.io.command.valid.expect(true.B);val u=d.io.command.bits.computeTexture.get
        u.enabled.expect(true.B);u.resources.version.expect(1.U);u.resources.address.expect((id*0x1000).U)
        u.identity.context.expect((7+id).U);u.identity.pass.expect((11+id).U);u.identity.generation.expect((13+id).U)
        val held=d.io.command.bits.peek().litValue;d.clock.step(4);assert(d.io.command.bits.peek().litValue==held)
        access(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000001L,ok=false)
        d.io.command.ready.poke(true.B);d.clock.step();d.io.command.ready.poke(false.B)
      }
      access(d,R.CONTROL,0x101);access(d,GpuCommandMmioRegs.RESET,1)
      assert(access(d,R.CONTROL,write=false)==0)
    }
  }
  it should "leave compute texture staging unmapped when disabled" in {
    simulate(new GpuCommandMmio(GpuConfig(lanes=4,warps=2))){d=>init(d)
      for(a<-R.base until R.end by 4){assert(access(d,a,write=false,ok=false)==0);access(d,a,1,ok=false)}
    }
  }
}
