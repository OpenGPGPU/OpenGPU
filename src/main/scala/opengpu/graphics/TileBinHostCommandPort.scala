package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.command.{GpuCommand,GpuCommandOpcode,GpuCommandResult}
import opengpu.config.GpuConfig

object TileBinHostRegs {
  /** Always mapped legacy reserved word: zero on older profiles. */
  val DISCOVERY=0x68
  val CAPABILITIES=0x160
  val END=0x180
}

/** Explicit opt-in opcode-7 GBJ1 routing. Ordinary kernel work may overlap
  * rendering in the same pinned VM; memory/DMA commands serialize with it.
  * The upstream MMIO bridge retains queued commands and software completions.
  */
class TileBinHostCommandPort(config: GpuConfig=GpuConfig(),commandIdWidth: Int=8) extends Module {
  val io=IO(new Bundle {
    val command=Flipped(Decoupled(new GpuCommand(config,commandIdWidth)))
    val otherCommand=Decoupled(new GpuCommand(config,commandIdWidth))
    val submit=Decoupled(new TileJobDescriptorRequest(commandIdWidth))
    val renderCompletion=Flipped(Decoupled(new TileBinJobCompletion(commandIdWidth)))
    val otherCompletion=Flipped(Decoupled(new GpuCommandResult(commandIdWidth)))
    val completion=Decoupled(new GpuCommandResult(commandIdWidth))
    val dependencyIdle=Input(Bool())
    val renderIdle=Input(Bool())
    val resetActive=Input(Bool())
    val cancel=Output(Bool())
    /** Also guards legacy DMA register kicks before a queued render begins. */
    val renderPending=Output(Bool())
    val drained=Output(Bool())
  })
  private val c=io.command.bits
  private val isRender=c.opcode===GpuCommandOpcode.tileRender
  private val otherAllowed=c.opcode===GpuCommandOpcode.kernel || io.renderIdle
  io.renderPending:= !io.renderIdle || (io.command.valid && isRender)
  io.submit.valid:=io.command.valid && isRender && io.dependencyIdle && !io.resetActive
  io.submit.bits.commandId:=c.commandId; io.submit.bits.base:=c.sourceAddress
  io.submit.bits.limit:=Cat(c.pattern(0),c.destinationAddress)
  io.submit.bits.envelopeValid:=c.pattern(31,1)===0.U && c.bytes===TileBinJobMemoryLayout.bytes.U &&
    !c.waitForDma && !c.waitForEvent && !c.signalEvent
  io.otherCommand.valid:=io.command.valid && !isRender && otherAllowed && !io.resetActive
  io.otherCommand.bits:=c
  io.command.ready:= !io.resetActive && Mux(isRender,
    io.submit.ready && io.dependencyIdle,io.otherCommand.ready && otherAllowed)
  io.cancel:=io.resetActive
  private val completions=Module(new RRArbiter(new GpuCommandResult(commandIdWidth),2))
  completions.io.in(0).valid:=io.renderCompletion.valid
  completions.io.in(0).bits.commandId:=io.renderCompletion.bits.commandId
  completions.io.in(0).bits.opcode:=GpuCommandOpcode.tileRender
  completions.io.in(0).bits.status:=io.renderCompletion.bits.status
  completions.io.in(0).bits.success:=io.renderCompletion.bits.status===0.U
  // Requested append-line bytes, including padding/faults, excluding descriptor.
  completions.io.in(0).bits.bytesProcessed:=io.renderCompletion.bits.appendReadBytes
  io.renderCompletion.ready:=completions.io.in(0).ready
  completions.io.in(1) <> io.otherCompletion
  private val results=Module(new Queue(new GpuCommandResult(commandIdWidth),2,pipe=true))
  results.io.enq <> completions.io.out
  io.completion <> results.io.deq
  io.drained:= !results.io.deq.valid && !io.renderCompletion.valid && !io.otherCompletion.valid
}
