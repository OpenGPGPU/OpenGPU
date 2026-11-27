package opengpu.system

import chisel3._
import chisel3.util._

/** Independent GNQ7/8/9 v3. The old GNQ4/5/6 validator never admits these
  * records. Bounds describe an already authorized immutable private owner;
  * neither this reader nor a numeric envelope grants mapping authority.
  */
object TessellationJointGeometryJobLayout {
  import TessellationJointJobLayout.{wide,maxVA,exact,overlaps}
  val jobMagics=Seq("474e5107","474e5108","474e5109").map(BigInt(_,16))
  val attachmentMagic=BigInt("47474101",16)
  val attachmentHeaderBytes=64
  val attachmentEntryBytes=1024
  val header=Seq(512,3,16,16,16,4,4)
  def decode(w:Vec[UInt],j:TessellationJointGeometryJob):Unit={
    TessellationJointJobLayout.decodeJob(w,j)
    j.geometryVersion:=w(36);j.geometry.base:=w(37);j.geometry.limit:=Cat(w(39)(0),w(38))
    j.geometryCount:=w(40);j.geometryEntryBytes:=w(41);j.geometryBytes:=w(42)
  }
  def encoding(w:Vec[UInt],domain:UInt):Bool={
    val magic=MuxLookup(domain,0.U(32.W))(jobMagics.zipWithIndex.map{case(m,i)=>i.U->m.U(32.W)})
    domain<3.U && w(0)===magic && header.zipWithIndex.map{case(v,i)=>w(i+1)===v.U}.reduce(_ && _) &&
      (8 until 16).map(w(_)===0.U).reduce(_ && _) && (43 until 128).map(w(_)===0.U).reduce(_ && _) &&
      Seq(19,23,30,39).map(i=> !w(i)(31,1).orR).reduce(_ && _)
  }
  def legal(j:TessellationJointGeometryJob,q:TessellationJointGeometryJobRequest):Bool={
    val bytes=attachmentHeaderBytes.U(65.W)+wide(j.sequenceCount)*attachmentEntryBytes.U
    val empty=j.geometryVersion===0.U && j.geometry.asUInt===0.U && j.geometryCount===0.U &&
      j.geometryEntryBytes===0.U && j.geometryBytes===0.U
    val populated=j.geometryVersion===1.U && j.geometryCount===j.sequenceCount &&
      j.geometryEntryBytes===attachmentEntryBytes.U && bytes<=BigInt("ffffffff",16).U &&
      wide(j.geometryBytes)===bytes && exact(j.geometry,bytes,q.owner) &&
      !overlaps(j.geometry,q.view) && !overlaps(j.geometry,j.frame) &&
      !overlaps(j.geometry,j.sequence) && !overlaps(j.geometry,j.directory)
    TessellationJointJobLayout.jobLegal(j,q) && Mux(j.sequenceCount===0.U,empty,populated)
  }
}
/** A distinct native front door; a legacy TileJobDescriptorRequest has no
  * owner scope/identity and cannot be substituted or used to infer them. */
class TessellationJointGeometryJobRequest(idWidth:Int) extends TessellationJointJobRequest(idWidth)
class TessellationJointGeometryJob extends TessellationJointJob {
  val geometryVersion=UInt(32.W);val geometry=new TessellationJointView
  val geometryCount=UInt(32.W);val geometryEntryBytes=UInt(32.W);val geometryBytes=UInt(32.W)
}
class TessellationJointGeometryJobCapture(idWidth:Int) extends Bundle {
  val request=new TessellationJointGeometryJobRequest(idWidth);val job=new TessellationJointGeometryJob
}
class TessellationJointGeometryJobReadCompletion(idWidth:Int) extends Bundle {
  /** 0 captured, 1 encoding/bounds, 2 memory, 4 cancel, 6 unavailable. */
  val status=UInt(3.W);val commandId=UInt(idWidth.W);val faultAddress=UInt(32.W);val readBytes=UInt(64.W)
  val clearOnly=Bool();val captured=new TessellationJointGeometryJobCapture(idWidth)
}

/** The original publication count remains TCS/TES-only. Wider GS and bin
  * inventories are copied only from the actual completed image frame. */
class TessellationJointGeometryNativeCompletion(idWidth:Int) extends Bundle {
  val base=new TessellationJointNativeCompletion(idWidth)
  val geometryReportValid=Bool();val actualGsPublished=UInt(158.W)
  val received=UInt(161.W);val dropped=UInt(161.W);val admitted=UInt(97.W)
  val geometryTasks=UInt(64.W);val nextGsSequenceExclusive=UInt(65.W)
  val nextGsSequence=UInt(64.W);val nextBinSequence=UInt(33.W)
}
