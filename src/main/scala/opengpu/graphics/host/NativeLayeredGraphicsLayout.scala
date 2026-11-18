package opengpu.graphics

import chisel3._

/** GNJ2 explicitly owns a complete, separately bounded GNY2 writeback. A raw
  * trusted caller must retain this writable mapping and all immutable source
  * mappings until the held coarse command result drains. Protected submission
  * additionally enforces physical allocation/CPU writer exclusion.
  */
class NativeLayeredCompletionView extends VertexInputView {val writable=Bool()}
class NativeLayeredGraphicsJobRecord extends NativeBinnedGraphicsJobRecord {
  val completion=new NativeLayeredCompletionView
}
class NativeLayeredFrameResources extends Bundle {
  val version=UInt(8.W);val layers=UInt(9.W)
  val sourcePitch=Vec(6,UInt(33.W));val resolvePitch=Vec(6,UInt(33.W))
}
class NativeLayeredGraphicsFrameRecord extends NativeBinnedGraphicsFrameRecord {
  val layered=new NativeLayeredFrameResources
}
object NativeLayeredGraphicsLayout {
  val jobMagic=BigInt("474e4a02",16);val jobVersion=2
  val frameMagic=BigInt("474e4603",16);val frameVersion=3
  val completionMagic=BigInt("474e5902",16);val completionVersion=2
  // Includes the exact full-capacity producer restart record and all wide
  // source/image counters. This is a bounded output view, not an in-band
  // reinterpretation of the old twelve GNX1 registers.
  val completionWords=4096;val completionBytes=completionWords*4
  def jobFields(r:NativeLayeredGraphicsJobRecord):Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    NativeBinnedGraphicsLayout.jobFields(r)++Seq(Field("completion.base",48,r.completion.base),
      Field("completion.limit",49,r.completion.limit),Field("completion.allocationBase",51,r.completion.allocationBase),
      Field("completion.allocationLimit",52,r.completion.allocationLimit),Field("completion.writable",54,r.completion.writable))
  }
  def frameFields(r:NativeLayeredGraphicsFrameRecord):Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    NativeBinnedGraphicsLayout.frameFields(r)++Seq(Field("layered.version",320,r.layered.version),Field("layered.layers",321,r.layered.layers))++
      r.layered.sourcePitch.zipWithIndex.map{case(v,i)=>Field(s"layered.sourcePitch[$i]",322+2*i,v)}++
      r.layered.resolvePitch.zipWithIndex.map{case(v,i)=>Field(s"layered.resolvePitch[$i]",334+2*i,v)}
  }
  def completionLegal(v:NativeLayeredCompletionView):Bool=v.base(5,0)===0.U && v.writable &&
    v.base.pad(34)+completionBytes.U<=v.limit && v.limit<=(BigInt(1)<<32).U &&
    v.allocationBase(5,0)===0.U && v.allocationLimit(5,0)===0.U && v.allocationLimit<=(BigInt(1)<<32).U &&
    v.base>=v.allocationBase && v.limit<=v.allocationLimit
}
