package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, ShaderPayloadBinding, VectorTlbFlush}
import opengpu.graphics.{FragmentPayloadPort, FragmentPerformanceEvents, ShaderPayloadPin,
  TransformFeedbackPipelineIO, TypedTilePixelQuadCompletion}

/** Common physical connections of the two tessellation command formats. */
trait TessellationHostPhysicalPorts {
  val out: Vec[DecoupledIO[TypedTilePixelQuadCompletion]]
  val shared: SharedCuGraphicsPort
  val payload: Option[Vec[FragmentPayloadPort]]
  val poolPayload: Option[Valid[ShaderPayloadBinding]]
  val payloadPins: Option[Vec[Valid[ShaderPayloadPin]]]
  val memoryRequest: DecoupledIO[ComputeMemoryRequest]
  val memoryResponse: DecoupledIO[ComputeMemoryResponse]
  val feedback: Option[TransformFeedbackPipelineIO]
  val satp: UInt
  val flush: Valid[VectorTlbFlush]
  val fragmentEvents: FragmentPerformanceEvents
  val cancel: Bool
  val busy: Bool
}
