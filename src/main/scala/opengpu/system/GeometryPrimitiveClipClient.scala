package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.graphics._

class GeometryClippedPrimitive(p:VertexShaderStageConfig) extends Bundle {
  val clipped = new ClippedPrimitive(p)
  val inputPrimitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val layer = UInt(32.W)
}
class GeometryPrimitiveClipResult extends Bundle {
  val geometry = new GeometryPrimitivePipelineResult
  /** 0=success, 1=invalid geometry task, 4=cancelled, 5=shader fault, 7=clip fault. */
  val status = UInt(3.W)
  val clipInputs = UInt(64.W)
  val clipOutputs = UInt(64.W)
  val culled = UInt(64.W)
}

/** One geometry input primitive owns its CU task and clipper until both drain.
  * Layer and original primitive identity remain sidebands for each fan part.
  */
class GeometryPrimitiveClipClient(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int,
    useSramBlackBoxes:Boolean=false) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new GeometrySharedShaderTask(config,p,commandIdWidth)))
    val out=Decoupled(new GeometryClippedPrimitive(p))
    val completion=Decoupled(new GeometryPrimitiveClipResult)
    val cancel=Input(Bool())
    val shared=Flipped(new SharedCuGraphicsPort(config,commandIdWidth,numComputeUnits,geometryAvailable=true))
    val texture=Vec(numComputeUnits,new SharedCuTexturePort(config))
    val trap=Decoupled(new TaggedGraphicsTrap(config,commandIdWidth,numComputeUnits))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone=Decoupled(new CacheLineInvalidate(config))
  })
  private val client=Module(new GeometrySharedShaderClient(config,p,commandIdWidth,numComputeUnits))
  private val clipper=Module(new PrimitiveClipStage(p,useSramBlackBoxes))
  private val active=RegInit(false.B);private val sourceDone=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val source=Reg(new GeometryPrimitivePipelineResult)
  private val result=Reg(new GeometryPrimitiveClipResult)
  private val inputs=RegInit(0.U(64.W));private val outputs=RegInit(0.U(64.W));private val culled=RegInit(0.U(64.W))
  private val inputId=Reg(UInt(32.W));private val invocation=Reg(UInt(6.W));private val layer=Reg(UInt(32.W))
  private val clipFault=clipper.io.completion.valid && clipper.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || io.cancel || clipFault
  client.io.task.valid:=io.task.valid && !active && !io.cancel;client.io.task.bits:=io.task.bits
  io.task.ready:= !active && !io.cancel && client.io.task.ready
  when(io.task.fire){active:=true.B;sourceDone:=false.B;done:=false.B;status:=0.U
    inputs:=0.U;outputs:=0.U;culled:=0.U}
  client.io.cancel:=active && stop;clipper.io.cancel:=active && stop
  clipper.io.in.valid:=active && !stop && client.io.primitive.valid && clipper.io.in.ready
  clipper.io.in.bits:=client.io.primitive.bits.primitive
  client.io.primitive.ready:=active && (stop || clipper.io.in.ready)
  when(clipper.io.in.fire){inputs:=inputs+1.U;inputId:=client.io.primitive.bits.inputPrimitiveId
    invocation:=client.io.primitive.bits.invocationId;layer:=client.io.primitive.bits.layer}
  io.out.valid:=clipper.io.out.valid;io.out.bits.clipped:=clipper.io.out.bits
  io.out.bits.inputPrimitiveId:=inputId;io.out.bits.invocationId:=invocation;io.out.bits.layer:=layer
  clipper.io.out.ready:=io.out.ready
  when(io.out.fire){outputs:=outputs+1.U}
  clipper.io.completion.ready:=active
  when(clipper.io.completion.fire){
    when(clipper.io.completion.bits.culled){culled:=culled+1.U}
    when(clipper.io.completion.bits.status=/=0.U && status===0.U && !io.cancel){status:=7.U}
  }
  client.io.completion.ready:=active && !sourceDone
  when(client.io.completion.fire){source:=client.io.completion.bits;sourceDone:=true.B
    when(client.io.completion.bits.status=/=GeometryInvocationStatus.success && status===0.U && !io.cancel && !clipFault){
      status:=MuxLookup(client.io.completion.bits.status,5.U)(Seq(
        GeometryInvocationStatus.invalid -> 1.U,GeometryInvocationStatus.cancelled -> 4.U))
    }}
  when(active && io.cancel && status===0.U && !done){status:=4.U}
  when(active && sourceDone && !clipper.io.busy && !done){
    result.geometry:=source;result.status:=Mux(io.cancel && status===0.U,4.U,status)
    result.clipInputs:=inputs;result.clipOutputs:=outputs;result.culled:=culled;done:=true.B
  }
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.shared<>client.io.shared;io.texture<>client.io.texture;io.trap<>client.io.trap
  client.io.invalidate<>io.invalidate;io.invalidateDone<>client.io.invalidateDone
}
