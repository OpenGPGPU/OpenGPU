package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.command.{InvalidateCompletion, InvalidateDescriptor, InvalidateStatus}
import opengpu.config.GpuConfig

/** Walks a byte range and invalidates one 64-byte L2 line per step.
  *
  * Used by the driver-visible invalidate command and by the resolve adapter's
  * implicit source invalidate.  Addresses are physical: the L2 host-invalidate
  * port does not consult VECTOR_SATP.  It issues exactly one host invalidate at
  * a time and waits for the L2 acknowledgement before advancing, so it needs no
  * tagging and never blocks an unrelated L2 client.
  */
class LineInvalidateEngine(
  config: GpuConfig = GpuConfig(),
  commandIdWidth: Int = 8
) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(
      new InvalidateDescriptor(config, commandIdWidth)))
    val completion = Decoupled(new InvalidateCompletion(commandIdWidth))
    val hostInvalidate = Decoupled(new CacheLineInvalidate(config))
    val hostInvalidateDone = Flipped(Decoupled(
      new CacheLineInvalidate(config)))
  })

  private object State extends ChiselEnum {
    val idle, decode, issue, waiting, respond = Value
  }
  require(commandIdWidth > 0)
  private val offsetWidth = 6
  private val state = RegInit(State.idle)
  private val address = Reg(UInt(config.xLen.W))
  private val bytes = Reg(UInt(32.W))
  private val descriptorId = Reg(UInt(commandIdWidth.W))
  private val lines = Reg(UInt(32.W))
  private val index = Reg(UInt(32.W))
  private val status = Reg(UInt(InvalidateStatus.width.W))

  // Validate the original exclusive end before rounding. Exactly 2^32 is
  // legal; an overflowing byte range must never wrap into another allocation.
  // Include the first line's byte offset and retain the carry when rounding,
  // including the nearly-four-GiB case whose aligned extent is four GiB.
  private val end = address +& bytes
  private val overflow = end > (BigInt(1) << config.xLen).U
  private val rounding = address(offsetWidth-1,0) +& (63.U(offsetWidth.W))
  private val lineCount = (bytes +& rounding) >> offsetWidth

  io.request.ready := state === State.idle
  io.hostInvalidate.valid := state === State.issue
  io.hostInvalidate.bits.lineAddress := address + (index << offsetWidth)
  io.hostInvalidateDone.ready := state === State.waiting
  io.completion.valid := state === State.respond
  io.completion.bits.descriptorId := descriptorId
  io.completion.bits.status := status
  io.completion.bits.success := status === InvalidateStatus.success
  io.completion.bits.bytesInvalidated := (lines << offsetWidth).pad(64)

  switch(state) {
    is(State.idle) {
      when(io.request.fire) {
        address := io.request.bits.address
        bytes := io.request.bits.bytes
        descriptorId := io.request.bits.descriptorId
        index := 0.U
        state := State.decode
      }
    }
    is(State.decode) {
      address := Cat(address(config.xLen-1,offsetWidth),0.U(offsetWidth.W))
      lines := Mux(overflow || bytes === 0.U,0.U,lineCount)
      status := Mux(overflow,InvalidateStatus.addressOverflow,InvalidateStatus.success)
      state := Mux(overflow || bytes === 0.U,State.respond,State.issue)
    }
    is(State.issue) {
      when(io.hostInvalidate.fire) { state := State.waiting }
    }
    is(State.waiting) {
      when(io.hostInvalidateDone.fire) {
        assert(io.hostInvalidateDone.bits.lineAddress === io.hostInvalidate.bits.lineAddress,
          "publication acknowledgement must match the owned physical cache line")
        when(index === lines - 1.U) {
          state := State.respond
        }.otherwise {
          index := index + 1.U
          state := State.issue
        }
      }
    }
    is(State.respond) {
      when(io.completion.fire) { state := State.idle }
    }
  }
}
