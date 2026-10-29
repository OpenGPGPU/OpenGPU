package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ShaderImageMemoryAccessSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero, identity}
  import TypedColorReference.{fp, value}
  private val formats = Vector("RGBA32F", "RGBA16F", "R32F", "RGBA32UI", "RGBA16UI",
    "RGBA8UI", "R32UI", "RGBA32I", "RGBA16I", "RGBA8I", "R32I", "RGBA8", "RGBA8_SNORM")
  private def packed(name: String, values: Seq[BigInt]): BigInt = {
    if (name != "RGBA8_SNORM") TypedColorReference.encode(name, values)._1
    else values.zipWithIndex.foldLeft(BigInt(0)) { case (out, (raw, c)) =>
      val x = value(raw)
      val bounded = if (x.isNaN) 0.0 else math.max(-1.0, math.min(1.0, x))
      out | (BigInt(math.rint(bounded * 127.0).toLong & 255) << (c * 8))
    }
  }
  private class Driver(val d: ShaderImageMemoryAccess) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val memory = mutable.Map.empty[BigInt, Int]
    def byte(a: BigInt): Int = memory.getOrElse(a, (a.toInt * 17 + 9) & 255)
    def install(a: BigInt, value: BigInt, size: Int): Unit =
      for (i <- 0 until size) memory(a + i) = ((value >> (i * 8)) & 255).toInt
    case class Pending(due: Int, data: BigInt, fault: Boolean)
    var pending: Option[Pending] = None
    var cycle = 0; var requests = 0; var operations = 0
    var ready = true; var holdAck = false; var responseId = 0
    var faultIndices = Set.empty[Int]
    var expectedLines = Vector.empty[BigInt]
    var requestTag = BigInt(0); var requestIdentity = BigInt(0)
    var requestWrite = false
    var texelAddress = BigInt(0); var texelBytes = 0
    var heldOffer: Option[BigInt] = None
    def tick(): Unit = {
      val req = d.io.memoryRequest; val rsp = d.io.memoryResponse
      req.ready.poke(ready.B)
      val reply = pending.filter(p => p.due <= cycle && !holdAck)
      rsp.valid.poke(reply.nonEmpty.B); rsp.bits.transactionId.poke(responseId.U)
      rsp.bits.fault.poke(reply.exists(_.fault).B); rsp.bits.readData.poke(reply.map(_.data).getOrElse(BigInt(0)).U)
      heldOffer.foreach { bits => req.valid.expect(true.B); assert(req.bits.peek().litValue == bits) }
      heldOffer = if (req.valid.peek().litToBoolean && !ready) Some(req.bits.peek().litValue) else None
      if (reply.nonEmpty && rsp.ready.peek().litToBoolean) pending = None
      if (req.valid.peek().litToBoolean && ready) {
        assert(pending.isEmpty, "image unit reused an unacknowledged transaction")
        assert(requests < expectedLines.size, "unexpected image memory request")
        val a = req.bits.address.peek().litValue
        assert(a == expectedLines(requests)); req.bits.sizeLog2.expect(6.U)
        req.bits.transactionId.expect(0.U); req.bits.cachePolicy.expect(2.U)
        req.bits.cacheClient.expect(false.B); req.bits.cacheResident.expect(false.B)
        req.bits.isWrite.expect(requestWrite.B)
        val data = (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(byte(a + i)) << (8 * i)))
        if (req.bits.isWrite.peek().litToBoolean) {
          val mask = req.bits.byteMask.peek().litValue; val write = req.bits.writeData.peek().litValue
          val expectedMask = (0 until 64).filter(i => a + i >= texelAddress && a + i < texelAddress + texelBytes)
            .foldLeft(BigInt(0))((m, i) => m.setBit(i))
          assert(mask == expectedMask, "store byte enables differ from the resolved texel interval")
          for (i <- 0 until 64 if mask.testBit(i)) memory(a + i) = ((write >> (8 * i)) & 255).toInt
        } else req.bits.byteMask.expect(((BigInt(1) << 64) - 1).U)
        pending = Some(Pending(cycle + 3, data, faultIndices(requests))); requests += 1
      }
      d.clock.step(); cycle += 1
    }
    def until(condition: => Boolean): Unit = {
      var steps = 0
      while (!condition && steps < 256) { tick(); steps += 1 }
      assert(condition, s"image operation stalled at cycle=$cycle")
    }
    def begin(name: String, address: BigInt, write: Boolean, values: Seq[BigInt] = Vector.fill(4)(BigInt(0)),
        valid: Boolean = true, base: BigInt = 0x1000, limit: BigInt = 0x1080,
        kind: Option[Int] = None, lines: Option[Vector[BigInt]] = None): Unit = {
      assert(pending.isEmpty && heldOffer.isEmpty)
      val f = ImageFormatReference.format(name)
      requestWrite = write; texelAddress = address; texelBytes = f.bytes
      requests = 0; faultIndices = Set.empty; operations += 1
      val first = address & ~BigInt(63)
      expectedLines = lines.getOrElse(if ((address & 63) + f.bytes > 64) Vector(first, first + 64) else Vector(first))
      requestTag = BigInt("f0000000", 16) + operations
      d.io.in.ready.expect(true.B); zero(d.io.in.bits)
      identity(d.io.in.bits.identity); d.io.in.bits.identity.generation.poke(operations.U)
      requestIdentity = d.io.in.bits.identity.peek().litValue
      d.io.in.bits.tag.poke(requestTag.U); d.io.in.bits.validTexel.poke(valid.B)
      d.io.in.bits.address.poke(address.U); d.io.in.bits.allocationBase.poke(base.U)
      d.io.in.bits.allocationLimit.poke(limit.U); d.io.in.bits.isWrite.poke(write.B)
      d.io.in.bits.format.poke(TextureImageFormat.id(name).U); d.io.in.bits.colorType.poke(kind.getOrElse(f.kind).U)
      d.io.in.bits.rgba.zip(values).foreach { case (v, x) => v.poke(x.U) }
      d.io.in.valid.poke(true.B); tick(); d.io.in.valid.poke(false.B)
      zero(d.io.in.bits)
    }
    def finish(status: Int = 0, values: Seq[BigInt] = Vector.fill(4)(BigInt(0)), fault: Option[BigInt] = None): Unit = {
      until(d.io.out.valid.peek().litToBoolean)
      assert(pending.isEmpty && requests == expectedLines.size)
      d.io.out.bits.tag.expect(requestTag.U)
      d.io.out.bits.isWrite.expect(requestWrite.B)
      assert(d.io.out.bits.identity.peek().litValue == requestIdentity)
      d.io.out.bits.status.expect(status.U); d.io.out.bits.faultAddressValid.expect(fault.nonEmpty.B)
      d.io.out.bits.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      d.io.out.bits.rgba.zip(values).foreach { case (v, x) => v.expect(x.U) }
      val held = d.io.out.bits.peek().litValue; val oldCancel = d.io.cancel.peek().litToBoolean
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 4) {
        tick(); d.io.out.valid.expect(true.B); d.io.in.ready.expect(false.B); d.io.busy.expect(true.B)
        assert(d.io.out.bits.peek().litValue == held)
      }
      d.io.cancel.poke(oldCancel.B)
      d.io.out.ready.poke(true.B); tick(); d.io.out.ready.poke(false.B)
      d.io.busy.expect(false.B)
    }
  }

  it should "load and store every image format across cache lines without changing guard bytes" in {
    simulate(new ShaderImageMemoryAccess) { d =>
      val t = new Driver(d)
      for (name <- formats; offset <- Seq(0, 63)) {
        val f = ImageFormatReference.format(name); val address = BigInt(0x1000 + offset)
        val values = if (f.kind == 0) Vector(-2.0, 0.5, 1.25, 65504.0).map(fp)
          else Vector[BigInt](0xffffffffL, 0x80000000L, 0x12345678L, 0x10001)
        val expected = packed(name, values)
        val before = Vector.tabulate(128)(i => t.byte(BigInt(0x1000 + i)))
        t.begin(name, address, write = true, values = values); t.finish()
        for (i <- 0 until 128) {
          val wanted = if (i >= offset && i < offset + f.bytes) ((expected >> (8 * (i - offset))) & 255).toInt else before(i)
          assert(t.byte(BigInt(0x1000 + i)) == wanted, s"masked image store changed byte$i format=$name")
        }
        t.begin(name, address, write = false); t.finish(values = ImageFormatReference.decode(name, expected))
      }
      for (group <- 0 until 64) {
        val raw = (0 until 4).foldLeft(BigInt(0))((v, c) => v | (BigInt(group * 4 + c) << (8 * c)))
        val address = BigInt(0x103f); t.install(address, raw, 4)
        t.begin("RGBA8_SNORM", address, write = false)
        t.finish(values = ImageFormatReference.decode("RGBA8_SNORM", raw))
      }
      val last = BigInt("fffffffc", 16); t.install(last, BigInt("89abcdef", 16), 4)
      t.begin("R32UI", last, write = false, base = BigInt("ffffffc0", 16), limit = BigInt(1) << 32)
      t.finish(values = Vector[BigInt](0x89abcdefL, 0, 0, 1))
      assert(t.operations == 117)
      info("SHADER_IMAGE_MEMORY formats=13 operations=117 allSNORMCodes=256 crossLineMasks=true guardBytes=true fullAddressBoundary=true exactTypedLoads=true")
    }
  }

  it should "reject malformed backing and shader types before memory and make invalid texels zero no-ops" in {
    simulate(new ShaderImageMemoryAccess) { d =>
      val t = new Driver(d); val none = Some(Vector.empty[BigInt])
      for (write <- Seq(false, true)) {
        t.begin("RGBA32UI", 0x103f, write, valid = false, base = 1, limit = 0, kind = Some(3), lines = none); t.finish()
        t.begin("R8", 0x1000, write, lines = none); t.finish(status = 1)
        t.begin("R32UI", 0x1000, write, kind = Some(0), lines = none); t.finish(status = 5)
        t.begin("RGBA32UI", 0x103f, write, limit = 0x1040, lines = none); t.finish(status = 2)
        t.begin("R32UI", 0x1000, write, base = 0x1001, lines = none); t.finish(status = 2)
        t.begin("R32UI", BigInt("fffffffd", 16), write,
          base = BigInt("ffffffc0", 16), limit = BigInt(1) << 32, lines = none); t.finish(status = 2)
      }
      assert(t.operations == 12)
      info("SHADER_IMAGE_MEMORY rejectedOrNoOp=12 memoryRequests=0 checkedBeforeFirstLine=true noAddressWrap=true")
    }
  }

  it should "retain offered writes and acknowledgements through cancel while preserving primary memory faults" in {
    simulate(new ShaderImageMemoryAccess) { d =>
      val t = new Driver(d); val values = Vector.fill(4)(BigInt("12345678", 16))
      for (fault <- Seq(false, true)) {
        t.ready = false; t.holdAck = true
        t.begin("RGBA32UI", 0x103f, write = true, values = values, lines = Some(Vector(BigInt(0x1000))))
        t.faultIndices = if (fault) Set(0) else Set.empty[Int]
        t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        t.tick(); d.io.cancel.poke(true.B)
        for (_ <- 0 until 4) t.tick()
        assert(t.requests == 0); d.io.out.valid.expect(false.B)
        t.ready = true; t.tick(); assert(t.requests == 1)
        for (_ <- 0 until 5) { t.tick(); d.io.out.valid.expect(false.B); d.io.busy.expect(true.B) }
        t.holdAck = false; t.responseId = 1
        for (_ <- 0 until 3) { t.tick(); d.io.memoryResponse.ready.expect(false.B); d.io.out.valid.expect(false.B) }
        t.responseId = 0
        t.finish(status = if (fault) 3 else 4, fault = if (fault) Some(BigInt(0x103f)) else None)
        d.io.cancel.poke(false.B)
      }
      t.begin("RGBA32UI", 0x103f, write = false); t.faultIndices = Set(1)
      t.until(t.requests == 2); d.io.cancel.poke(true.B)
      t.finish(status = 3, fault = Some(BigInt(0x1040))); d.io.cancel.poke(false.B)
      t.install(0x1000, 0x01020304, 4)
      t.begin("R32UI", 0x1000, write = false); t.finish(values = Vector[BigInt](0x01020304, 0, 0, 1))
      info("SHADER_IMAGE_MEMORY offeredWriteStable=true delayedAckOwned=true wrongReplyIDHeld=true cancelDrain=true primaryFaultPreserved=true healthyReuse=true")
    }
  }

  it should "cancel before memory publication and suppress later lines after a primary fault" in {
    simulate(new ShaderImageMemoryAccess) { d =>
      val t = new Driver(d)
      for (write <- Seq(false, true)) {
        t.begin("RGBA32UI", 0x103f, write, lines = Some(Vector.empty[BigInt]))
        d.io.cancel.poke(true.B); t.finish(status = 4); d.io.cancel.poke(false.B)
      }
      // Conversion takes ownership before a write can be offered. Cancellation
      // must drain that owner without publishing a memory transaction.
      t.begin("RGBA32UI", 0x103f, write = true, lines = Some(Vector.empty[BigInt]))
      for (_ <- 0 until 3) t.tick()
      d.io.cancel.poke(true.B); t.finish(status = 4); d.io.cancel.poke(false.B)
      for (write <- Seq(false, true)) {
        t.begin("RGBA32UI", 0x103f, write, lines = Some(Vector(BigInt(0x1000))))
        t.faultIndices = Set(0)
        t.finish(status = 3, fault = Some(BigInt(0x103f)))
      }
      t.install(0x1000, 0x01020304, 4)
      t.begin("R32UI", 0x1000, write = false); t.finish(values = Vector[BigInt](0x01020304, 0, 0, 1))
      info("SHADER_IMAGE_MEMORY earlyCancelNoRequests=true converterDrain=true firstFaultStopsSecondLine=true healthyReuse=true")
    }
  }
}
