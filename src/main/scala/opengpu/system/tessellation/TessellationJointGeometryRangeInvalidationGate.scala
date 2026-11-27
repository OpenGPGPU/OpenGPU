package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate, ComputeMemoryRequest, ComputeMemoryResponse}

class TessellationJointGeometryRangeInvalidationGate[D <: Data](config: GpuConfig, requestType: D) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(requestType))
    val begin = Decoupled(requestType)
    val externalInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val externalInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val peerInvalidate = Decoupled(new CacheLineInvalidate(config))
    val peerInvalidateDone = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val cancel = Input(Bool())
    val cancelled = Output(Bool())
    val cancelChild = Output(Bool())
    val busy = Output(Bool())
    val drained = Output(Bool())
  })
  private object RangeState extends ChiselEnum { val idle, offer, pending, begin = Value }
  private object ExternalState extends ChiselEnum { val idle, pending, done = Value }
  private val rangeState = RegInit(RangeState.idle)
  private val externalState = RegInit(ExternalState.idle)
  private val saved = Reg(requestType)
  private val external = Reg(new CacheLineInvalidate(config))
  private val cancelled = RegInit(false.B)
  private val childCancelled = RegInit(false.B)
  private val childActive = RegInit(false.B)
  private val peerPending = RegInit(false.B)
  private val peerRange = RegInit(false.B)
  private val peerAddress = Reg(UInt(config.xLen.W))
  private val allLines = CacheLineInvalidate.allLinesAddress(config)

  io.request.ready := rangeState === RangeState.idle && externalState === ExternalState.idle && !io.cancel
  io.externalInvalidate.ready := externalState === ExternalState.idle &&
    rangeState === RangeState.idle && !io.peerInvalidateDone.valid
  io.peerInvalidate.valid := !peerPending &&
    ((rangeState === RangeState.offer && !cancelled) || externalState === ExternalState.pending)
  io.peerInvalidate.bits.lineAddress := Mux(rangeState === RangeState.offer, allLines, external.lineAddress)
  io.peerInvalidateDone.ready := peerPending
  io.externalInvalidateDone.valid := externalState === ExternalState.done
  io.externalInvalidateDone.bits.lineAddress := peerAddress
  io.begin.valid := rangeState === RangeState.begin
  io.begin.bits := saved
  io.cancelled := cancelled
  io.cancelChild := childCancelled || (childActive && io.cancel)
  io.busy := rangeState =/= RangeState.idle || externalState =/= ExternalState.idle ||
    peerPending || io.peerInvalidateDone.valid
  io.drained := !io.busy

  when(io.request.fire) {
    saved := io.request.bits
    cancelled := false.B
    childCancelled := false.B
    childActive := false.B
    rangeState := RangeState.offer
  }
  when(rangeState =/= RangeState.idle && io.cancel) { cancelled := true.B }
  when(rangeState === RangeState.offer && (cancelled || io.cancel)) { rangeState := RangeState.begin }
  when(io.externalInvalidate.fire) {
    external := io.externalInvalidate.bits
    externalState := ExternalState.pending
  }
  when(io.peerInvalidate.fire) {
    assert(!peerPending, "joint geometry invalidate peer accepted overlapping owners")
    peerPending := true.B
    peerRange := rangeState === RangeState.offer
    peerAddress := io.peerInvalidate.bits.lineAddress
    when(rangeState === RangeState.offer) { rangeState := RangeState.pending }
  }
  when(io.peerInvalidateDone.fire) {
    assert(peerPending, "joint geometry invalidate acknowledgement has no accepted owner")
    assert(io.peerInvalidateDone.bits.lineAddress === peerAddress)
    peerPending := false.B
    when(peerRange) { rangeState := RangeState.begin }
      .otherwise { externalState := ExternalState.done }
  }
  when(io.externalInvalidateDone.fire) { externalState := ExternalState.idle }
  when(io.begin.fire) {
    rangeState := RangeState.idle
    childActive := true.B
    childCancelled := cancelled
  }
}
