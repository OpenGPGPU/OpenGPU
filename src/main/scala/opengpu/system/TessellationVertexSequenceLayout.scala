package opengpu.system

import chisel3._
import opengpu.graphics._

/** New GNT7/8/9 records inherit the exact domain/GUR3 fields. The original
  * descriptors and all old wire layouts remain unchanged. */
class TessellationVertexSequenceRecord[D <: Data](sequenceType:D) extends Bundle {
  val sequence=sequenceType.cloneType
  val sourceBase=UInt(32.W);val sourceLimit=UInt(33.W)
}
object TessellationVertexSequenceLayout {
  val words=512;val bytes=2048;val lines=32
  def version(domain:Int):Int={require(domain>=0 && domain<3);7+domain}
  def magic(domain:Int):BigInt=BigInt("474e5400",16)+version(domain)
  def fields[D <: Data](r:TessellationVertexSequenceRecord[D],domain:Int):Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    val inherited=domain match {
      case 0=>TessellationNativeSequenceLayout.fields(r.sequence.asInstanceOf[TessellationPatchSequenceDescriptor],true)
        .map(f=>Field(f.name,f.word,f.value))
      case 1=>TessellationNativeTriangleSequenceLayout.fields(r.sequence.asInstanceOf[TessellationTrianglePatchSequenceDescriptor],true)
        .map(f=>Field(f.name,f.word,f.value))
      case 2=>TessellationNativeQuadSequenceLayout.fields(r.sequence.asInstanceOf[TessellationQuadPatchSequenceDescriptor],true)
        .map(f=>Field(f.name,f.word,f.value))
      case _=>throw new IllegalArgumentException("unknown tessellation domain")
    }
    inherited++Seq(Field("vertexSource.base",509,r.sourceBase),Field("vertexSource.limit",510,r.sourceLimit))
  }
}

/** The enclosing accepted owner supplies both ordinals and the private scope.
  * GNQ's array index is not an original-draw ordinal, and a decoded GVS root
  * cannot authorize its own expected values. */
class TessellationVertexSequenceRequest(idWidth:Int) extends Bundle {
  val commandId=UInt(idWidth.W);val frameIdentity=new GraphicsWorkIdentity
  val view=new VertexInputView;val privateBase=UInt(32.W);val privateLimit=UInt(33.W)
  val expectedSequenceIndex=UInt(32.W);val expectedDrawIndex=UInt(32.W)
}
class TessellationVertexSequenceCompletion[D <: Data](sequenceType:D,idWidth:Int) extends Bundle {
  /** 0 captured, 1 encoding/bounds/scope, 2 descriptor memory, 4 cancelled. */
  val commandId=UInt(idWidth.W);val status=UInt(3.W);val faultAddress=UInt(32.W);val readBytes=UInt(12.W)
  /** Captured metadata only; do not feed this to an old DMA sequencer. */
  val sequence=sequenceType.cloneType
  val sourceRequest=new TessellationVertexSourceRequest
}
