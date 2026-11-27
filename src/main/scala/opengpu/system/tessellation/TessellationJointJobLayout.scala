package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.GraphicsWorkIdentity

/** GNQ4/5/6 and attached GVM2. The committed JSON, not Bundle packing, is the external ABI. No address or numeric
  * envelope confers mapping authority.
  */
object TessellationJointJobLayout {
  val jobBytes = 512; val frameBytes = 2048; val sequenceBytes = 2048; val sourceBytes = 512
  val directoryHeaderBytes = 64; val directoryEntryBytes = 64
  val jobMagics = Seq("474e5104", "474e5105", "474e5106").map(BigInt(_, 16))
  val sequenceMagics = Seq("474e5407", "474e5408", "474e5409").map(BigInt(_, 16))
  val directoryMagic = BigInt("47564d02", 16)
  val header = Seq(512, 2, 16, 16, 16, 4, 4)
  val jobFields = Seq(
    "sequence.base" -> 16,
    "sequence.count" -> 17,
    "sequence.limit" -> 18,
    "sequence.stride" -> 20,
    "frame.base" -> 21,
    "frame.limit" -> 22,
    "identity.context" -> 24,
    "identity.generation" -> 25,
    "identity.pass" -> 26,
    "directory.version" -> 27,
    "directory.base" -> 28,
    "directory.limit" -> 29,
    "directory.count" -> 31,
    "directory.bytes" -> 32,
    "directory.entryBytes" -> 33,
    "directory.drawCount" -> 34,
    "directory.invocationCount" -> 35
  )
  val entryFields = Seq(
    "sequenceIndex" -> 0,
    "drawIndex" -> 1,
    "invocationId" -> 2,
    "flags" -> 4,
    "rangeOrdinal" -> 5,
    "firstInstance" -> 6,
    "instanceCount" -> 7,
    "firstInputPatch" -> 8,
    "patchesPerInstance" -> 9,
    "taskIdBase" -> 10,
    "patchCount" -> 11,
    "source.base" -> 12,
    "source.limit" -> 13
  )
  val maxVA = BigInt(1) << 32
  def wide(x: UInt): UInt = x.pad(65)
  def inside(base: UInt, end: UInt, owner: TessellationJointOwnerScope): Bool =
    end > wide(base) && end <= maxVA.U && base >= owner.base && end <= wide(owner.limit)
  def scopeLegal(owner: TessellationJointOwnerScope): Bool = owner.base(5, 0) === 0.U &&
    owner.limit(5, 0) === 0.U && owner.limit > owner.base && owner.limit <= maxVA.U
  def exact(view: TessellationJointView, bytes: UInt, owner: TessellationJointOwnerScope): Bool = {
    val end = wide(view.base) + bytes.pad(65)
    view.base(5, 0) === 0.U && end === wide(view.limit) && inside(view.base, end, owner)
  }
  def overlaps(a: TessellationJointView, b: TessellationJointView): Bool =
    a.limit > a.base && b.limit > b.base && a.base < b.limit && b.base < a.limit
  def jobLegal(j: TessellationJointJob, q: TessellationJointJobRequest): Bool = {
    val n = j.sequenceCount; val sequenceExtent = (wide(n) - 1.U) * wide(j.sequenceStride) + sequenceBytes.U
    val directoryExtent = 64.U(65.W) + wide(n) * 64.U
    val empty = j.sequence.asUInt === 0.U && j.sequenceStride === 0.U && j.directory.asUInt === 0.U &&
      j.directoryVersion === 0.U && j.directoryCount === 0.U && j.directoryBytes === 0.U &&
      j.directoryEntryBytes === 0.U && j.drawCount === 0.U && j.invocationCount === 0.U
    val nonempty = j.sequenceStride >= sequenceBytes.U && j.sequenceStride(5, 0) === 0.U &&
      sequenceExtent <= maxVA.U && directoryExtent <= BigInt("ffffffff", 16).U &&
      j.directoryVersion === 2.U && j.directoryCount === n && wide(j.directoryBytes) === directoryExtent &&
      j.directoryEntryBytes === 64.U && j.drawCount =/= 0.U && j.invocationCount =/= 0.U && j.invocationCount <= n &&
      exact(j.sequence, sequenceExtent, q.owner) && exact(j.directory, directoryExtent, q.owner) &&
      !overlaps(j.sequence, j.frame) && !overlaps(j.directory, j.frame) && !overlaps(j.sequence, j.directory) &&
      !overlaps(j.sequence, q.view) && !overlaps(j.directory, q.view)
    q.domain < 3.U && scopeLegal(q.owner) && exact(j.frame, frameBytes.U(65.W), q.owner) &&
    !overlaps(j.frame, q.view) && j.identity.asUInt === q.identity.asUInt && Mux(n === 0.U, empty, nonempty)
  }
  def requestLegal(q: TessellationJointJobRequest): Bool = scopeLegal(q.owner) && q.domain < 3.U &&
    exact(q.view, jobBytes.U(65.W), q.owner) && q.view.allocationBase(5, 0) === 0.U &&
    q.view.allocationLimit(5, 0) === 0.U && q.view.allocationLimit > q.view.allocationBase &&
    q.view.allocationLimit <= maxVA.U && q.view.allocationBase >= q.owner.base && q.view.allocationLimit <= q.owner.limit &&
    q.view.base >= q.view.allocationBase && q.view.limit <= q.view.allocationLimit
  def decodeJob(w: Vec[UInt], j: TessellationJointJob): Unit = {
    j.sequence.base := w(16); j.sequenceCount := w(17); j.sequence.limit := Cat(w(19)(0), w(18));
    j.sequenceStride := w(20)
    j.frame.base := w(21); j.frame.limit := Cat(w(23)(0), w(22))
    j.identity.context := w(24); j.identity.generation := w(25); j.identity.pass := w(26)
    j.directoryVersion := w(27); j.directory.base := w(28); j.directory.limit := Cat(w(30)(0), w(29))
    j.directoryCount := w(31); j.directoryBytes := w(32); j.directoryEntryBytes := w(33)
    j.drawCount := w(34); j.invocationCount := w(35)
  }
  def jobEncoding(w: Vec[UInt], domain: UInt): Bool = {
    val magic = MuxLookup(domain, 0.U(32.W))(jobMagics.zipWithIndex.map { case (m, i) => i.U -> m.U(32.W) })
    w(0) === magic && header.zipWithIndex.map { case (v, i) => w(i + 1) === v.U }.reduce(_ && _) &&
    (8 until 16).map(w(_) === 0.U).reduce(_ && _) && (36 until 128).map(w(_) === 0.U).reduce(_ && _) &&
    Seq(19, 23, 30).map(i => !w(i)(31, 1).orR).reduce(_ && _)
  }
  def decodeEntry(w: Vec[UInt], e: TessellationJointDirectoryEntry): Unit = {
    e.sequenceIndex := w(0); e.drawIndex := w(1); e.invocationId := Cat(w(3), w(2)); e.flags := w(4)(2, 0)
    e.rangeOrdinal := w(5); e.firstInstance := w(6); e.instanceCount := w(7); e.firstInputPatch := w(8)
    e.patchesPerInstance := w(9); e.taskIdBase := w(10); e.patchCount := w(11)
    e.source.base := w(12); e.source.limit := Cat(w(14)(0), w(13))
  }
  def entryEncoding(w: Vec[UInt]): Bool = !w(4)(31, 3).orR && !w(14)(31, 1).orR && w(15) === 0.U
  def entryLegal(
      e: TessellationJointDirectoryEntry,
      job: TessellationJointJob,
      q: TessellationJointJobRequest
  ): Bool = {
    val count = wide(e.instanceCount) * wide(e.patchesPerInstance)
    e.drawIndex < job.drawCount && count === wide(e.patchCount) &&
    wide(e.firstInstance) + wide(e.instanceCount) <= maxVA.U &&
    wide(e.firstInputPatch) + wide(e.patchesPerInstance) <= maxVA.U &&
    wide(e.taskIdBase) + wide(e.patchCount) <= maxVA.U && exact(e.source, sourceBytes.U(65.W), q.owner) &&
    Mux(
      e.patchCount === 0.U,
      e.firstInstance === 0.U && e.instanceCount === 0.U &&
        e.firstInputPatch === 0.U && e.patchesPerInstance === 0.U,
      true.B
    )
  }
}

class TessellationJointOwnerScope extends Bundle { val base = UInt(32.W); val limit = UInt(33.W) }
class TessellationJointView extends Bundle { val base = UInt(32.W); val limit = UInt(33.W) }
class TessellationJointAllocatedView extends TessellationJointView {
  val allocationBase = UInt(32.W); val allocationLimit = UInt(33.W)
}
class TessellationJointJobRequest(idWidth: Int) extends Bundle {
  val commandId = UInt(idWidth.W); val domain = UInt(2.W); val view = new TessellationJointAllocatedView
  val owner = new TessellationJointOwnerScope; val identity = new GraphicsWorkIdentity
}
class TessellationJointJob extends Bundle {
  val sequence = new TessellationJointView; val sequenceCount = UInt(32.W); val sequenceStride = UInt(32.W)
  val frame = new TessellationJointView; val identity = new GraphicsWorkIdentity
  val directoryVersion = UInt(32.W); val directory = new TessellationJointView
  val directoryCount = UInt(32.W); val directoryBytes = UInt(32.W); val directoryEntryBytes = UInt(32.W)
  val drawCount = UInt(32.W); val invocationCount = UInt(32.W)
}
class TessellationJointJobCapture(idWidth: Int) extends Bundle {
  val request = new TessellationJointJobRequest(idWidth); val job = new TessellationJointJob
}
class TessellationJointJobReadCompletion(idWidth: Int) extends Bundle {

  /** 0 metadata captured, 1 encoding/bounds, 2 memory, 4 cancel, 6 unavailable. */
  val status = UInt(3.W); val commandId = UInt(idWidth.W); val faultAddress = UInt(32.W); val readBytes = UInt(64.W)
  val clearOnly = Bool(); val captured = new TessellationJointJobCapture(idWidth)
}
class TessellationJointDirectoryEntry extends Bundle {
  val sequenceIndex = UInt(32.W); val drawIndex = UInt(32.W); val invocationId = UInt(64.W); val flags = UInt(3.W)
  val rangeOrdinal = UInt(32.W); val firstInstance = UInt(32.W); val instanceCount = UInt(32.W)
  val firstInputPatch = UInt(32.W); val patchesPerInstance = UInt(32.W); val taskIdBase = UInt(32.W);
  val patchCount = UInt(32.W)
  val source = new TessellationJointView
}
class TessellationJointDirectoryCapture(idWidth: Int) extends Bundle {
  val commandId = UInt(idWidth.W); val domain = UInt(2.W); val identity = new GraphicsWorkIdentity
  val entry = new TessellationJointDirectoryEntry; val sequence = new TessellationJointView
}
class TessellationJointDirectoryCompletion(idWidth: Int) extends Bundle {
  val commandId = UInt(idWidth.W); val status = UInt(3.W); val faultAddress = UInt(32.W); val readBytes = UInt(64.W)

  /** Accepted metadata offers, never a count of executed/retired ranges. */
  val capturedEntries = UInt(32.W); val clearOnly = Bool()
}
