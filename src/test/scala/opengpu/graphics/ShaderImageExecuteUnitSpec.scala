package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ShaderImageExecuteUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val config = GpuConfig(lanes = 4, warps = 2)
  private val format = TextureImageFormat.id("RGBA32UI")
  private val control = BigInt((format << 6) | (1 << 5) | (1 << 3))
  private val all = Vector.fill[BigInt](4)(0)
  private def vector(x: Int): Vector[BigInt] = Vector.tabulate(4)(i => BigInt(x + i))
  private def color(unit: Int, x: Int): Vector[BigInt] =
    Vector.tabulate(4)(c => BigInt(0x80000000L + unit * 1024 + x * 16 + c))
  private def address(unit: Int, x: Int): Int = 0x1000 + unit * 0x400 + 63 + x * 16
  private def opcode(function: Int, pair: Int = 0, rd: Int = 0): BigInt =
    (BigInt(function) << 26) | (BigInt(1) << 25) | (BigInt(pair) << 12) | (BigInt(rd) << 7) | 0x2b

  private class Driver(val d: ShaderImageExecuteUnit) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.enabled.poke(true.B)
    val memory = mutable.Map.empty[BigInt, Int]
    def byte(a: BigInt): Int = memory.getOrElse(a, 0x5a)
    def put(a: BigInt, word: BigInt, bytes: Int = 4): Unit =
      for(i <- 0 until bytes) memory(a + i) = ((word >> (8 * i)) & 255).toInt
    def get(a: BigInt, bytes: Int = 4): BigInt =
      (0 until bytes).map(i => BigInt(byte(a + i)) << (8 * i)).sum
    def descriptor(unit: Int, generation: Int = 13, readable: Boolean = true,
        writable: Boolean = true, base: Int = 0x8000): Unit = {
      val w = Array.fill[BigInt](64)(0)
      for((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> generation, 6 -> unit, 7 -> 1, 8 -> 1, 9 -> 4,
          10 -> (if(readable) 1 else 0), 11 -> (if(writable) 1 else 0),
          12 -> format, 13 -> format, 14 -> 1, 17 -> 15, 18 -> 1,
          22 -> (0x1000 + unit * 0x400), 23 -> (0x1400 + unit * 0x400),
          25 -> address(unit, 0), 26 -> 8, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 128, 37 -> 16)) w(i) = v
      w.zipWithIndex.foreach { case(v, i) => put(BigInt(base + unit * 256 + i * 4), v) }
    }
    for(unit <- 0 until 4) {
      descriptor(unit)
      for(x <- 0 until 8; c <- 0 until 4) put(BigInt(address(unit, x) + c * 4), color(unit, x)(c))
    }
    def table(generation: Int = 13, base: Int = 0x8000): Unit = {
      val t = d.io.table; zero(t)
      t.version.poke(1.U); t.base.poke(base.U); t.limit.poke((base + 1024).U)
      t.identity.context.poke(7.U); t.identity.pass.poke(11.U); t.identity.generation.poke(generation.U)
    }
    table()
    case class Reply(due: Int, id: Int, data: BigInt, fault: Boolean)
    case class Offer(id: Int, address: BigInt, write: Boolean, mask: BigInt)
    var pending: Option[Reply] = None
    var held: Option[BigInt] = None
    var requests = Vector.empty[Offer]
    var cycle = 0; var ready = true; var holdAck = false; var wrongId = false
    var failAt = Set.empty[(Int, BigInt)]
    var warp = 0; var rd = 0; var active = 15; var pc = 0x400
    def tick(): Unit = {
      val q = d.io.memoryRequest; val r = d.io.memoryResponse
      q.ready.poke(ready.B)
      val reply = pending.filter(p => p.due <= cycle && !holdAck)
      r.valid.poke(reply.nonEmpty.B)
      r.bits.transactionId.poke(reply.map(p => if(wrongId) 1 - p.id else p.id).getOrElse(0).U)
      r.bits.readData.poke(reply.map(_.data).getOrElse(BigInt(0)).U)
      r.bits.fault.poke(reply.exists(_.fault).B)
      held.foreach { old => q.valid.expect(true.B); assert(q.bits.peek().litValue == old) }
      held = if(q.valid.peek().litToBoolean && !ready) Some(q.bits.peek().litValue) else None
      if(reply.nonEmpty && r.ready.peek().litToBoolean) pending = None
      if(q.valid.peek().litToBoolean && ready) {
        assert(pending.isEmpty, "image operation reused an unacknowledged memory owner")
        val a = q.bits.address.peek().litValue; val id = q.bits.transactionId.peek().litValue.toInt
        val write = q.bits.isWrite.peek().litToBoolean; val mask = q.bits.byteMask.peek().litValue
        assert((a & 63) == 0 && (id == 0 || id == 1))
        q.bits.sizeLog2.expect(6.U); q.bits.cachePolicy.expect(2.U)
        if(id == 0) assert(!write && a >= 0x8000 && a < 0xa400)
        val fault = failAt((id, a)); val read = get(a, 64)
        if(write && !fault) {
          assert(id == 1)
          val data = q.bits.writeData.peek().litValue
          for(i <- 0 until 64 if mask.testBit(i)) memory(a + i) = ((data >> (8 * i)) & 255).toInt
        }
        pending = Some(Reply(cycle + 3, id, read, fault))
        requests :+= Offer(id, a, write, mask)
      }
      d.clock.step(); cycle += 1
    }
    def until(p: => Boolean): Unit = {
      var n = 0; while(!p && n < 2048) { tick(); n += 1 }; assert(p, s"image instruction stalled at $cycle")
    }
    def begin(function: Int, pair: Int = 0, dest: Int = 0, w: Int = 0,
        lhs: Seq[BigInt] = all, rhs: Seq[BigInt] = all, mask: Int = 15,
        effects: Int = 15, activeMask: Int = 15, old: Seq[BigInt] = vector(700)): Unit = {
      assert(pending.isEmpty && held.isEmpty); requests = Vector.empty
      warp = w; rd = dest; active = activeMask; pc += 4
      val q = d.io.in.bits; zero(q)
      q.issued.decode.instruction.poke(opcode(function, pair, dest).U)
      q.issued.decode.warpId.poke(w.U); q.issued.decode.pc.poke(pc.U)
      q.issued.decode.activeMask.poke(activeMask.U)
      q.executionMask.poke(mask.U); q.sideEffectMask.poke(effects.U)
      q.issued.vs1Data.zip(lhs).foreach { case(v, x) => v.poke(x.U) }
      q.issued.vs2Data.zip(rhs).foreach { case(v, x) => v.poke(x.U) }
      q.issued.oldVdData.zip(old).foreach { case(v, x) => v.poke(x.U) }
      d.io.in.ready.expect(true.B); d.io.in.valid.poke(true.B); tick()
      d.io.in.valid.poke(false.B); zero(q)
    }
    def finish(code: Int = 0, result: Option[Seq[BigInt]] = None, fault: Option[BigInt] = None): Unit = {
      until(d.io.commit.valid.peek().litToBoolean)
      assert(pending.isEmpty && held.isEmpty)
      d.io.status.expect(code.U); d.io.faultAddressValid.expect(fault.nonEmpty.B)
      d.io.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      val c = d.io.commit.bits
      c.writeback.warpId.expect(warp.U); c.writeback.vd.expect(rd.U)
      c.pc.expect(pc.U); c.warpActiveMask.expect(active.U)
      c.writesVd.expect((code == 0 && result.nonEmpty).B)
      c.writesScalar.expect((code == 0 && result.isEmpty).B)
      c.scalarData.expect(0.U)
      if(code == 0) result.foreach(xs => c.writeback.data.zip(xs).foreach { case(v, x) => v.expect(x.U) })
      val saved = c.peek().litValue
      for(_ <- 0 until 3) { tick(); d.io.busy.expect(true.B); d.io.in.ready.expect(false.B); assert(c.peek().litValue == saved) }
      d.io.commit.ready.poke(true.B); tick(); d.io.commit.ready.poke(false.B); d.io.busy.expect(false.B)
    }
    def parameters(w: Int = 0, units: Seq[BigInt] = all, mask: Int = 15,
        controls: Seq[BigInt] = Vector.fill(4)(control), stores: Boolean = false): Unit = {
      begin(8, w = w, lhs = units, rhs = controls, mask = mask); finish(); assert(requests.isEmpty)
      begin(8, pair = 1, w = w, mask = mask); finish(); assert(requests.isEmpty)
      if(stores) {
        begin(8, pair = 2, w = w, lhs = vector(100), rhs = vector(200), mask = mask); finish()
        begin(8, pair = 3, w = w, lhs = vector(300), rhs = vector(400), mask = mask); finish()
      }
    }
    def invalidate(): Unit = { d.io.invalidate.poke(true.B); tick(); d.io.invalidate.poke(false.B) }
    def clear(): Unit = { d.io.clearParameters.poke(true.B); tick(); d.io.clearParameters.poke(false.B) }
  }

  it should "capture four components once and retain independent masked lane and warp snapshots" in {
    simulate(new ShaderImageExecuteUnit(config)) { d =>
      val t = new Driver(d)
      t.parameters(units = vector(0)); t.parameters(w = 1, units = Vector.fill(4)(BigInt(3)))
      t.begin(9, dest = 5, lhs = vector(0), mask = 5)
      t.table(generation = 99, base = 0x9000) // live task input cannot alter the owned instruction
      t.finish(result = Some(Vector(color(0, 0)(0), 701, color(2, 2)(0), 703)))
      assert(t.requests.count(_.id == 0) == 8 && t.requests.count(_.id == 1) == 3)
      t.table()
      for(l <- Seq(0, 2); c <- 0 until 4) t.put(BigInt(address(l, l) + c * 4), 0)
      for(c <- 1 to 3) {
        t.begin(11, pair = c, dest = 6, mask = 5)
        t.finish(result = Some(Vector(color(0, 0)(c), 701, color(2, 2)(c), 703)))
        assert(t.requests.isEmpty)
      }
      t.begin(9, dest = 7, w = 1, lhs = vector(0), mask = 15, activeMask = 10)
      t.finish(result = Some(Vector(700, color(3, 1)(0), 702, color(3, 3)(0))))
      t.begin(11, pair = 2, dest = 8, mask = 5)
      t.finish(result = Some(Vector(color(0, 0)(2), 701, color(2, 2)(2), 703)))
      info("SHADER_IMAGE_EXECUTE snapshotRGBA=true maskedOldVd=true warpIsolation=true capturedTable=true componentReadsWithoutMemory=true")
    }
  }

  it should "suppress helper stores and retain every byte outside acknowledged texels" in {
    simulate(new ShaderImageExecuteUnit(config)) { d =>
      val t = new Driver(d); t.parameters(stores = true)
      val before = Vector.tabulate(1024)(i => t.byte(BigInt(0x1000 + i)))
      t.begin(10, lhs = vector(0), effects = 5); t.finish()
      assert(t.requests.count(_.write) == 3)
      for(i <- 0 until 1024) {
        val a = 0x1000 + i
        val lane = Seq(0, 2).find(l => a >= address(0, l) && a < address(0, l) + 16)
        val wanted = lane.map { l =>
          val off = a - address(0, l); val word = (off / 4 + 1) * 100 + l
          (word >> (8 * (off % 4))) & 255
        }.getOrElse(before(i))
        assert(t.byte(BigInt(a)) == wanted, s"unexpected store at $a")
      }
      t.clear()
      t.begin(10, lhs = Vector.fill(4)(BigInt("ffffffff", 16)), effects = 0)
      t.finish(); assert(t.requests.isEmpty)
      info("SHADER_IMAGE_EXECUTE helperStoreSuppression=true fullTexelStores=true splitStoreACK=true exactGuardBytes=true zeroEffectNoMemory=true")
    }
  }

  it should "reject incomplete tuples reserved controls disabled tasks and stale results before memory" in {
    simulate(new ShaderImageExecuteUnit(config)) { d =>
      val t = new Driver(d)
      t.begin(9, dest = 1); t.finish(code = 6); assert(t.requests.isEmpty)
      t.parameters(mask = 7, stores = true)
      t.begin(10, lhs = vector(0)); t.finish(code = 6); assert(t.requests.isEmpty)
      t.parameters(controls = Vector.fill(4)(control | (BigInt(1) << 31)), stores = true)
      t.begin(10, lhs = vector(0)); t.finish(code = 6); assert(t.requests.isEmpty)
      t.parameters()
      t.begin(9, dest = 1, mask = 1); t.finish(result = Some(Vector(color(0, 0)(0), 701, 702, 703)))
      t.begin(11, pair = 1, dest = 2, mask = 2); t.finish(code = 6); assert(t.requests.isEmpty)
      t.clear(); t.begin(11, pair = 1, dest = 2, mask = 1); t.finish(code = 6); assert(t.requests.isEmpty)
      t.parameters(); d.io.enabled.poke(false.B)
      t.begin(9, dest = 3); t.finish(code = 3); assert(t.requests.isEmpty)
      d.io.enabled.poke(true.B)
      t.begin(9, dest = 3, lhs = Vector.fill(4)(BigInt("ffffffff", 16)))
      t.finish(result = Some(all)); assert(!t.requests.exists(_.id == 1))
      info("SHADER_IMAGE_EXECUTE tuplePreflight=true reservedControls=true disabledTask=true staleSnapshotRejected=true signedCoordinatesPreserved=true")
    }
  }

  it should "drain descriptor and store offers and preserve primary faults before instruction retirement" in {
    simulate(new ShaderImageExecuteUnit(config)) { d =>
      val t = new Driver(d); t.parameters(stores = true)
      for(owner <- 0 to 1; fault <- Seq(false, true)) {
        t.invalidate(); t.begin(10, lhs = vector(0), mask = 1)
        t.until(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.transactionId.peek().litValue == owner)
        t.ready = false; t.holdAck = true; t.tick(); d.io.cancel.poke(true.B)
        for(_ <- 0 until 3) t.tick()
        val a = if(owner == 0) BigInt(0x8000) else BigInt(0x1000)
        t.failAt = if(fault) Set((owner, a)) else Set.empty
        t.ready = true; t.tick()
        for(_ <- 0 until 4) { t.tick(); d.io.commit.valid.expect(false.B) }
        t.holdAck = false; t.wrongId = true
        for(_ <- 0 until 4) { t.tick(); d.io.memoryResponse.ready.expect(false.B); d.io.commit.valid.expect(false.B) }
        t.wrongId = false
        t.finish(code = if(fault) 7 else 5, fault = if(fault) Some(if(owner == 0) a else BigInt(address(0, 0))) else None)
        assert(t.requests.count(_.id == owner) == 1)
        d.io.cancel.poke(false.B); t.failAt = Set.empty
      }
      t.begin(10, lhs = vector(0), mask = 1); t.finish()
      t.invalidate(); t.failAt = Set((1, BigInt(0x1080)))
      t.begin(9, dest = 4, lhs = Vector[BigInt](1, 5, 0, 0), mask = 3)
      t.finish(code = 7, fault = Some(BigInt(address(0, 5))))
      assert(t.requests.filter(_.id == 1).map(_.address) == Vector[BigInt](0x1040, 0x1080))
      t.begin(11, pair = 1, dest = 5, mask = 1); t.finish(code = 6)
      assert(t.requests.isEmpty, "a later lane fault published an earlier lane's private snapshot")
      t.failAt = Set.empty
      t.begin(9, dest = 4, lhs = Vector.fill(4)(BigInt(1)), mask = 1)
      t.finish(result = Some(Vector[BigInt](color(0, 1)(0), 701, 702, 703)))
      info("SHADER_IMAGE_EXECUTE heldOffers=true wrongReplyIDs=true delayedACK=true primaryFault=true lateLaneFaultNoSnapshot=true preciseRetirement=true healthyReuse=true")
    }
  }

  it should "cancel each instruction phase without publishing partial load snapshots" in {
    simulate(new ShaderImageExecuteUnit(config)) { d =>
      val t = new Driver(d); t.parameters()
      for(delay <- 0 until 120) {
        t.invalidate(); t.begin(9, dest = 4, lhs = vector(0), mask = 3)
        for(_ <- 0 until delay if !d.io.commit.valid.peek().litToBoolean) t.tick()
        val complete = d.io.commit.valid.peek().litToBoolean
        d.io.cancel.poke(true.B)
        t.finish(code = if(complete) 0 else 5,
          result = Some(Vector(color(0, 0)(0), color(0, 1)(0), 702, 703)))
        d.io.cancel.poke(false.B)
        t.begin(11, pair = 1, dest = 5, mask = 3)
        t.finish(code = if(complete) 0 else 6,
          result = Some(Vector(color(0, 0)(1), color(0, 1)(1), 702, 703)))
        assert(t.requests.isEmpty)
      }
      t.begin(9, dest = 4, lhs = vector(0), mask = 1)
      t.finish(result = Some(Vector(color(0, 0)(0), 701, 702, 703)))
      info("SHADER_IMAGE_EXECUTE cancellationCuts=120 noPartialSnapshot=true heldCommitImmutable=true healthyReuse=true")
    }
  }
}
