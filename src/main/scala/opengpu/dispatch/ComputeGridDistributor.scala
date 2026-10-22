package opengpu.dispatch

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Expand compute grids into private tagged workgroup tasks. Graphics arbitration,
  * physical completion credits and payload ownership remain in the shared arbiter.
  *
  * Parent rows remain reserved through public completion acceptance. Child tags
  * remain reserved through real child completion acceptance. After a child fault,
  * no new groups are offered; an already offered group is still owned and drains.
  */
class ComputeGridDistributor(
  config: GpuConfig = GpuConfig(), numComputeUnits: Int = 4, commandIdWidth: Int = 8
) extends Module {
  require(numComputeUnits > 0 && commandIdWidth > 0)
  val slots = BigInt(numComputeUnits).min(BigInt(1) << commandIdWidth).toInt
  private val slotWidth = math.max(1, log2Ceil(slots))
  private val countWidth = math.max(1, log2Ceil(slots + 1))
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new TaggedKernelLaunch(config, commandIdWidth)))
    val completion = Decoupled(new TaggedKernelCompletion(commandIdWidth))
    val childLaunch = Decoupled(new TaggedKernelLaunch(config, commandIdWidth))
    val childGroup = Output(new KernelGridSlice)
    val childCompletion = Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val childParentIds = Output(Vec(slots, UInt(commandIdWidth.W)))
  })

  private val live = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val queued = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val parent = Reg(Vec(slots, new TaggedKernelLaunch(config, commandIdWidth)))
  private val group = Reg(Vec(slots, Vec(3, UInt(32.W))))
  private val limit = Reg(Vec(slots, Vec(3, UInt(32.W))))
  private val last = Reg(Vec(slots, Vec(3, Bool())))
  private val allIssued = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val failed = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val pending = RegInit(VecInit(Seq.fill(slots)(0.U(countWidth.W))))
  private val childLive = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val childParent = Reg(Vec(slots, UInt(slotWidth.W)))

  private val offerValid = RegInit(false.B)
  private val offer = Reg(new TaggedKernelLaunch(config, commandIdWidth))
  private val offerGroup = Reg(Vec(3, UInt(32.W)))
  private val offerParent = Reg(UInt(slotWidth.W))
  io.childLaunch.valid := offerValid
  io.childLaunch.bits := offer
  io.childGroup.singleGroup := true.B
  io.childGroup.groupId := offerGroup
  private val issue = io.childLaunch.fire
  private val offerTag = offer.commandId

  private val retireTag = io.childCompletion.bits.commandId
  private val retireInRange = retireTag < slots.U
  private val retireActive = retireInRange && childLive(retireTag)
  io.childCompletion.ready := retireActive
  private val retire = io.childCompletion.fire
  private val retireParent = childParent(retireTag)
  when(io.childCompletion.valid) {
    assert(retireActive, "compute child completion must identify an owned tag")
  }
  for (slot <- 0 until slots) {
    io.childParentIds(slot) := parent(childParent(slot)).commandId
    when(issue && offerTag === slot.U) {
      assert(!childLive(slot), "compute child tag cannot be reused before retirement")
      childLive(slot) := true.B
      childParent(slot) := offerParent
    }
    when(retire && retireTag === slot.U) { childLive(slot) := false.B }
  }

  private val freeParent = ~live.asUInt
  private val newRow = if (slots == 1) 0.U else PriorityEncoder(freeParent)
  private val duplicate = VecInit((0 until slots).map(i =>
    live(i) && parent(i).commandId === io.launch.bits.commandId)).asUInt.orR
  io.launch.ready := freeParent.orR && !duplicate
  when(io.launch.fire) {
    live(newRow) := true.B
    queued(newRow) := false.B
    parent(newRow) := io.launch.bits
    pending(newRow) := 0.U
    val nonEmpty = io.launch.bits.launch.gridSize.map(_ =/= 0.U).reduce(_ && _)
    failed(newRow) := !nonEmpty
    allIssued(newRow) := !nonEmpty
    for (axis <- 0 until 3) {
      group(newRow)(axis) := 0.U
      limit(newRow)(axis) := io.launch.bits.launch.gridSize(axis) - 1.U
      last(newRow)(axis) := io.launch.bits.launch.gridSize(axis) === 1.U
    }
  }

  private val freeChild = ~childLive.asUInt
  private val newChild = if (slots == 1) 0.U else PriorityEncoder(freeChild)
  private val select = Module(new RRArbiter(UInt(slotWidth.W), slots))
  for (row <- 0 until slots) {
    // A fault accepted on this edge also blocks creating a new offer for that row.
    val failingNow = retire && retireParent === row.U && !io.childCompletion.bits.success
    select.io.in(row).valid := live(row) && !queued(row) && !allIssued(row) &&
      !failed(row) && !failingNow
    select.io.in(row).bits := row.U
  }
  select.io.out.ready := !offerValid && freeChild.orR
  when(select.io.out.fire) {
    offerValid := true.B
    offer := parent(select.io.out.bits)
    offer.commandId := newChild
    offerGroup := group(select.io.out.bits)
    offerParent := select.io.out.bits
  }
  when(issue) { offerValid := false.B }

  private class ParentResult extends Bundle {
    val row = UInt(slotWidth.W)
    val result = new TaggedKernelCompletion(commandIdWidth)
  }
  private val done = Module(new RRArbiter(new ParentResult, slots))
  private val results = Module(new Queue(new ParentResult, slots))
  results.io.enq <> done.io.out
  io.completion.valid := results.io.deq.valid
  io.completion.bits := results.io.deq.bits.result
  results.io.deq.ready := io.completion.ready

  for (row <- 0 until slots) {
    val issuing = issue && offerParent === row.U
    val retiring = retire && retireParent === row.U
    when(issuing =/= retiring) {
      pending(row) := Mux(issuing, pending(row) + 1.U, pending(row) - 1.U)
    }
    when(retiring) {
      assert(live(row) && pending(row) =/= 0.U, "compute parent retirement underflow")
      when(!io.childCompletion.bits.success) { failed(row) := true.B }
    }
    when(issuing) {
      assert(live(row) && !queued(row), "offered work must retain its parent")
      when(last(row).reduce(_ && _)) { allIssued(row) := true.B }
      // Registered last flags keep the XYZ carry off completion retirement.
      when(!last(row)(0)) {
        val next = group(row)(0) + 1.U
        group(row)(0) := next
        last(row)(0) := next === limit(row)(0)
      }.otherwise {
        group(row)(0) := 0.U
        last(row)(0) := limit(row)(0) === 0.U
        when(!last(row)(1)) {
          val next = group(row)(1) + 1.U
          group(row)(1) := next
          last(row)(1) := next === limit(row)(1)
        }.otherwise {
          group(row)(1) := 0.U
          last(row)(1) := limit(row)(1) === 0.U
          val next = group(row)(2) + 1.U
          group(row)(2) := next
          last(row)(2) := next === limit(row)(2)
        }
      }
    }
    val offeredForRow = offerValid && offerParent === row.U
    done.io.in(row).valid := live(row) && !queued(row) &&
      (allIssued(row) || failed(row)) && pending(row) === 0.U && !offeredForRow
    done.io.in(row).bits.row := row.U
    done.io.in(row).bits.result.commandId := parent(row).commandId
    done.io.in(row).bits.result.success := !failed(row)
    when(done.io.in(row).fire) { queued(row) := true.B }
    when(results.io.deq.fire && results.io.deq.bits.row === row.U) { live(row) := false.B }
    assert(pending(row) <= slots.U, "compute parent exceeded physical child ownership")
  }
}
