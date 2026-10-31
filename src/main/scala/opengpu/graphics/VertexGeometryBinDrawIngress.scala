package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system.{GeometryWindowBinDrawResult,GeometryWindowBinTask}

class VertexGeometryBinDraw(config:GpuConfig,p:VertexShaderStageConfig,taskIdWidth:Int) extends Bundle {
  val vertex=new VertexGeometryDraw(p);val geometry=new GeometryWindowBinTask(config,p,taskIdWidth)
}

/** Admit a vertex draw and geometry template as one owned transaction. The
  * geometry side is admitted first; source primitives and source completion
  * then drain into it, including during cancellation.
  */
class VertexGeometryBinDrawIngress(config:GpuConfig,p:VertexShaderStageConfig,taskIdWidth:Int) extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new VertexGeometryBinDraw(config,p,taskIdWidth)))
    val vertexDraw=Decoupled(new VertexGeometryDraw(p));val geometryDraw=Decoupled(new GeometryWindowBinTask(config,p,taskIdWidth))
    val vertexOut=Flipped(Decoupled(new AssembledPrimitive(p)));val primitive=Decoupled(new AssembledPrimitive(p))
    val vertexCompletion=Flipped(Decoupled(new PrimitiveAssemblyCompletion));val inputEnd=Decoupled(new PrimitiveAssemblyCompletion)
    val geometryCompletion=Flipped(Decoupled(new GeometryWindowBinDrawResult))
    val vertexCancel=Output(Bool());val geometryCancel=Output(Bool());val cancel=Input(Bool())
    val completion=Decoupled(new GeometryWindowBinDrawResult);val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,validate,geometryOffer,vertexOffer,run,complete=Value}
  private val phase=RegInit(State.idle);private val draw=Reg(new VertexGeometryBinDraw(config,p,taskIdWidth))
  private val result=Reg(new GeometryWindowBinDrawResult);private val cancelled=RegInit(false.B)
  private val vertexStarted=RegInit(false.B);private val geometryStarted=RegInit(false.B);private val sourceEnded=RegInit(false.B)
  private val stop=cancelled || io.cancel
  io.begin.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.geometryDraw.valid:=phase===State.geometryOffer;io.geometryDraw.bits:=draw.geometry
  io.vertexDraw.valid:=phase===State.vertexOffer;io.vertexDraw.bits:=draw.vertex
  io.primitive.valid:=phase===State.run && !sourceEnded && io.vertexOut.valid;io.primitive.bits:=io.vertexOut.bits
  io.vertexOut.ready:=phase===State.run && !sourceEnded && io.primitive.ready
  io.inputEnd.valid:=phase===State.run && !sourceEnded && io.vertexCompletion.valid;io.inputEnd.bits:=io.vertexCompletion.bits
  io.vertexCompletion.ready:=phase===State.run && !sourceEnded && io.inputEnd.ready
  io.vertexCancel:=vertexStarted && stop;io.geometryCancel:=geometryStarted && stop
  io.geometryCompletion.ready:=phase===State.run && sourceEnded
  when(io.inputEnd.fire){sourceEnded:=true.B}
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  when(phase=/=State.idle && phase=/=State.complete && io.cancel){cancelled:=true.B}
  switch(phase){
    is(State.idle){when(io.begin.fire){draw:=io.begin.bits;result:=0.U.asTypeOf(result)
      result.identity:=io.begin.bits.vertex.vertex.input.identity;cancelled:=false.B
      vertexStarted:=false.B;geometryStarted:=false.B;sourceEnded:=false.B;phase:=State.validate}}
    is(State.validate){
      val legal=draw.vertex.vertex.input.identity.asUInt===draw.geometry.geometry.geometry.primitive.input.primitive.identity.asUInt
      when(stop || !legal){result.status:=Mux(stop,4.U,1.U);phase:=State.complete}.otherwise{phase:=State.geometryOffer}}
    is(State.geometryOffer){when(io.geometryDraw.fire){geometryStarted:=true.B;phase:=State.vertexOffer}}
    is(State.vertexOffer){when(io.vertexDraw.fire){vertexStarted:=true.B;phase:=State.run}}
    is(State.run){when(io.geometryCompletion.fire){result:=io.geometryCompletion.bits
      when(stop && io.geometryCompletion.bits.status===0.U){result.status:=4.U};phase:=State.complete}}
    is(State.complete){when(io.completion.fire){vertexStarted:=false.B;geometryStarted:=false.B;phase:=State.idle}}
  }
}
