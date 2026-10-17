package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

/** One synchronous single-port word service over characterized 256x32 banks.
  * Bank = high word-index bits, row = low eight bits. This H1 service issues
  * one operation/cycle; H3 will provide independent pixel-bank request ports.
  * The behavioral path has the same read latency and write priority.
  */
class TileAttachmentSram(words: Int, useBlackBoxes: Boolean = false) extends Module {
  require(Set(256, 512, 1024)(words))
  private val banks = words / 256
  private val addressBits = log2Ceil(words)
  val io = IO(new Bundle {
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(addressBits.W))
    val readData = Output(UInt(32.W))
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(addressBits.W))
    val writeData = Input(UInt(32.W))
  })
  private val memories = Seq.fill(banks)(Module(new L2SramArray(256, 32, useBlackBoxes)))
  private val readBank = if (banks == 1) 0.U else io.readAddress(addressBits - 1, 8)
  private val writeBank = if (banks == 1) 0.U else io.writeAddress(addressBits - 1, 8)
  private val heldBank = RegEnable(readBank, io.readEnable)
  for ((memory, i) <- memories.zipWithIndex) {
    memory.io.readEnable := io.readEnable && readBank === i.U
    memory.io.readAddress := io.readAddress(7, 0)
    memory.io.writeEnable := io.writeEnable && writeBank === i.U
    memory.io.writeAddress := io.writeAddress(7, 0)
    memory.io.writeData := io.writeData
  }
  io.readData := (if (banks == 1) memories.head.io.readData
    else VecInit(memories.map(_.io.readData))(heldBank))
  assert(!(io.readEnable && io.writeEnable), "tile service must serialize single-port SRAM operations")
}
