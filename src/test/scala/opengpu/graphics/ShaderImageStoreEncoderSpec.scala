package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class ShaderImageCompatibilityHarness extends Module {
  val io = IO(new Bundle {
    val storage = Input(UInt(6.W)); val image = Input(UInt(6.W)); val bySize = Input(Bool())
    val compatible = Output(Bool())
  })
  io.compatible := ShaderImageFormat.compatible(io.storage, io.image, io.bySize)
}

class ShaderImageStoreEncoderSpec extends AnyFlatSpec {
  import TypedColorReference.{fp, value}
  // Explicit specification table, independent of the RTL format properties.
  private val formats = Vector(
    ("RGBA32F", 0, 4, 32), ("RGBA16F", 0, 4, 16), ("R32F", 0, 1, 32),
    ("RGBA32UI", 1, 4, 32), ("RGBA16UI", 1, 4, 16), ("RGBA8UI", 1, 4, 8), ("R32UI", 1, 1, 32),
    ("RGBA32I", 2, 4, 32), ("RGBA16I", 2, 4, 16), ("RGBA8I", 2, 4, 8), ("R32I", 2, 1, 32),
    ("RGBA8", 0, 4, 8), ("RGBA8_SNORM", 0, 4, 8))
  private def encode(name: String, values: Seq[BigInt]): BigInt = {
    if(name != "RGBA8_SNORM") TypedColorReference.encode(name, values)._1
    else values.zipWithIndex.foldLeft(BigInt(0)) { case (packed, (raw, c)) =>
      val x = value(raw)
      val clamped = if(x.isNaN) 0.0 else math.max(-1.0, math.min(1.0, x))
      packed | (BigInt(math.rint(clamped * 127.0).toLong & 255) << (8 * c))
    }
  }
  private class Driver(d: ShaderImageStoreEncoder) {
    var count = 0
    d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def check(id: Int, kind: Int, values: Seq[BigInt], status: Int, expected: BigInt, bytes: Int): Unit = {
      val tag = BigInt("fedc0000", 16) + count
      d.io.in.ready.expect(true.B); d.io.in.bits.format.poke(id.U); d.io.in.bits.colorType.poke(kind.U)
      d.io.in.bits.tag.poke(tag.U); d.io.in.bits.rgba.zip(values).foreach { case (a, b) => a.poke(b.U) }
      d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      // Poison the producer immediately: only the admitted snapshot may convert.
      d.io.in.bits.format.poke(63.U); d.io.in.bits.colorType.poke(3.U); d.io.in.bits.tag.poke(0.U)
      d.io.in.bits.rgba.foreach(_.poke("hdeadbeef".U))
      var cycles = 0
      while(!d.io.out.valid.peek().litToBoolean && cycles < 8) { d.clock.step(); cycles += 1 }
      assert(d.io.out.valid.peek().litToBoolean, s"image encoder did not finish id=$id")
      d.io.out.bits.status.expect(status.U); d.io.out.bits.tag.expect(tag.U)
      d.io.out.bits.value.expect(expected.U); d.io.out.bits.bytes.expect(bytes.U)
      d.io.out.bits.byteMask.expect(((BigInt(1) << bytes) - 1).U)
      d.io.busy.expect(true.B); d.io.in.ready.expect(false.B)
      val held = d.io.out.bits.peek().litValue
      d.clock.step(1 + count % 3)
      assert(d.io.out.bits.peek().litValue == held)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      d.io.busy.expect(false.B); count += 1
    }
  }

  it should "encode all thirteen image formats with exact saturation, byte enables and stable request snapshots" in {
    assert(ShaderImageFormat.names == formats.map(_._1))
    assert(TypedColorFormat.formats.size == 36)
    simulate(new ShaderImageStoreEncoder) { d =>
      val t = new Driver(d)
      val directed = Vector[BigInt](0, 1, 0x80000000L, 0x3f000000L, 0xbf000000L, 0x3f800000L,
        0xbf800000L, 0x7f800000L, 0xff800000L, 0x7fc12345L, 0xffffffffL, 0x00010080L, 0xffff7fffL, 0x477ff000L)
      val random = new scala.util.Random(0x494d4731)
      for((name, kind, channels, bits) <- formats) {
        val samples = directed.indices.map(i => Vector.tabulate(4)(c => directed((i + c) % directed.size))) ++
          Vector.fill(24)(Vector.fill(4)(BigInt(random.nextLong() & 0xffffffffL)))
        for(values <- samples) withClue(s"image=$name values=$values ") {
          t.check(TextureImageFormat.id(name), kind, values, 0, encode(name, values), channels * bits / 8)
        }
      }
      // Nearest FP32 neighbours of every signed-normalized quantization boundary.
      for(n <- 0 until 127; delta <- -1 to 1) {
        val near = fp((n + 0.5) / 127.0) + delta
        val values = Vector(near, near | (BigInt(1) << 31), fp(2), fp(-2))
        t.check(TextureImageFormat.id("RGBA8_SNORM"), 0, values, 0, encode("RGBA8_SNORM", values), 4)
      }
      info(s"SHADER_IMAGE_STORE formats=13 transactions=${t.count} SNORMBoundaries=381 integerSaturation=true exactByteMasks=true heldSnapshots=true")
    }
  }

  it should "reject every non-image storage format and every mismatched shader type without write enables" in {
    simulate(new ShaderImageStoreEncoder) { d =>
      val t = new Driver(d); val values = Vector.fill(4)(BigInt("ffffffff", 16))
      val supported = formats.map(f => TextureImageFormat.id(f._1)).toSet
      for(id <- 0 until 64 if !supported(id); kind <- 0 until 4)
        t.check(id, kind, values, 1, 0, 0)
      for((name, kind, _, _) <- formats; other <- 0 until 4 if other != kind)
        t.check(TextureImageFormat.id(name), other, values, 2, 0, 0)
      assert(t.count == 243)
      info("SHADER_IMAGE_STORE rejectedFormatsAndTypes=243 noWriteEnables=true healthyIdleReuse=true")
    }
  }

  it should "distinguish size and class compatibility for every image-format pair and reject unsupported formats" in {
    simulate(new ShaderImageCompatibilityHarness) { d =>
      for((left, _, lc, lb) <- formats; (right, _, rc, rb) <- formats; bySize <- Seq(false, true)) {
        d.io.storage.poke(TextureImageFormat.id(left).U); d.io.image.poke(TextureImageFormat.id(right).U)
        d.io.bySize.poke(bySize.B); d.clock.step()
        d.io.compatible.expect((lc * lb == rc * rb && (bySize || (lc == rc && lb == rb))).B)
      }
      val supported = formats.map(f => TextureImageFormat.id(f._1)).toSet
      for(id <- 0 until 64 if !supported(id); swap <- Seq(false, true); bySize <- Seq(false, true)) {
        d.io.storage.poke((if(swap) TextureImageFormat.id("RGBA8") else id).U)
        d.io.image.poke((if(swap) id else TextureImageFormat.id("RGBA8")).U)
        d.io.bySize.poke(bySize.B); d.clock.step(); d.io.compatible.expect(false.B)
      }
      info("SHADER_IMAGE_COMPATIBILITY validPairs=338 invalidPairs=204 sizeAndClassChecked=true")
    }
  }
}
