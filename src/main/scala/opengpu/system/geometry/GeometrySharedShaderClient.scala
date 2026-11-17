package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.graphics._

class GeometrySharedShaderTask(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int) extends Bundle {
  val primitive = new GeometryPrimitivePipelineTask(config,p,commandIdWidth)
  val nativeResources = Bool()
  val uniformResources = Bool()
}

/** One geometry pipeline as a client of the existing shared shader router.
  * Texture and invalidation service remain owned by the enclosing renderer.
  */
class GeometrySharedShaderClient(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new GeometrySharedShaderTask(config,p,commandIdWidth)))
    val primitive=Decoupled(new GeometryMappedPrimitive(p))
    val completion=Decoupled(new GeometryPrimitivePipelineResult)
    val cancel=Input(Bool())
    val shared=Flipped(new SharedCuGraphicsPort(config,commandIdWidth,numComputeUnits,geometryAvailable=true))
    val texture=Vec(numComputeUnits,new SharedCuTexturePort(config))
    val trap=Decoupled(new TaggedGraphicsTrap(config,commandIdWidth,numComputeUnits))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone=Decoupled(new CacheLineInvalidate(config))
  })
  private val pipeline=Module(new GeometryPrimitivePipeline(config,p,commandIdWidth,numComputeUnits))
  private val native=RegInit(false.B);private val uniform=RegInit(false.B)
  pipeline.io.task.valid:=io.task.valid;pipeline.io.task.bits:=io.task.bits.primitive
  io.task.ready:=pipeline.io.task.ready
  when(io.task.fire){native:=io.task.bits.nativeResources;uniform:=io.task.bits.uniformResources}
  io.primitive<>pipeline.io.primitive;io.completion<>pipeline.io.completion;pipeline.io.cancel:=io.cancel
  io.shared.launch<>pipeline.io.launch;io.shared.geometryTask.get:=pipeline.io.geometryTask
  pipeline.io.kernelCompletion<>io.shared.completion;pipeline.io.event<>io.shared.geometryEvent.get
  pipeline.io.owner:=io.shared.owned;pipeline.io.taskIds:=io.shared.taskIds
  io.shared.geometryCancel.get:=pipeline.io.cancelCu
  io.shared.nativeResources:=native;io.shared.uniformResources:=uniform
  io.texture<>io.shared.texture;io.trap<>io.shared.trap
  io.shared.invalidate<>io.invalidate;io.invalidateDone<>io.shared.invalidateDone
}
