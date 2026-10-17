package opengpu.graphics

import chisel3._
import chisel3.util._

/** Ordered rectangle clear through the resident attachment word service.
  * Admission waits for older draws to drain. Full-word clears allocate with
  * no old-data read; masked clears use the store's cache/clear tags first.
  */
class TileAttachmentClearEngine extends Module {
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new TileAttachmentClear))
    val done = Decoupled(Bool())
    val descriptor = Input(new TileRenderPassDescriptor)
    val colorTileBase = Input(UInt(32.W))
    val depthTileBase = Input(UInt(32.W))
    val cancel = Input(Bool())
    val busy = Output(Bool())
    val wordIndex = Output(UInt(10.W))
    val depthPlane = Output(Bool())
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })
  private val Seq(idle, prepareOffset, prepareRows, readRequest, readResponse,
    writeRequest, writeResponse, complete) = Enum(8)
  private val state = RegInit(idle)
  private val command = Reg(new TileAttachmentClear)
  private val x = Reg(UInt(4.W))
  private val y = Reg(UInt(4.W))
  private val sample = Reg(UInt(2.W))
  private val depth = Reg(Bool())
  private val merged = Reg(UInt(32.W))
  private val cancelled = RegInit(false.B)
  private val valid = RegInit(false.B)
  private val firstRowOffset = Reg(UInt(37.W))
  private val rowBase = Reg(UInt(32.W))
  private val depthFirstRow = Reg(UInt(32.W))
  private val view = Mux(depth, io.descriptor.depth, io.descriptor.color)
  private val mask = Mux(depth, command.depthMask, command.colorMask)
  private val value = Mux(depth, command.depthValue, command.colorValue)
  private val address = rowBase + (((x << io.descriptor.sampleMode) + sample) << 2)

  io.busy := state =/= idle
  io.command.ready := state === idle && !io.cancel
  io.done.valid := state === complete
  io.done.bits := valid && !cancelled && !io.cancel
  io.wordIndex := (Cat(y, x) << io.descriptor.sampleMode) + sample
  io.depthPlane := depth
  io.mem.req.valid := state === readRequest || state === writeRequest
  io.mem.req.bits.write := state === writeRequest
  io.mem.req.bits.addr := address(31, 0)
  io.mem.req.bits.data := Mux(mask.andR, value, merged)
  io.mem.resp.ready := state === readResponse || state === writeResponse

  when(io.command.fire) {
    command := io.command.bits
    x := io.command.bits.minX
    y := io.command.bits.minY
    sample := 0.U
    depth := !io.command.bits.colorMask.orR
    cancelled := false.B
    val validRectangle = io.command.bits.minX <= io.command.bits.maxX &&
      io.command.bits.minY <= io.command.bits.maxY &&
      io.command.bits.maxX <= io.descriptor.tileWidth && io.command.bits.maxY <= io.descriptor.tileHeight
    valid := validRectangle
    val empty = io.command.bits.minX === io.command.bits.maxX ||
      io.command.bits.minY === io.command.bits.maxY ||
      !(io.command.bits.colorMask | io.command.bits.depthMask).orR
    state := Mux(!validRectangle || empty, complete, prepareOffset)
  }
  when(state === prepareOffset) {
    firstRowOffset := command.minY * io.descriptor.color.rowPitch
    state := Mux(cancelled || io.cancel, complete, prepareRows)
  }
  when(state === prepareRows) {
    rowBase := Mux(depth, io.depthTileBase, io.colorTileBase) + firstRowOffset
    depthFirstRow := io.depthTileBase + firstRowOffset
    state := Mux(cancelled || io.cancel, complete, Mux(mask.andR, writeRequest, readRequest))
  }
  when(io.cancel && io.busy) { cancelled := true.B }
  when(io.mem.req.fire) { state := Mux(state === readRequest, readResponse, writeResponse) }
  when(io.mem.resp.fire) {
    assert(io.mem.resp.bits.addr === address(31, 0) &&
      io.mem.resp.bits.write === (state === writeResponse), "clear response lost its sample ownership")
    when(cancelled || io.cancel) { state := complete }
      .elsewhen(state === readResponse) {
        merged := (io.mem.resp.bits.data & ~mask) | (value & mask)
        state := writeRequest
      }.otherwise {
        val nextState = Mux(mask.andR, writeRequest, readRequest)
        state := nextState
        when(sample +& 1.U < (1.U(3.W) << io.descriptor.sampleMode)) {
          sample := sample + 1.U
        }.otherwise {
          sample := 0.U
          when(x +& 1.U < command.maxX) { x := x + 1.U }
            .otherwise {
              x := command.minX
              when(y +& 1.U < command.maxY) {
                y := y + 1.U
                rowBase := rowBase + view.rowPitch
              }
                .otherwise {
                  y := command.minY
                  when(!depth && command.depthMask.orR) {
                    depth := true.B
                    rowBase := depthFirstRow
                    state := Mux(command.depthMask.andR, writeRequest, readRequest)
                  }.otherwise { state := complete }
                }
            }
        }
      }
  }
  when(io.done.fire) { state := idle }
}
