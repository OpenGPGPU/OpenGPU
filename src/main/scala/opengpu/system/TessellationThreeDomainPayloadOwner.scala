package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.{ShaderPayloadConfig, ShaderPayloadProducerPort}

/** Exclusive payload ownership for isoline, triangle and quad jobs.
  * Both stages acquire and retire together, so a domain change cannot bypass
  * a live reply or allocation retained by either stage.
  */
class TessellationThreeDomainPayloadOwner(p: ShaderPayloadConfig) extends Module {
  val io = IO(new Bundle {
    /** 0: isoline, 1: triangle, 2: quad. */
    val acquire = Flipped(Decoupled(UInt(2.W)))
    val end = Flipped(Decoupled(Bool()))
    val clients = Vec(3, Vec(p.owners, Flipped(new ShaderPayloadProducerPort(p))))
    val pool = Vec(p.owners, new ShaderPayloadProducerPort(p))
    val active = Output(Bool())
    val drained = Output(Bool())
  })
  private val pair = Module(new TessellationHostPayloadOwner(p))
  private val outer = Module(new TessellationHostPayloadOwner(p))
  private val validOwner = io.acquire.bits < 3.U
  io.acquire.ready := validOwner && pair.io.acquire.ready && outer.io.acquire.ready
  pair.io.acquire.valid := io.acquire.valid && validOwner && outer.io.acquire.ready
  pair.io.acquire.bits := io.acquire.bits === 1.U
  outer.io.acquire.valid := io.acquire.valid && validOwner && pair.io.acquire.ready
  outer.io.acquire.bits := io.acquire.bits === 2.U
  io.end.ready := pair.io.end.ready && outer.io.end.ready
  pair.io.end.valid := io.end.valid && outer.io.end.ready
  pair.io.end.bits := io.end.bits
  outer.io.end.valid := io.end.valid && pair.io.end.ready
  outer.io.end.bits := io.end.bits
  pair.io.legacy <> io.clients(0)
  pair.io.tessellation <> io.clients(1)
  outer.io.legacy <> pair.io.pool
  outer.io.tessellation <> io.clients(2)
  io.pool <> outer.io.pool
  io.active := pair.io.active || outer.io.active
  io.drained := pair.io.drained && outer.io.drained
  assert(pair.io.active === outer.io.active, "payload owner stages lost synchronization")
}
