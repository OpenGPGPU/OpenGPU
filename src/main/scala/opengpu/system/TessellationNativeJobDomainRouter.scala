package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics.TileJobDescriptorRequest

/** Select one native job owner after probing its translated descriptor.
  * The selected owner retains all mappings until its completion retires. An
  * offered child request remains valid across cancellation; cancellation is
  * delivered after the child accepts it and stays asserted until retirement.
  */
class TessellationNativeJobDomainRouter(config:GpuConfig,idWidth:Int) extends Module {
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(idWidth)))
    val isoline=Decoupled(new TileJobDescriptorRequest(idWidth))
    val triangle=Decoupled(new TileJobDescriptorRequest(idWidth))
    val failed=Decoupled(new Bundle {
      val commandId=UInt(idWidth.W);val status=UInt(3.W);val faultAddress=UInt(32.W)
    })
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val cancelIsoline=Output(Bool());val cancelTriangle=Output(Bool())
    /** Assert only when the selected child's completion has been consumed. */
    val retired=Input(Bool());val busy=Output(Bool())
  })
  private object Phase extends ChiselEnum {val idle,probe,offer,running,failed=Value}
  private val phase=RegInit(Phase.idle)
  private val selectTriangle=RegInit(false.B);private val pendingCancel=RegInit(false.B)
  private val job=Reg(new TileJobDescriptorRequest(idWidth))
  private val failure=Reg(chiselTypeOf(io.failed.bits))
  private val probe=Module(new TessellationNativeJobDomainProbe(config,idWidth))
  probe.io.submit.valid:=io.submit.valid && phase===Phase.idle
  probe.io.submit.bits:=io.submit.bits
  io.submit.ready:=phase===Phase.idle && probe.io.submit.ready
  probe.io.cancel:=io.cancel && phase===Phase.probe
  io.memoryRequest<>probe.io.memoryRequest;probe.io.memoryResponse<>io.memoryResponse
  probe.io.result.ready:=phase===Phase.probe
  io.isoline.valid:=phase===Phase.offer && !selectTriangle;io.isoline.bits:=job
  io.triangle.valid:=phase===Phase.offer && selectTriangle;io.triangle.bits:=job
  io.failed.valid:=phase===Phase.failed;io.failed.bits:=failure
  io.cancelIsoline:=phase===Phase.running && !selectTriangle && (pendingCancel || io.cancel)
  io.cancelTriangle:=phase===Phase.running && selectTriangle && (pendingCancel || io.cancel)
  io.busy:=phase=/=Phase.idle || probe.io.busy
  when(io.submit.fire){phase:=Phase.probe;pendingCancel:=false.B}
  when(phase===Phase.offer || phase===Phase.running){when(io.cancel){pendingCancel:=true.B}}
  when(probe.io.result.fire){
    val r=probe.io.result.bits
    when(r.status===0.U && !io.cancel){job:=r.job;selectTriangle:=r.triangle;phase:=Phase.offer}
      .otherwise{failure.commandId:=r.job.commandId;failure.status:=Mux(io.cancel,4.U,r.status)
        failure.faultAddress:=Mux(io.cancel,0.U,r.faultAddress);phase:=Phase.failed}
  }
  when(io.isoline.fire || io.triangle.fire){phase:=Phase.running}
  when(io.retired){assert(phase===Phase.running,"native tessellation retirement lacks an owner");phase:=Phase.idle}
  when(io.failed.fire){phase:=Phase.idle}
}
