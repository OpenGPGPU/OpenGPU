package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi, GpuHostTestSupport}
import opengpu.testutil.{GraphicsTestResolution, GpuSim}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeHostAxiAdmissionSpec extends AnyFlatSpec with GpuHostTestSupport {
  it should "reject malformed tessellation jobs through the real host without memory traffic" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val graphics=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)))
    simulate(new GpuHostSystemAxi(graphics,gpu,numComputeUnits=2,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      nativeBinnedGraphics=Some(n),tessellationNative=true)){d=>
      initialize(d);axiWrite(d,RenderHostRegs.IRQ,1)
      for((base,bytes,id)<-Seq((0x4000,511,41),(0x4001,512,42))){
        axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,15)
        axiWrite(d,GpuCommandMmioRegs.SOURCE,base);axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x4200)
        axiWrite(d,GpuCommandMmioRegs.PATTERN,0);axiWrite(d,GpuCommandMmioRegs.BYTES,bytes);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        var cycles=0
        while(!d.io.m_irq.peek().litToBoolean && cycles<20000){
          d.io.m_axi_arvalid.expect(false.B);d.io.m_axi_awvalid.expect(false.B);d.io.s_axi_aclk.step();cycles+=1
        }
        assert(cycles<20000,"tessellation admission did not complete")
        val meta=axiRead(d,GpuCommandMmioRegs.COMPLETION)
        val opcode=((meta>>8)&7)|(((meta>>16)&1)<<3)
        assert((meta&255)==id && opcode==15 && ((meta>>11)&15)==1 && !meta.testBit(15),s"unexpected completion $meta")
        assert(axiRead(d,0x180)==BigInt("474e5a01",16))
        assert((axiRead(d,0x184)&31)==9 && axiRead(d,0x188)==base)
        axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1,expectedResponse=2)
        axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
      }
    }
  }
}
