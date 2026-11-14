package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Retain the complete GS/ordinary source tuple through real feedback ACKs.
  * Export receives raw linked words; its original flat rule runs only after
  * capture. Discard/fault primitives retire metadata through the real result,
  * including when there is deliberately no downstream raster output.
  */
class GeometryTransformFeedbackStage(config:GpuConfig,p:VertexShaderStageConfig,contexts:Int=8) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new GeometryMappedPrimitive(p)))
    val out=Decoupled(new GeometryMappedPrimitive(p))
    val feedback=new TransformFeedbackPipelineIO(config)
    val result=Valid(new TransformFeedbackPrimitiveResult)
    val dropped=Output(Bool())
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val exporter=Module(new TransformFeedbackExport(config,p,contexts))
  private val metadata=Reg(new GeometryMappedPrimitive(p))
  private val scope=Reg(new TransformFeedbackScope)
  private val owned=RegInit(false.B)
  // A bubble after the exporter's real drain keeps an old tuple from being
  // replaced on the same edge that its final output/result retires.
  when(owned && !exporter.io.busy){owned:=false.B}
  exporter.io.in.valid:=io.in.valid && !owned
  io.in.ready:=exporter.io.in.ready && !owned
  exporter.io.in.bits.primitive:=io.in.bits.primitive
  exporter.io.in.bits.context:=io.feedback.scope.context;exporter.io.in.bits.root:=io.feedback.scope.root
  exporter.io.in.bits.program:=io.feedback.scope.program;exporter.io.in.bits.cookie:=io.feedback.scope.cookie
  exporter.io.in.bits.mode:=io.feedback.scope.mode;exporter.io.in.bits.rasterDiscard:=io.feedback.scope.rasterDiscard
  when(io.in.fire){metadata:=io.in.bits;scope:=io.feedback.scope;owned:=true.B}
  io.out.valid:=exporter.io.out.valid;io.out.bits:=metadata
  io.out.bits.primitive:=exporter.io.out.bits;exporter.io.out.ready:=io.out.ready
  when(io.out.valid){assert(owned,"feedback output lost its original geometry tuple")}
  exporter.io.result.ready:=true.B
  io.result.valid:=exporter.io.result.fire;io.result.bits:=exporter.io.result.bits
  io.dropped:=io.result.valid && (io.result.bits.status=/=0.U || scope.rasterDiscard)
  when(io.result.valid){assert(owned,"feedback result lost its source metadata")}
  io.feedback.recorded.valid:=io.result.valid && io.result.bits.captured
  io.feedback.recorded.bits:=io.result.bits
  exporter.io.command<>io.feedback.command;io.feedback.commandReply<>exporter.io.commandReply
  io.feedback.memoryRequest<>exporter.io.memoryRequest;exporter.io.memoryResponse<>io.feedback.memoryResponse
  io.feedback.memorySatp:=exporter.io.memorySatp
  exporter.io.cancel:=io.feedback.cancel;exporter.io.abort:=io.cancel
  io.busy:=exporter.io.busy || owned;io.feedback.busy:=io.busy
}
