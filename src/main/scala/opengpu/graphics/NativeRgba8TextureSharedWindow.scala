package opengpu.graphics

import chisel3._
import chisel3.util._

private[graphics] class NativeTextureWindowTicket(clientBits: Int) extends Bundle {
  val request = new TextureRgba8GroupRequest
  val client = UInt(clientBits.W)
  val callerTag = UInt(32.W)
}

private[graphics] class NativeTextureWindowPayload extends Bundle {
  val rgba = Vec(4, UInt(32.W))
  val status = UInt(3.W); val faultSource = UInt(3.W)
  val faultAddressValid = Bool(); val faultAddress = UInt(32.W)
  val uniqueTaps = UInt(3.W); val dataReadLines = UInt(3.W); val fallback = Bool()
  def capture(r: NativeRgba8TextureResult): Unit = {
    rgba := r.rgba; status := r.status; faultSource := r.faultSource
    faultAddressValid := r.faultAddressValid; faultAddress := r.faultAddress
    uniqueTaps := r.uniqueTaps; dataReadLines := r.dataReadLines; fallback := r.fallback
  }
  def publish(r: NativeRgba8TextureResult): Unit = {
    r.rgba := rgba; r.status := status; r.faultSource := faultSource
    r.faultAddressValid := faultAddressValid; r.faultAddress := faultAddress
    r.uniqueTaps := uniqueTaps; r.dataReadLines := dataReadLines; r.fallback := fallback
  }
}

/** Logical response ownership shared across clients, with a fixed row bank per
  * physical cluster. Reservations protect other clients from a held hot-client
  * reply; otherwise spare rows are borrowed. Each pair allocates one row/cycle,
  * so two pairs can admit two requests/cycle. Numeric services retain their
  * existing cache, fill, data-ID, PTW and sample-owner capacities.
  */
class NativeRgba8TextureSharedWindow(clients: Int = 3, destinations: Int = 2,
    rowsPerDestination: Int = 128, reservedPerClient: Int = 4) extends Module {
  require(clients >= 1 && clients <= 16 && destinations >= 1 && destinations <= 8)
  require(isPow2(rowsPerDestination) && rowsPerDestination >= 4 && rowsPerDestination <= 128)
  require(reservedPerClient > 0 && reservedPerClient * clients <= rowsPerDestination)
  private val entries = destinations * rowsPerDestination
  private val rowBits = math.max(1, log2Ceil(entries))
  private val localRowBits = log2Ceil(rowsPerDestination)
  private val clientBits = math.max(1, log2Ceil(clients))
  private val destinationBits = math.max(1, log2Ceil(destinations))
  private val countBits = log2Ceil(rowsPerDestination + 1)
  private class Published extends Bundle {
    val row = UInt(rowBits.W)
    val result = new NativeRgba8TextureResult
  }
  val io = IO(new Bundle {
    val client = Vec(clients, Flipped(new NativeRgba8TextureClientIO))
    val target = Input(Vec(clients, UInt(destinationBits.W)))
    val child = Vec(destinations, new NativeRgba8TextureClientIO)
    val cancelOnAdmission = Output(Vec(destinations, Bool()))
    val allow = Input(Bool()); val cancel = Input(Bool()); val busy = Output(Bool())
    val offered = Output(Vec(clients, Bool()))
  })
  private val leased = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val sent = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val completed = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val cancelled = Reg(Vec(entries, Bool()))
  private val version = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val owner = Reg(Vec(entries, UInt(clientBits.W)))
  private val identities = Reg(Vec(entries, new GraphicsWorkIdentity))
  private val tags = Reg(Vec(entries, UInt(32.W)))
  // A cluster can only complete rows from its own fixed bank. Keep its write
  // decoding local instead of building cross-bank write ports for every reply.
  // Public ordered reads still select any destination without added latency.
  private val payload = Reg(Vec(destinations, Vec(rowsPerDestination, new NativeTextureWindowPayload)))
  private def rowPayload(row: UInt): NativeTextureWindowPayload =
    if (destinations == 1) payload(0)(row(localRowBits - 1, 0))
    else payload(row(rowBits - 1, localRowBits))(row(localRowBits - 1, 0))
  private val clientCount = RegInit(VecInit(Seq.fill(clients)(0.U(countBits.W))))
  private val pairCount = RegInit(VecInit(Seq.fill(destinations)(VecInit(Seq.fill(clients)(0.U(countBits.W))))))
  private val freeCount = RegInit(VecInit(Seq.fill(destinations)(rowsPerDestination.U(countBits.W))))
  private val offerLocked = RegInit(VecInit(Seq.fill(clients)(false.B)))
  private val offerTarget = Reg(Vec(clients, UInt(destinationBits.W)))
  private val order = Seq.fill(clients)(Module(new Queue(UInt(rowBits.W), rowsPerDestination)))
  private val output = Seq.fill(clients)(Module(new Queue(new Published, 1, pipe = true)))
  private val requests = Seq.fill(destinations)(Module(new Queue(new NativeTextureWindowTicket(clientBits), 2)))
  private val allocation = Seq.fill(destinations)(Module(new RRArbiter(new NativeTextureWindowTicket(clientBits), clients)))
  private val granted = Wire(Vec(destinations, Vec(clients, Bool())))
  private val selectedRow = Wire(Vec(destinations, UInt(rowBits.W)))
  private val retiredRow = Wire(Vec(clients, UInt(rowBits.W)))
  private val target = VecInit((0 until clients).map(c => Mux(offerLocked(c), offerTarget(c), io.target(c))))
  io.offered := offerLocked
  io.busy := leased.asUInt.orR || requests.map(_.io.deq.valid).reduce(_ || _)
  private def sum(xs: Seq[UInt]): UInt = if (xs.isEmpty) 0.U else if (xs.size == 1) xs.head else
    sum(xs.grouped(2).map(p => if (p.size == 2) p.head +& p(1) else p.head).toSeq)
  private def matches(r: Int): Bool = VecInit((0 until clients).map(c =>
    owner(r) === c.U && io.client(c).cancelGroup.valid &&
      io.client(c).cancelGroup.bits.identity.asUInt === identities(r).asUInt &&
      io.client(c).cancelGroup.bits.tag === tags(r))).asUInt.orR
  private def ticketCancelled(q: NativeTextureWindowTicket): Bool = VecInit((0 until clients).map(c =>
    q.client === c.U && io.client(c).cancelGroup.valid &&
      io.client(c).cancelGroup.bits.identity.asUInt === q.request.lookup.table.identity.asUInt &&
      io.client(c).cancelGroup.bits.tag === q.callerTag)).asUInt.orR

  for (r <- 0 until entries) {
    when(leased(r) && (io.cancel || matches(r))) { cancelled(r) := true.B }
    assert(!completed(r) || (leased(r) && sent(r)), "shared window completed row lost ownership")
  }
  for (c <- 0 until clients) {
    val client = io.client(c); val q = order(c); val out = output(c)
    val choices = VecInit((0 until destinations).map(n => granted(n)(c)))
    client.request.ready := choices.asUInt.orR
    // The arbiter's ready can assert before valid; only a fire allocates a row.
    q.io.enq.valid := client.request.fire
    q.io.enq.bits := Mux1H(choices, selectedRow)
    assert(PopCount(choices) <= 1.U)
    when(client.request.valid && !client.request.ready && !offerLocked(c)) {
      offerLocked(c) := true.B; offerTarget(c) := io.target(c)
    }
    when(offerLocked(c)) { assert(client.request.valid, "shared window held offer was withdrawn") }
    when(client.request.fire) { offerLocked(c) := false.B; assert(target(c) < destinations.U) }
    val head = q.io.deq.bits
    out.io.enq.valid := q.io.deq.valid && completed(head)
    out.io.enq.bits := 0.U.asTypeOf(out.io.enq.bits)
    out.io.enq.bits.row := head
    out.io.enq.bits.result.identity := identities(head); out.io.enq.bits.result.tag := tags(head)
    rowPayload(head).publish(out.io.enq.bits.result)
    q.io.deq.ready := out.io.enq.ready && completed(head)
    when(out.io.enq.fire) { assert(leased(head) && owner(head) === c.U && completed(head)) }
    client.response.valid := out.io.deq.valid; client.response.bits := out.io.deq.bits.result
    out.io.deq.ready := client.response.ready; retiredRow(c) := out.io.deq.bits.row
    when(client.response.fire) {
      val r = retiredRow(c)
      assert(leased(r) && sent(r) && completed(r) && owner(r) === c.U)
      leased(r) := false.B; sent(r) := false.B; completed(r) := false.B
    }
    when(client.request.fire =/= client.response.fire) {
      clientCount(c) := Mux(client.request.fire, clientCount(c) + 1.U, clientCount(c) - 1.U)
    }
    assert(clientCount(c) <= rowsPerDestination.U)
    assert(clientCount(c) === q.io.count +& out.io.count, "shared window order and public ownership diverged")
    assert(clientCount(c) === PopCount(VecInit((0 until entries).map(r => leased(r) && owner(r) === c.U))))
  }
  for (n <- 0 until destinations) {
    val arb = allocation(n); val q = requests(n); val child = io.child(n)
    val base = n * rowsPerDestination
    val available = VecInit((0 until rowsPerDestination).map(i => !leased(base + i))).asUInt
    selectedRow(n) := base.U + PriorityEncoder(available)
    for (c <- 0 until clients) {
      val reserveOthers = sum((0 until clients).filter(_ != c).map(k =>
        Mux(pairCount(n)(k) < reservedPerClient.U, reservedPerClient.U - pairCount(n)(k), 0.U)))
      val credit = freeCount(n) > reserveOthers && clientCount(c) < rowsPerDestination.U && order(c).io.enq.ready
      val cancelOffer = io.cancel && offerLocked(c)
      val eligible = target(c) === n.U && credit && ((io.allow && !io.cancel) || cancelOffer)
      arb.io.in(c).valid := io.client(c).request.valid && eligible
      arb.io.in(c).bits.request := io.client(c).request.bits
      arb.io.in(c).bits.request.tag := selectedRow(n)
      arb.io.in(c).bits.client := c.U; arb.io.in(c).bits.callerTag := io.client(c).request.bits.tag
      granted(n)(c) := arb.io.in(c).ready && eligible
      val accepting = arb.io.in(c).fire
      val retiring = io.client(c).response.fire && (retiredRow(c) / rowsPerDestination.U) === n.U
      when(accepting =/= retiring) {
        pairCount(n)(c) := Mux(accepting, pairCount(n)(c) + 1.U, pairCount(n)(c) - 1.U)
      }
    }
    arb.io.out.ready := io.cancel || q.io.enq.ready
    q.io.enq.valid := arb.io.out.valid && !io.cancel; q.io.enq.bits := arb.io.out.bits
    when(arb.io.out.fire) {
      val r = selectedRow(n); val t = arb.io.out.bits
      assert(available.orR && !leased(r) && r >= base.U && r < (base + rowsPerDestination).U)
      leased(r) := true.B; sent(r) := io.cancel; completed(r) := io.cancel; version(r) := !version(r)
      owner(r) := t.client; identities(r) := t.request.lookup.table.identity; tags(r) := t.callerTag
      cancelled(r) := io.cancel || ticketCancelled(t)
      val value = payload(n)(r(localRowBits - 1, 0))
      when(io.cancel) { value := 0.U.asTypeOf(value); value.status := TextureImageResourceStatus.cancelled.U }
    }
    val released = PopCount(VecInit((0 until clients).map(c =>
      io.client(c).response.fire && (retiredRow(c) / rowsPerDestination.U) === n.U)))
    when(arb.io.out.fire || released =/= 0.U) { freeCount(n) := freeCount(n) + released - arb.io.out.fire.asUInt }
    assert(freeCount(n) <= rowsPerDestination.U)
    assert(freeCount(n) +& sum(pairCount(n).toSeq) === rowsPerDestination.U)
    assert(PopCount(leased.slice(base, base + rowsPerDestination)) === sum(pairCount(n).toSeq))

    val queued = q.io.deq.bits.request.tag(rowBits - 1, 0)
    child.request.valid := q.io.deq.valid && !io.cancel; child.request.bits := q.io.deq.bits.request
    child.invalidate := false.B
    q.io.deq.ready := io.cancel || child.request.ready
    when(child.request.fire) { assert(leased(queued) && !sent(queued)); sent(queued) := true.B }
    when(q.io.deq.fire && io.cancel) {
      assert(leased(queued) && !sent(queued) && !completed(queued))
      assert(queued >= base.U && queued < (base + rowsPerDestination).U)
      sent(queued) := true.B; completed(queued) := true.B; cancelled(queued) := true.B
      val value = payload(n)(queued(localRowBits - 1, 0))
      value := 0.U.asTypeOf(value); value.status := TextureImageResourceStatus.cancelled.U
    }
    // Current queued caller state is carried beside its private row tag, so
    // immediate admission poison avoids a wide identity-table read/mux.
    io.cancelOnAdmission(n) := cancelled(queued) || ticketCancelled(q.io.deq.bits)
    val cancels = Module(new RRArbiter(UInt(rowBits.W), rowsPerDestination))
    for (i <- 0 until rowsPerDestination) {
      val r = base + i
      cancels.io.in(i).valid := leased(r) && sent(r) && !completed(r) && cancelled(r)
      cancels.io.in(i).bits := r.U
    }
    val cancelValid = RegNext(cancels.io.out.valid, false.B)
    val cancelRow = Reg(UInt(rowBits.W)); val cancelVersion = Reg(Bool())
    val cancelIdentity = Reg(new GraphicsWorkIdentity)
    when(cancels.io.out.valid) {
      cancelRow := cancels.io.out.bits; cancelVersion := version(cancels.io.out.bits)
      cancelIdentity := identities(cancels.io.out.bits)
    }
    cancels.io.out.ready := true.B
    child.cancelGroup.valid := cancelValid && leased(cancelRow) && sent(cancelRow) &&
      !completed(cancelRow) && version(cancelRow) === cancelVersion
    child.cancelGroup.bits.tag := cancelRow; child.cancelGroup.bits.identity := cancelIdentity
    when(child.cancelGroup.valid) { assert(identities(cancelRow).asUInt === cancelIdentity.asUInt) }
    child.response.ready := true.B
    val r = child.response.bits.tag(rowBits - 1, 0)
    when(child.response.fire) {
      assert(child.response.bits.tag < entries.U && r >= base.U && r < (base + rowsPerDestination).U)
      assert(leased(r) && sent(r) && !completed(r))
      assert(child.response.bits.identity.asUInt === identities(r).asUInt)
      val value = payload(n)(r(localRowBits - 1, 0))
      value.capture(child.response.bits); completed(r) := true.B
      val scoped = VecInit((0 until clients).map(c => owner(r) === c.U && io.client(c).cancelGroup.valid &&
        io.client(c).cancelGroup.bits.tag === tags(r) && io.client(c).cancelGroup.bits.identity.asUInt === identities(r).asUInt)).asUInt.orR
      when((io.cancel || cancelled(r) || scoped) && child.response.bits.status === 0.U) {
        value.status := TextureImageResourceStatus.cancelled.U
        value.rgba := 0.U.asTypeOf(value.rgba); value.fallback := false.B
      }
    }
  }
}
