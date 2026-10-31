package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.VertexShaderStageConfig

/** Drain a held task locally after its enclosing pass has terminated. */
class GeometryBinTaskTerminalBridge(config:GpuConfig,p:VertexShaderStageConfig,taskIdWidth:Int) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new GeometryWindowBinTask(config,p,taskIdWidth)))
    val targetTask=Decoupled(new GeometryWindowBinTask(config,p,taskIdWidth))
    val targetCompletion=Flipped(Decoupled(new GeometryWindowBinTaskCompletion))
    val completion=Decoupled(new GeometryWindowBinTaskCompletion)
    val terminal=Input(Bool());val busy=Output(Bool())
  })
  private val reply=RegInit(false.B)
  io.targetTask.valid:=io.task.valid && !io.terminal && !reply;io.targetTask.bits:=io.task.bits
  io.task.ready:= !reply && Mux(io.terminal,true.B,io.targetTask.ready)
  when(io.terminal && io.task.fire){reply:=true.B}
  io.completion.valid:=reply || io.targetCompletion.valid
  io.completion.bits:=Mux(reply,0.U.asTypeOf(io.completion.bits),io.targetCompletion.bits)
  when(reply){io.completion.bits.status:=4.U}
  io.targetCompletion.ready:=io.completion.ready && !reply
  when(reply && io.completion.fire){reply:=false.B}
  io.busy:=reply
}
