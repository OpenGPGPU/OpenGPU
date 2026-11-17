package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.GraphicsWorkIdentity

/** Full-width captured fields, before any narrower hardware layout cast. */
class TessellationJointPatchLayout extends Bundle {
  val inputVertices=UInt(32.W);val inputComponents=UInt(32.W)
  val outputVertices=UInt(32.W);val outputComponents=UInt(32.W);val patchComponents=UInt(32.W)
}
class TessellationJointSequenceEvidence extends Bundle {
  val view=new TessellationJointView;val magic=UInt(32.W);val version=UInt(32.W)
  val identity=new GraphicsWorkIdentity;val source=new TessellationJointView
  val taskIdBase=UInt(32.W);val patchCount=UInt(32.W);val layout=new TessellationJointPatchLayout
}
class TessellationJointSourceEvidence extends Bundle {
  val view=new TessellationJointView;val identity=new GraphicsWorkIdentity
  val sequenceIndex=UInt(32.W);val drawIndex=UInt(32.W);val taskIdBase=UInt(32.W);val patchCount=UInt(32.W)
  val layout=new TessellationJointPatchLayout;val sliceVersion=UInt(32.W);val firstOrdinal=UInt(32.W)
  val firstInstance=UInt(32.W);val instanceCount=UInt(32.W);val patchesPerInstance=UInt(32.W)
  val draw=new TessellationJointView
}
class TessellationJointDrawEvidence extends Bundle {
  val view=new TessellationJointView;val vertexCount=UInt(32.W);val instanceCount=UInt(32.W)
}

/** Cross-check actual captured GNT/GVS/GND observations against an independent
  * directory entry. The future parent must provide these observations from
  * the real readers and retain authorized BUILD/MMU leases; literal evidence
  * supplied to a component test is not actual source capture or admission.
  *
  * cursor is the previous successfully validated range's next position in
  * this original invocation, reset to zero only for FIRST. A successful
  * check does not mean that the range or its memory has retired. */
class TessellationJointSourceCrosslink(idWidth:Int) extends Module {
  require(idWidth>0 && idWidth<=12)
  import TessellationJointJobLayout._
  val io=IO(new Bundle {
    val captured=Input(new TessellationJointDirectoryCapture(idWidth))
    val owner=Input(new TessellationJointOwnerScope)
    val sequence=Input(new TessellationJointSequenceEvidence)
    val source=Input(new TessellationJointSourceEvidence);val draw=Input(new TessellationJointDrawEvidence)
    val cursorInstance=Input(UInt(33.W));val cursorPatch=Input(UInt(33.W))
    val legal=Output(Bool());val nextInstance=Output(UInt(33.W));val nextPatch=Output(UInt(33.W))
  })
  private val c=io.captured;private val e=c.entry;private val s=io.sequence;private val v=io.source;private val l=s.layout
  private val layoutLegal=l.inputVertices>=1.U && l.inputVertices<=32.U && l.outputVertices>=1.U && l.outputVertices<=32.U &&
    l.inputComponents<=64.U && l.outputComponents<=64.U && l.patchComponents<=120.U &&
    wide(l.inputVertices)*wide(l.inputComponents)<=2048.U &&
    wide(l.outputVertices)*wide(l.outputComponents)+wide(l.patchComponents)<=2048.U
  private val expectedMagic=MuxLookup(c.domain,0.U(32.W))(sequenceMagics.zipWithIndex.map{case(m,i)=>i.U->m.U(32.W)})
  private val dialect=c.domain<3.U && s.magic===expectedMagic && s.version===c.domain+&7.U
  private val exactViews=s.view.asUInt===c.sequence.asUInt && s.source.asUInt===e.source.asUInt &&
    v.view.asUInt===e.source.asUInt && v.draw.asUInt===io.draw.view.asUInt &&
    exact(s.view,sequenceBytes.U(65.W),io.owner) && exact(v.view,sourceBytes.U(65.W),io.owner) &&
    exact(io.draw.view,2048.U(65.W),io.owner)
  private val same=s.identity.asUInt===c.identity.asUInt && v.identity.asUInt===c.identity.asUInt &&
    v.sequenceIndex===e.sequenceIndex && v.drawIndex===e.drawIndex &&
    s.taskIdBase===e.taskIdBase && v.taskIdBase===e.taskIdBase &&
    s.patchCount===e.patchCount && v.patchCount===e.patchCount && v.layout.asUInt===l.asUInt
  private val firstOrdinal=wide(e.firstInputPatch)*wide(l.inputVertices)
  private val ordinalEnd=firstOrdinal+wide(e.patchesPerInstance)*wide(l.inputVertices)
  private val slice=v.sliceVersion===1.U && firstOrdinal<=BigInt("ffffffff",16).U && ordinalEnd<=maxVA.U &&
    wide(v.firstOrdinal)===firstOrdinal && v.firstInstance===e.firstInstance &&
    v.instanceCount===e.instanceCount && v.patchesPerInstance===e.patchesPerInstance &&
    wide(e.instanceCount)*wide(e.patchesPerInstance)===wide(e.patchCount) &&
    wide(e.taskIdBase)+wide(e.patchCount)<=maxVA.U
  // Division itself is exactly 32/32; only products, sums and address/range
  // extents need widened intermediates. An invalid zero divisor fails layout.
  private val per=wide(io.draw.vertexCount/Mux(l.inputVertices===0.U,1.U(32.W),l.inputVertices))
  private val totalInstances=wide(io.draw.instanceCount)
  private val empty=per===0.U || totalInstances===0.U
  private val first=e.flags(0);private val last=e.flags(1);private val continuation=e.flags(2)
  private val cursorInstance=Mux(first,0.U(65.W),wide(io.cursorInstance))
  private val cursorPatch=Mux(first,0.U(65.W),wide(io.cursorPatch))
  private val patchEnd=wide(e.firstInputPatch)+wide(e.patchesPerInstance)
  private val instanceEnd=wide(e.firstInstance)+wide(e.instanceCount)
  private val completeRow=patchEnd===per
  private val nextInstance=instanceEnd-Mux(completeRow,0.U(65.W),1.U(65.W))
  private val nextPatch=Mux(completeRow,0.U(65.W),patchEnd)
  private val rangeLegal=Mux(empty,
    first && last && !continuation && e.rangeOrdinal===0.U && e.patchCount===0.U &&
      e.firstInstance===0.U && e.instanceCount===0.U && e.firstInputPatch===0.U && e.patchesPerInstance===0.U,
    e.patchCount=/=0.U && e.firstInstance.pad(65)===cursorInstance && e.firstInputPatch.pad(65)===cursorPatch &&
      patchEnd<=per && instanceEnd<=totalInstances &&
      (e.instanceCount<=1.U || (e.firstInputPatch===0.U && wide(e.patchesPerInstance)===per)) &&
      last===(nextInstance===totalInstances) && first=/=continuation && (!first || e.rangeOrdinal===0.U))
  io.legal:=scopeLegal(io.owner) && layoutLegal && dialect && exactViews && same && slice && rangeLegal
  io.nextInstance:=Mux(io.legal && !empty,nextInstance(32,0),0.U)
  io.nextPatch:=Mux(io.legal && !empty,nextPatch(32,0),0.U)
}
