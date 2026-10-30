package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.TextureImageFormat
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitImageSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private val config = GpuConfig(lanes = 4, warps = 2)
  private val format = TextureImageFormat.id("RGBA32UI")
  private val cease = BigInt("30500073", 16)
  private def image(fn: Int, pair: Int = 0, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(pair) << 12) | (BigInt(rd) << 7) | 0x2b
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57
  private val control = (format << 6) | 40
  private val setup = Vector(
    vector(23, 3, 2, 0), // vmv.v.i v2,0
    BigInt(((control + 0x800) & ~0xfff) | (10 << 7) | 0x37),
    BigInt(((control & 0xfff).toLong << 20) | (10 << 15) | (10 << 7) | 0x13),
    vector(23, 4, 3, 10), // vmv.v.x v3,x10
    image(8, vs1 = 2, vs2 = 3), image(8, pair = 1, vs1 = 2, vs2 = 2)) ++
    (0 until 4).map(c => vector(0, 3, 4 + c, c + 1, 1)) ++ Vector(
      image(8, pair = 2, vs1 = 4, vs2 = 5), image(8, pair = 3, vs1 = 6, vs2 = 7))
  private val load = image(9, rd = 10, vs1 = 1, vs2 = 2)
  private val tail = Vector(load, image(11, pair = 1, rd = 11), image(11, pair = 2, rd = 12), image(11, pair = 3, rd = 13), cease)
  private val normal = Vector(BigInt("0104f057", 16)) ++ setup ++ Vector(image(10, vs1 = 1, vs2 = 2)) ++ tail
  private def bneGroup(offset: Int): BigInt =
    BigInt(((offset >> 12) & 1) << 31) | BigInt(((offset >> 5) & 63) << 25) |
      BigInt(2 << 15) | BigInt(1 << 12) | BigInt(((offset >> 1) & 15) << 8) |
      BigInt(((offset >> 11) & 1) << 7) | 0x63
  private val skipSecondGroup = Vector(normal.head, bneGroup((setup.size + 2) * 4)) ++ setup ++ Vector(image(10, vs1 = 1, vs2 = 2)) ++ tail

  private class Driver(val d: GpuComputeUnit, val translated: Boolean) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.l1InvalidateDone.ready.poke(true.B)
    val memory = mutable.Map.empty[BigInt, Int]
    val descriptorVirtual = if(translated) 0x00804000 else 0x8000
    val texelVirtual = if(translated) 0x0080603f else 0x903f
    val descriptorPhysical = if(translated) 0xa000 else 0x8000
    var texelPage = if(translated) 0xb000 else 0x9000
    def put(a: BigInt, value: BigInt): Unit =
      for(i <- 0 until 4) memory(a + i) = ((value >> (8 * i)) & 255).toInt
    def get(a: BigInt, bytes: Int = 4): BigInt =
      (0 until bytes).map(i => BigInt(memory.getOrElse(a + i, 0xa5)) << (8 * i)).sum
    def fixture(writable: Boolean = true): Unit = {
      val w = Array.fill[BigInt](64)(0)
      for((i, value) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> 13, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
          12 -> format, 13 -> format, 14 -> 1, 17 -> 15, 18 -> 1,
          22 -> (texelVirtual & ~0xfff), 23 -> ((texelVirtual & ~0xfff) + 4096),
          25 -> texelVirtual, 26 -> 8, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 128, 37 -> 16)) w(i) = value
      w.zipWithIndex.foreach { case(v, i) => put(BigInt(descriptorPhysical + i * 4), v) }
      for(i <- 0 until 4096) memory(BigInt(texelPage + i)) = 0xa5
      for(item <- 0 until 8; c <- 0 until 4) put(BigInt(texelPage + 63 + item * 16 + c * 4), 1000 + item * 4 + c)
      if(translated) {
        put(0x30008, (BigInt(0x31) << 10) | 1)
        put(0x31010, (BigInt(descriptorPhysical >> 12) << 10) | 0x243)
        put(0x31018, (BigInt(texelPage >> 12) << 10) | (if(writable) 0x2c7 else 0x243))
      }
    }
    case class Reply(id: Int, address: BigInt, data: BigInt, mask: BigInt, write: Boolean, fault: Boolean, due: Int)
    val pending = mutable.Queue.empty[Reply]
    var cycle = 0; var generation = 0; var pc = 0
    var requests = Vector.empty[(Int, BigInt, Boolean)]
    var writes = Vector.empty[(Int, Int, Vector[BigInt])]
    var traps = Vector.empty[(BigInt, BigInt, BigInt)]
    var faultAt = Set.empty[BigInt]
    var held: Option[BigInt] = None
    var holdStores = false; var holdAcks = false
    var permission = false
    def tick(): Unit = {
      val q = d.io.memoryRequest; val r = d.io.memoryResponse
      q.ready.poke((cycle % 5 != 0 && !(holdStores && q.bits.isWrite.peek().litToBoolean)).B)
      val reply = pending.headOption.filter(p => p.due <= cycle && !(holdAcks && p.write))
      r.valid.poke(reply.nonEmpty.B)
      r.bits.transactionId.poke(reply.map(_.id).getOrElse(0).U)
      r.bits.readData.poke(reply.map(_.data).getOrElse(BigInt(0)).U)
      r.bits.fault.poke(reply.exists(_.fault).B)
      if(reply.nonEmpty && r.ready.peek().litToBoolean) {
        val p = pending.dequeue()
        if(p.write && !p.fault) for(i <- 0 until 64 if p.mask.testBit(i))
          memory(p.address + i) = ((p.data >> (8 * i)) & 255).toInt
      }
      held.foreach { old => q.valid.expect(true.B); assert(q.bits.peek().litValue == old) }
      held = if(q.valid.peek().litToBoolean && !q.ready.peek().litToBoolean) Some(q.bits.peek().litValue) else None
      if(q.valid.peek().litToBoolean && q.ready.peek().litToBoolean) {
        val id = q.bits.transactionId.peek().litValue.toInt; val a = q.bits.address.peek().litValue
        val write = q.bits.isWrite.peek().litToBoolean
        assert(!pending.exists(_.id == id), s"live memory ID $id reused")
        if(id >= 4) {
          assert(permission, "disabled task reached image memory")
          q.bits.cachePolicy.expect(2.U); q.bits.cacheClient.expect(false.B)
          if(id == 6) { assert(translated && !write); q.bits.sizeLog2.expect(2.U) }
          else { q.bits.sizeLog2.expect(6.U); assert(a >= descriptorPhysical && a < descriptorPhysical + 256 || a >= texelPage && a < texelPage + 4096) }
        } else assert(!write && a >= pc && a < pc + 256, s"unexpected ordinary CU access $a")
        val data = if(write) q.bits.writeData.peek().litValue else get(a, if(id == 6) 4 else 64)
        pending.enqueue(Reply(id, a, data, q.bits.byteMask.peek().litValue, write, faultAt(a), cycle + (if(write) 19 else 3)))
        requests :+= ((id, a, write))
      }
      d.io.vectorTexSample.valid.expect(false.B); d.io.texSample.valid.expect(false.B)
      if(d.io.committedVectorWriteback.valid.peek().litToBoolean) {
        val c = d.io.committedVectorWriteback.bits; val vd = c.vd.peek().litValue.toInt
        if(vd >= 10 && vd <= 13) writes :+= ((c.warpId.peek().litValue.toInt, vd, c.data.map(_.peek().litValue).toVector))
      }
      if(d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean)
        traps :+= ((d.io.trap.bits.pc.peek().litValue, d.io.trap.bits.cause.peek().litValue, d.io.trap.bits.tval.peek().litValue))
      d.clock.step(); cycle += 1
    }
    def until(label: String)(p: => Boolean): Unit = {
      var n = 0; while(!p && n < 20000) { tick(); n += 1 }
      assert(p, s"$label stalled at $cycle, active=${d.io.active.peek().litValue}, pending=${pending.size}")
    }
    def launch(program: Seq[BigInt] = normal, allowed: Boolean = true, helpers: Int = 0,
        threads: Int = 8, groups: Int = 1): Unit = {
      assert(pending.isEmpty && held.isEmpty)
      requests = Vector.empty; writes = Vector.empty; traps = Vector.empty
      generation += 1; pc = 0x1000 + generation * 0x200
      program.zipWithIndex.foreach { case(v, i) => put(BigInt(pc + i * 4), v) }
      d.io.images.get.enabled.poke(allowed.B); permission = allowed
      val table = d.io.images.get.table; zero(table)
      table.version.poke(1.U); table.base.poke(descriptorVirtual.U); table.limit.poke((descriptorVirtual + 1024).U)
      table.identity.context.poke(7.U); table.identity.pass.poke(11.U); table.identity.generation.poke(13.U)
      d.io.vectorSatp.poke((if(translated) BigInt("80000030", 16) else BigInt(0)).U)
      zero(d.io.kernel.bits); d.io.kernel.bits.kernelPc.poke(pc.U)
      d.io.kernel.bits.invocations.helpers.poke(helpers.U)
      for(i <- 0 until 3) {
        d.io.kernel.bits.gridSize(i).poke((if(i == 0) groups else 1).U)
        d.io.kernel.bits.localSize(i).poke((if(i == 0) threads else 1).U)
      }
      d.io.kernel.valid.poke(true.B); until("kernel admission")(d.io.kernel.ready.peek().litToBoolean)
      tick(); d.io.kernel.valid.poke(false.B)
      // The admitted task must own the table, permission and image VM root.
      zero(d.io.images.get); d.io.images.get.enabled.poke((!allowed).B)
      d.io.vectorSatp.poke("h80000040".U)
      d.io.kernel.bits.invocations.helpers.poke(0.U)
      d.io.imageLease.get.expect(allowed.B)
    }
    def finish(success: Boolean, fault: Option[(BigInt, BigInt, BigInt)] = None): Unit = {
      if(!success) {
        until("attributed image fault")(d.io.trap.valid.peek().litToBoolean)
        fault.foreach { case(at, cause, value) =>
          d.io.trap.bits.pc.expect(at.U); d.io.trap.bits.cause.expect(cause.U); d.io.trap.bits.tval.expect(value.U)
        }
        val saved = d.io.trap.bits.peek().litValue
        for(_ <- 0 until 7) { tick(); assert(d.io.trap.bits.peek().litValue == saved); d.io.completion.valid.expect(false.B); d.io.imageLease.get.expect(permission.B) }
        d.io.trap.ready.poke(true.B)
      }
      until("kernel completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.success.expect(success.B); d.io.active.expect(0.U)
      assert(pending.isEmpty && held.isEmpty)
      val saved = d.io.completion.bits.peek().litValue
      for(_ <- 0 until 5) { tick(); assert(d.io.completion.bits.peek().litValue == saved); d.io.imageLease.get.expect(permission.B) }
      d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
      d.io.trap.ready.poke(false.B); d.io.imageLease.get.expect(false.B)
      assert(traps.isEmpty == success)
    }
  }

  for(translated <- Seq(false, true)) {
    it should s"execute typed image instructions with task capture helper suppression and recovery Sv32=$translated" in {
      simulate(new GpuComputeUnit(config, finishOnTrap = true, textureAvailable = false,
        nativeResourcesAvailable = false, imageAvailable = true, nativeExecutionProfile = true,
        instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1)) { d =>
        val t = new Driver(d, translated); t.fixture()
        val before = Vector.tabulate(4096)(i => t.memory(BigInt(t.texelPage + i)))
        t.launch(helpers = 0xaa); t.finish(success = true)
        assert(t.writes.size == 8 && t.writes.map(_._1).toSet == Set(0, 1))
        for((warp, vd, words) <- t.writes; lane <- 0 until 4) {
          val item = warp * 4 + lane; val c = vd - 10
          val expected = if(item % 2 == 0) item + c + 1 else 1000 + item * 4 + c
          assert(words(lane) == expected, s"image result mismatch warp=$warp vd=$vd lane=$lane")
        }
        for(i <- 0 until 4096) {
          val off = i - 63; val touched = off >= 0 && off < 128 && (off / 16) % 2 == 0
          val expected = if(touched) (((off / 16 + (off % 16) / 4 + 1) >> (8 * (off % 4))) & 255) else before(i)
          assert(t.memory(BigInt(t.texelPage + i)) == expected)
        }
        assert(t.requests.exists(_._1 == 6) == translated)

        t.launch(allowed = false, threads = 4)
        t.finish(success = false, fault = Some((BigInt(t.pc + 5 * 4), BigInt(2), setup(4))))
        assert(!t.requests.exists(_._1 >= 4) && t.writes.isEmpty)

        // Same identity and hardware warp slot, but no tuple in the new task.
        t.launch(program = Vector(normal.head, load, cease), threads = 4)
        t.finish(success = false, fault = Some((BigInt(t.pc + 4), BigInt(5), BigInt(0))))
        assert(!t.requests.exists(_._1 >= 4) && t.writes.isEmpty)

        // A failing load to v0 must release its real destination reservation;
        // unlike parameter/store acknowledgements, v0 is not a scalar x0 ACK.
        t.launch(program = Vector(normal.head, image(9, rd = 0, vs1 = 1, vs2 = 2), cease), threads = 4)
        t.finish(success = false, fault = Some((BigInt(t.pc + 4), BigInt(5), BigInt(0))))
        assert(!t.requests.exists(_._1 >= 4) && t.writes.isEmpty)

        t.fixture(); t.faultAt = Set(BigInt(t.texelPage))
        t.launch(threads = 4)
        val storeIndex = 1 + setup.size
        t.finish(success = false, fault = Some((BigInt(t.pc + storeIndex * 4), BigInt(7), BigInt(t.texelVirtual))))
        assert(t.writes.isEmpty && t.requests.count(_._3) == 1)
        t.faultAt = Set.empty

        if(translated) {
          t.fixture(writable = false); t.launch(threads = 4)
          t.finish(success = false, fault = Some((BigInt(t.pc + storeIndex * 4), BigInt(7), BigInt(t.texelVirtual))))
          assert(t.writes.isEmpty && !t.requests.exists(_._3), "denied image page reached physical storage")
        }
        t.fixture(); t.holdStores = true; t.launch(threads = 4)
        t.until("held image store")(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.isWrite.peek().litToBoolean)
        t.holdAcks = true; d.io.imageCancel.get.poke(true.B)
        for(_ <- 0 until 5) { t.tick(); d.io.completion.valid.expect(false.B) }
        t.holdStores = false
        t.until("irrevocable store accepted")(t.requests.exists(_._3))
        for(_ <- 0 until 5) { t.tick(); d.io.completion.valid.expect(false.B); d.io.trap.valid.expect(false.B) }
        t.holdAcks = false
        t.finish(success = false, fault = Some((BigInt(t.pc + storeIndex * 4), BigInt(7), BigInt(0))))
        assert(t.writes.isEmpty && t.requests.count(_._3) == 1)
        d.io.imageCancel.get.poke(false.B)

        // Rebinding with unchanged public identity must flush private state.
        if(translated) t.texelPage = 0xc000
        t.fixture(); t.launch(threads = 4); t.finish(success = true)
        assert(t.writes.size == 4)
        if(translated) assert(t.requests.filter(_._3).forall(_._2 >= 0xc000))
        info(s"CU_IMAGE_EXECUTION Sv32=$translated actualInstructions=true warps=2 helperStoresSuppressed=true capturedTaskAndRoot=true sameIdentityReuse=true delayedStoreACK=true exactImagesAndGuards=true preciseFaults=true cancelDrain=true healthyReuse=true")
      }
    }
  }

  it should "clear image tuples when a later workgroup reuses a hardware warp slot" in {
    simulate(new GpuComputeUnit(config, finishOnTrap = true, textureAvailable = false,
      imageAvailable = true, nativeExecutionProfile = true,
      instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1)) { d =>
      val t = new Driver(d, false); t.fixture()
      t.launch(program = skipSecondGroup, threads = 4, groups = 2)
      val loadIndex = 2 + setup.size + 1
      t.finish(success = false, fault = Some((BigInt(t.pc + loadIndex * 4), BigInt(5), BigInt(0))))
      assert(t.writes.size == 4 && t.writes.map(_._1).toSet == Set(0))
      assert(t.traps.size == 1)
      t.fixture(); t.launch(threads = 4); t.finish(success = true)
      info("CU_IMAGE_EXECUTION workgroups=2 warpSlotReuse=true freshTupleRequired=true previousGroupSnapshotNotVisible=true healthyReuse=true")
    }
  }
}
