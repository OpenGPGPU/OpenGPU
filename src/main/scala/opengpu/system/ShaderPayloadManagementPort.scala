package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.{ShaderPayloadConfig,ShaderPayloadControlRequest,ShaderPayloadControlResponse,
  ShaderPayloadRequest,ShaderPayloadResponse}

/** Producer-side reservation and staging channels of the shared payload pool.
  * Each fixed owner has independent response capacity. The system supplies
  * that port's owner identity rather than trusting a request's owner field.
  */
class ShaderPayloadManagementPort(p: ShaderPayloadConfig) extends Bundle {
  val control=Vec(p.owners,Flipped(Decoupled(new ShaderPayloadControlRequest(p))))
  val controlResponse=Vec(p.owners,Decoupled(new ShaderPayloadControlResponse(p)))
  val request=Vec(p.owners,Flipped(Decoupled(new ShaderPayloadRequest(p))))
  val response=Vec(p.owners,Decoupled(new ShaderPayloadResponse(p)))
  val reservedBytes=Output(UInt(32.W))
  val liveBytes=Output(UInt(32.W))
  val drained=Output(Bool())
}
