package opengpu.graphics

import chisel3._
import chisel3.util._

/** Debug message transport envelope.
  *
  * Attaches the current 64-bit timestamp and, when the current debug-group
  * label is valid and has a nonzero object type, its object label key. A
  * record with no valid group retains a cleared label so downstream consumers
  * cannot attribute it to a stale object. Payload and metadata pass unchanged;
  * backpressure is forwarded to the producer.
  */
class DebugMessageEnvelope extends Bundle {
  val message=new DebugMessage
  val timestamp=UInt(64.W)
  val labelValid=Bool()
  val label=new DebugLabelKey
}

class DebugMessageEnvelopeUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new DebugMessage))
    val timestamp=Input(UInt(64.W))
    val label=Input(new DebugLabelKey);val labelValid=Input(Bool())
    val out=Decoupled(new DebugMessageEnvelope)
  })
  private val attach=io.labelValid && io.label.objectType=/=0.U
  io.out.valid:=io.in.valid
  io.in.ready:=io.out.ready
  io.out.bits.message:=io.in.bits
  io.out.bits.timestamp:=io.timestamp
  io.out.bits.labelValid:=attach
  io.out.bits.label:=Mux(attach,io.label,0.U.asTypeOf(new DebugLabelKey))
}
