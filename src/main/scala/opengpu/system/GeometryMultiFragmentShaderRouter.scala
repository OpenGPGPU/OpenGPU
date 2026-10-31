package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.ShaderPayloadBinding
import opengpu.graphics.{GeometryInvocationLaunch,ShaderPayloadConfig,ShaderPayloadPin,SharedShaderTaskRouter}

/** Share the geometry-capable CU pool with independent fragment engines. */
class GeometryMultiFragmentShaderRouter(config:GpuConfig,numComputeUnits:Int,taskIdWidth:Int,fragments:Int,
    shaderPayload:Option[ShaderPayloadConfig]=None) extends Module {
  require(fragments>=1 && fragments<=4)
  val io=IO(new Bundle {
    val geometry=new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true)
    val fragment=Vec(fragments,new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits))
    val pool=Flipped(new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true))
    val clientPayload=shaderPayload.map(p=>Input(Vec(fragments+1,Valid(new ShaderPayloadBinding(config,p)))))
    val poolPayload=shaderPayload.map(p=>Output(Valid(new ShaderPayloadBinding(config,p))))
    val payloadPins=shaderPayload.map(p=>Output(Vec(2*(fragments+1),Valid(new ShaderPayloadPin(p)))))
    val drained=Output(Bool())
  })
  private val router=Module(new SharedShaderTaskRouter(config,numComputeUnits,fragments+1,taskIdWidth,
    shaderPayload=shaderPayload,geometryAvailable=true))
  router.io.clients(0)<>io.geometry;io.pool<>router.io.pool
  for(e<-0 until fragments){
    val client=router.io.clients(e+1);val f=io.fragment(e)
    client.nativeResources:=f.nativeResources;client.uniformResources:=f.uniformResources
    client.launch<>f.launch;f.completion<>client.completion;f.trap<>client.trap;f.texture<>client.texture
    f.owned:=client.owned;f.taskIds:=client.taskIds;client.invalidate<>f.invalidate;f.invalidateDone<>client.invalidateDone
    client.geometryTask.get:=0.U.asTypeOf(Valid(new GeometryInvocationLaunch))
    for(cu<-0 until numComputeUnits){client.geometryCancel.get(cu):=false.B;client.geometryEvent.get(cu).ready:=true.B
      assert(!client.geometryEvent.get(cu).valid,"fragment task cannot own geometry output")}
  }
  shaderPayload.foreach{_=>router.io.clientPayload.get:=io.clientPayload.get
    io.poolPayload.get:=router.io.poolPayload.get;io.payloadPins.get:=router.io.payloadPins.get}
  io.drained:=router.io.drained
}
