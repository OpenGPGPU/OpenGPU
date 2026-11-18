package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class TransformFeedbackScope extends Bundle {
  val context=UInt(32.W);val root=UInt(32.W);val program=UInt(32.W);val rasterDiscard=Bool()
  /** 0 internal automatic,1 require this cookie,2 require inactive/paused. */
  val mode=UInt(2.W);val cookie=UInt(32.W)
}
/** Separate data ownership preserves every pre-existing renderer/physical-image
  * transaction namespace. The enclosing native host translates this client.
  * Control cancellation is independent of a geometry draw's active flag.
  */
class TransformFeedbackPipelineIO(config: GpuConfig) extends Bundle {
  val command=Flipped(Decoupled(new TransformFeedbackCommand))
  val commandReply=Decoupled(new TransformFeedbackCommandReply)
  val scope=Input(new TransformFeedbackScope);val cancel=Input(Bool())
  val recorded=Valid(new TransformFeedbackPrimitiveResult);val busy=Output(Bool())
  val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
  val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
  val memorySatp=Output(UInt(32.W))
}
object TransformFeedbackPipelineIO {
  def forward(parent: TransformFeedbackPipelineIO,child: TransformFeedbackPipelineIO,
      scope: TransformFeedbackScope,admitCommands: Bool): Unit={
    child.command.valid:=parent.command.valid && admitCommands;child.command.bits:=parent.command.bits
    parent.command.ready:=child.command.ready && admitCommands
    parent.commandReply.valid:=child.commandReply.valid;parent.commandReply.bits:=child.commandReply.bits
    child.commandReply.ready:=parent.commandReply.ready
    child.scope:=scope;child.cancel:=parent.cancel
    parent.recorded:=child.recorded;parent.busy:=child.busy;parent.memorySatp:=child.memorySatp
    parent.memoryRequest.valid:=child.memoryRequest.valid;parent.memoryRequest.bits:=child.memoryRequest.bits
    child.memoryRequest.ready:=parent.memoryRequest.ready
    child.memoryResponse.valid:=parent.memoryResponse.valid;child.memoryResponse.bits:=parent.memoryResponse.bits
    parent.memoryResponse.ready:=child.memoryResponse.ready
  }
}
