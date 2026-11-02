package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics.{NativeBinnedGraphicsLayout,TileJobDescriptorRequest}

/** Read the job magic before choosing a native tessellation owner. The caller must
  * retain the original descriptor contents and address translation until the
  * selected job completes; the selected reader validates the complete record.
  * Quad recognition is opt-in until the caller can route its quad result.
  * Accepted or offered reads drain before a cancellation is reported.
  */
class TessellationNativeJobDomainProbe(config:GpuConfig,idWidth:Int,supportQuad:Boolean=false) extends Module {
  require(config.xLen==32 && idWidth>0 && idWidth<=12)
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(idWidth)))
    val result=Decoupled(new Bundle {
      val job=new TileJobDescriptorRequest(idWidth)
      val triangle=Bool()
      val quad=Bool()
      /** 0 recognized, 1 invalid envelope or magic, 3 memory fault, 4 canceled. */
      val status=UInt(3.W)
      val faultAddress=UInt(32.W);val readBytes=UInt(7.W)
    })
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object Phase extends ChiselEnum { val idle,offer,waitReply,done=Value }
  private val phase=RegInit(Phase.idle)
  private val result=Reg(chiselTypeOf(io.result.bits))
  private val canceled=RegInit(false.B)
  io.submit.ready:=phase===Phase.idle && !io.cancel
  io.result.valid:=phase===Phase.done;io.result.bits:=result
  io.busy:=phase=/=Phase.idle
  io.memoryRequest.valid:=phase===Phase.offer
  io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=result.job.base
  io.memoryRequest.bits.sizeLog2:=6.U
  io.memoryRequest.bits.byteMask:=Fill(64,1.U(1.W))
  io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  io.memoryRequest.bits.transactionId:=0.U
  io.memoryResponse.ready:=phase===Phase.waitReply || (phase===Phase.offer && io.memoryRequest.ready)
  when(io.memoryResponse.valid && io.memoryResponse.ready){assert(io.memoryResponse.bits.transactionId===0.U)}
  when((phase===Phase.offer || phase===Phase.waitReply) && io.cancel){canceled:=true.B}
  when(io.submit.fire){
    result:=0.U.asTypeOf(result);result.job:=io.submit.bits;canceled:=false.B
    val end=io.submit.bits.base+&NativeBinnedGraphicsLayout.jobBytes.U
    when(!io.submit.bits.envelopeValid || io.submit.bits.base(5,0)=/=0.U ||
      io.submit.bits.limit>(BigInt(1)<<32).U || end>io.submit.bits.limit){
      result.status:=1.U;result.faultAddress:=io.submit.bits.base;phase:=Phase.done
    }.otherwise{phase:=Phase.offer}
  }
  when(io.memoryRequest.fire){result.readBytes:=64.U;phase:=Phase.waitReply}
  when(io.memoryResponse.fire){
    val magic=io.memoryResponse.bits.readData(31,0)
    when(canceled || io.cancel){result.status:=4.U;result.faultAddress:=0.U}
      .elsewhen(io.memoryResponse.bits.fault){result.status:=3.U;result.faultAddress:=result.job.base}
      .elsewhen(magic===TessellationNativeJobLayout.magic.U){result.status:=0.U;result.triangle:=false.B}
      .elsewhen(magic===TessellationNativeTriangleJobLayout.magic.U){result.status:=0.U;result.triangle:=true.B}
      .elsewhen(supportQuad.B && magic===TessellationNativeQuadJobLayout.magic.U){result.status:=0.U;result.quad:=true.B}
      .otherwise{result.status:=1.U;result.faultAddress:=result.job.base}
    phase:=Phase.done
  }
  when(io.result.fire){phase:=Phase.idle}
}
