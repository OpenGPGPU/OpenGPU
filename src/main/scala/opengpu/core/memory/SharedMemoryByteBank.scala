package opengpu.core.memory

import chisel3._
import chisel3.util._

/** One byte operation per cycle with one-cycle read latency.
  *
  * Characterized memories have 256 rows and a minimum width of 32 bits. Each
  * segment uses its low byte; padding preserves independent byte-bank writes
  * without a read/modify/write cycle or a reduction in bank throughput.
  */
class SharedMemoryByteBank(depth: Int, useBlackBoxes: Boolean = false) extends Module {
  require(depth > 0 && isPow2(depth))
  private val addressWidth = math.max(1, log2Ceil(depth))
  val io = IO(new Bundle {
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(addressWidth.W))
    val readData = Output(UInt(8.W))
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(addressWidth.W))
    val writeData = Input(UInt(8.W))
  })

  if (useBlackBoxes) {
    val segments = math.max(1, depth / 256)
    val memories = Seq.fill(segments)(Module(new L2SramArray(256, 8, useBlackBox = true)))
    val readSegment = if (segments == 1) 0.U else io.readAddress(addressWidth - 1, 8)
    val writeSegment = if (segments == 1) 0.U else io.writeAddress(addressWidth - 1, 8)
    val heldSegment = RegEnable(readSegment, io.readEnable && !io.writeEnable)
    for ((memory, index) <- memories.zipWithIndex) {
      memory.io.readEnable := io.readEnable && readSegment === index.U
      memory.io.readAddress := io.readAddress.pad(8)(7, 0)
      memory.io.writeEnable := io.writeEnable && writeSegment === index.U
      memory.io.writeAddress := io.writeAddress.pad(8)(7, 0)
      memory.io.writeData := io.writeData
    }
    io.readData := (if (segments == 1) memories.head.io.readData
      else VecInit(memories.map(_.io.readData))(heldSegment))
  } else {
    val memory = SyncReadMem(depth, UInt(8.W))
    io.readData := memory.read(io.readAddress, io.readEnable && !io.writeEnable)
    when(io.writeEnable) { memory.write(io.writeAddress, io.writeData) }
  }
  assert(!(io.readEnable && io.writeEnable), "shared byte bank must serialize its single port")
}
