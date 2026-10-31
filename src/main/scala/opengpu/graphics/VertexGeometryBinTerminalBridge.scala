package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system.{GeometryWindowBinDrawResult,GeometryWindowBinTask}

/** A draw offered as the geometry pass terminates still owns its vertex source.
  * Drain that source locally and return a held cancelled draw report.
  */
class VertexGeometryBinTerminalBridge(config:GpuConfig,p:VertexShaderStageConfig,taskIdWidth:Int) extends Module {
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new GeometryWindowBinTask(config,p,taskIdWidth)))
    val targetDraw=Decoupled(new GeometryWindowBinTask(config,p,taskIdWidth))
    val primitive=Flipped(Decoupled(new AssembledPrimitive(p)));val targetPrimitive=Decoupled(new AssembledPrimitive(p))
    val inputEnd=Flipped(Decoupled(new PrimitiveAssemblyCompletion));val targetEnd=Decoupled(new PrimitiveAssemblyCompletion)
    val targetCompletion=Flipped(Decoupled(new GeometryWindowBinDrawResult));val completion=Decoupled(new GeometryWindowBinDrawResult)
    val terminal=Input(Bool());val busy=Output(Bool())
  })
  private val draining=RegInit(false.B);private val reporting=RegInit(false.B)
  private val identity=Reg(new GraphicsWorkIdentity);private val count=RegInit(0.U(64.W));private val result=Reg(new GeometryWindowBinDrawResult)
  io.busy:=draining || reporting
  io.targetDraw.valid:=io.draw.valid && !io.terminal && !io.busy;io.targetDraw.bits:=io.draw.bits
  io.draw.ready:= !io.busy && Mux(io.terminal,true.B,io.targetDraw.ready)
  when(io.terminal && io.draw.fire){draining:=true.B;count:=0.U;identity:=io.draw.bits.geometry.geometry.primitive.input.primitive.identity}
  io.targetPrimitive.valid:=io.primitive.valid && !draining && !reporting;io.targetPrimitive.bits:=io.primitive.bits
  io.primitive.ready:=Mux(draining,true.B,!reporting && io.targetPrimitive.ready)
  when(draining && io.primitive.fire && !count.andR){count:=count+1.U}
  io.targetEnd.valid:=io.inputEnd.valid && !draining && !reporting;io.targetEnd.bits:=io.inputEnd.bits
  io.inputEnd.ready:=Mux(draining,!io.primitive.valid,!reporting && io.targetEnd.ready)
  when(draining && io.inputEnd.fire){result:=0.U.asTypeOf(result);result.identity:=identity;result.source:=io.inputEnd.bits
    result.inputPrimitives:=count;result.status:=4.U;draining:=false.B;reporting:=true.B}
  io.completion.valid:=reporting || (!draining && io.targetCompletion.valid)
  io.completion.bits:=Mux(reporting,result,io.targetCompletion.bits)
  io.targetCompletion.ready:=io.completion.ready && !reporting && !draining
  when(reporting && io.completion.fire){reporting:=false.B}
}
