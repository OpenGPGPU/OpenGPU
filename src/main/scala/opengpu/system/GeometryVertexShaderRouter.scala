package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.{GeometryInvocationLaunch,SharedShaderTaskRouter}

/** Share a geometry-capable subtree and a vertex client without serializing
  * the subtree's distinct in-flight geometry and fragment task tags.
  */
class GeometryVertexShaderRouter(config:GpuConfig,numComputeUnits:Int,taskIdWidth:Int,tasksPerClient:Int) extends Module {
  require(tasksPerClient>=1)
  val io=IO(new Bundle {
    val geometry=new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true)
    val vertex=new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits)
    val pool=Flipped(new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true))
    val drained=Output(Bool())
  })
  private val router=Module(new SharedShaderTaskRouter(config,numComputeUnits,2,taskIdWidth,
    tasksPerClient=tasksPerClient,geometryAvailable=true))
  router.io.clients(0)<>io.geometry;io.pool<>router.io.pool
  private val client=router.io.clients(1);private val v=io.vertex
  client.nativeResources:=v.nativeResources;client.uniformResources:=v.uniformResources
  client.launch<>v.launch;v.completion<>client.completion;v.trap<>client.trap;v.texture<>client.texture
  v.owned:=client.owned;v.taskIds:=client.taskIds;client.invalidate<>v.invalidate;v.invalidateDone<>client.invalidateDone
  client.geometryTask.get:=0.U.asTypeOf(Valid(new GeometryInvocationLaunch))
  for(cu<-0 until numComputeUnits){client.geometryCancel.get(cu):=false.B;client.geometryEvent.get(cu).ready:=true.B
    assert(!client.geometryEvent.get(cu).valid,"vertex task cannot own geometry output")}
  io.drained:=router.io.drained
}
