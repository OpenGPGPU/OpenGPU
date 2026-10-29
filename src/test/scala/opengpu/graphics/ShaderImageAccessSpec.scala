package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ShaderImageAccessSpec extends AnyFlatSpec {
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
  private class Driver(val d: ShaderImageAccess) {
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
    def begin(name: String = "RGBA32UI", target: Int = 0, layered: Boolean = true,
        write: Boolean = false, values: Seq[BigInt] = Vector.fill(4)(BigInt(0)),
        configure: ShaderImageBindingAddressRequest => Unit = _ => (),
        lines: Option[Vector[BigInt]] = None): Unit = {
      assert(pending.isEmpty && heldOffer.isEmpty)
      val f = ImageFormatReference.format(name)
      val selected = Seq(0, 2, 5, 1)(target)
      texelAddress = BigInt(0x103f + selected * 128); texelBytes = f.bytes; requestWrite = write
      requests = 0; faultIndices = Set.empty; operations += 1
      val first = texelAddress & ~BigInt(63)
      expectedLines = lines.getOrElse(Vector(first, first + 64))
      requestTag = BigInt("f1000000", 16) + operations
      d.io.in.ready.expect(true.B); zero(d.io.in.bits)
      val q = d.io.in.bits.access; val b = q.imageBinding; val v = b.view
      identity(q.identity); identity(b.identity)
      q.identity.generation.poke(operations.U); b.identity.generation.poke(operations.U)
      requestIdentity = q.identity.peek().litValue
      q.tag.poke(requestTag.U); q.unitIndex.poke((operations % 4).U); b.unitIndex.poke((operations % 4).U)
      q.stage.poke(GraphicsStage.compute.U); q.isWrite.poke(write.B)
      q.shaderTarget.poke((if (target != 0 && !layered) 0 else target).U)
      q.colorType.poke(f.kind.U); q.hasFormatQualifier.poke(true.B)
      q.shaderFormat.poke(TextureImageFormat.id(name).U)
      q.z.poke((if (target == 0 || !layered) BigInt("ffffffff", 16) else BigInt(selected)).U)
      b.bound.poke(true.B); b.complete.poke(true.B); b.stageMask.poke(4.U)
      b.readable.poke(true.B); b.writable.poke(true.B)
      b.storageFormat.poke(TextureImageFormat.id(name).U); b.imageFormat.poke(TextureImageFormat.id(name).U)
      b.compatibleBySize.poke(true.B); b.maxLevel.poke(15.U)
      b.layered.poke(layered.B); b.layer.poke((if (target == 0) BigInt("ffffffff", 16) else BigInt(selected)).U)
      v.target.poke(target.U); v.base.poke(0x103f.U)
      v.allocationBase.poke(0x1000.U); v.allocationLimit.poke(0x1400.U)
      v.texWidth.poke(1.U); v.texHeight.poke(1.U)
      v.texDepth.poke((if (target == 3) 2 else 1).U); v.layers.poke((if (target == 1) 3 else 1).U)
      v.rowPitch.poke(64.U); v.slicePitch.poke((if (target == 3) 128 else 0).U)
      v.layerPitch.poke((if (target == 1 || target == 2) 128 else 0).U); v.blockBytes.poke(f.bytes.U)
      d.io.in.bits.rgba.zip(values).foreach { case (v, x) => v.poke(x.U) }
      configure(q)
      d.io.in.valid.poke(true.B); tick(); d.io.in.valid.poke(false.B); zero(d.io.in.bits)
    }
    def finish(status: Int = 0, values: Seq[BigInt] = Vector.fill(4)(BigInt(0)), fault: Option[BigInt] = None): Unit = {
      until(d.io.out.valid.peek().litToBoolean)
      assert(pending.isEmpty && requests == expectedLines.size)
      d.io.out.bits.tag.expect(requestTag.U)
      d.io.out.bits.unitIndex.expect((operations % 4).U)
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

  it should "compose captured image bindings with exact typed split-line loads and stores" in {
    simulate(new ShaderImageAccess) { d =>
      val t = new Driver(d)
      for (name <- formats; target <- 0 to 3; layered <- Seq(false, true)) {
        val f = ImageFormatReference.format(name)
        val address = BigInt(0x103f + Seq(0, 2, 5, 1)(target) * 128)
        val values = if (f.kind == 0) Vector(-2.0, 0.5, 1.25, 65504.0).map(fp)
          else Vector[BigInt](0xffffffffL, 0x80000000L, 0x12345678L, 0x10001)
        val expected = packed(name, values)
        val before = Vector.tabulate(1024)(i => t.byte(BigInt(0x1000 + i)))
        t.begin(name, target, layered, write = true, values = values); t.finish()
        for (i <- 0 until 1024) {
          val a = BigInt(0x1000 + i)
          val wanted = if (a >= address && a < address + f.bytes) ((expected >> (8 * (a - address).toInt)) & 255).toInt else before(i)
          assert(t.byte(a) == wanted, s"composed image store changed guard byte$i format=$name target=$target")
        }
        t.begin(name, target, layered); t.finish(values = ImageFormatReference.decode(name, expected))
      }
      assert(t.operations == 208)
      info("SHADER_IMAGE_ACCESS formats=13 targets=4 layeredAndSingle=true operations=208 splitLines=true guards=true poisonedInputs=true")
    }
  }

  it should "gate physical memory with binding permissions types full-width coordinates and backing bounds" in {
    simulate(new ShaderImageAccess) { d =>
      val t = new Driver(d)
      val changes: Seq[(Int, ShaderImageBindingAddressRequest => Unit)] = Seq(
        0 -> (q => q.imageBinding.bound.poke(false.B)),
        0 -> (q => q.imageBinding.complete.poke(false.B)),
        0 -> (q => q.x.poke("hffffffff".U)),
        0 -> (q => q.imageBinding.storageFormat.poke(TextureImageFormat.id("R8").U)),
        2 -> (q => q.imageBinding.identity.generation.poke(0.U)),
        3 -> (q => { q.imageBinding.readable.poke(false.B); q.imageBinding.writable.poke(false.B) }),
        3 -> (q => q.imageBinding.stageMask.poke(1.U)),
        6 -> (q => q.colorType.poke(0.U)),
        4 -> (q => q.imageBinding.view.allocationLimit.poke(0x1040.U)))
      for (write <- Seq(false, true); (status, change) <- changes) {
        t.begin(write = write, configure = change, lines = Some(Vector.empty)); t.finish(status = status)
      }
      t.begin(target = 2, configure = q => q.z.poke(13.U), lines = Some(Vector.empty)); t.finish()
      // The texel fits the descriptor span, but its full backing cache line does not.
      t.begin(configure = q => q.imageBinding.view.allocationLimit.poke(0x104f.U), lines = Some(Vector.empty))
      t.finish(status = 4)
      assert(t.operations == 20)
      info("SHADER_IMAGE_ACCESS rejectedOrNoOp=20 memoryRequests=0 permissionBeforeMemory=true fullBackingLinesChecked=true")
    }
  }

  it should "retain split-store acknowledgement ownership through cancellation and primary faults" in {
    simulate(new ShaderImageAccess) { d =>
      val t = new Driver(d); val values = Vector.fill(4)(BigInt("12345678", 16))
      for (fault <- Seq(false, true)) {
        t.ready = false; t.holdAck = true
        t.begin(write = true, values = values, lines = Some(Vector(BigInt(0x1000))))
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
        t.finish(status = if (fault) 7 else 5, fault = if (fault) Some(BigInt(0x103f)) else None)
        d.io.cancel.poke(false.B)
      }
      t.begin(); t.faultIndices = Set(1)
      t.until(t.requests == 2); d.io.cancel.poke(true.B)
      t.finish(status = 7, fault = Some(BigInt(0x1040))); d.io.cancel.poke(false.B)
      t.install(0x103f, 0x01020304, 4)
      t.begin("R32UI"); t.finish(values = Vector[BigInt](0x01020304, 0, 0, 1))
      info("SHADER_IMAGE_ACCESS heldOffer=true delayedStoreAck=true wrongReplyID=true cancelDrain=true primaryFault=true healthyReuse=true")
    }
  }

  it should "drain cancellation at every address and memory handoff without leaking child ownership" in {
    simulate(new ShaderImageAccess) { d =>
      val t = new Driver(d)
      for (write <- Seq(false, true); delay <- 0 until 32) {
        t.begin(write = write)
        for (_ <- 0 until delay if !d.io.out.valid.peek().litToBoolean) t.tick()
        val completed = d.io.out.valid.peek().litToBoolean
        val result = d.io.out.bits.rgba.map(_.peek().litValue).toVector
        d.io.cancel.poke(true.B)
        t.until(d.io.out.valid.peek().litToBoolean)
        t.expectedLines = t.expectedLines.take(t.requests)
        t.finish(status = if (completed) 0 else 5,
          values = if (completed) result else Vector.fill(4)(BigInt(0)))
        d.io.cancel.poke(false.B)
      }
      t.install(0x103f, 0x10203040, 4)
      t.begin("R32UI"); t.finish(values = Vector[BigInt](0x10203040, 0, 0, 1))
      info("SHADER_IMAGE_ACCESS cancelCycleSweep=64 childOwnersDrained=true heldResultsStable=true healthyReuse=true")
    }
  }
}
