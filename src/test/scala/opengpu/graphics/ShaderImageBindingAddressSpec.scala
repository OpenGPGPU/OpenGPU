package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderImageBindingAddressSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero, identity}
  private class Driver(val d: ShaderImageBindingAddress) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    var operations = 0
    var expectedIdentity = BigInt(0); var expectedTag = BigInt(0)
    var expectedUnit = BigInt(0); var expectedFormat = BigInt(0); var expectedKind = BigInt(0)
    var expectedWrite = false
    def tick(): Unit = d.clock.step()
    def begin(target: Int = 0, layered: Boolean = true, selected: BigInt = 0, write: Boolean = false,
        configure: ShaderImageBindingAddressRequest => Unit = _ => ()): Unit = {
      d.io.in.ready.expect(true.B)
      val q = d.io.in.bits; zero(q)
      operations += 1; expectedTag = BigInt("e0000000", 16) + operations
      identity(q.identity); identity(q.imageBinding.identity)
      q.identity.generation.poke(operations.U); q.imageBinding.identity.generation.poke(operations.U)
      q.tag.poke(expectedTag.U); q.unitIndex.poke((operations % 4).U)
      q.stage.poke(GraphicsStage.compute.U); q.isWrite.poke(write.B)
      q.shaderTarget.poke((if (target != 0 && !layered) 0 else target).U)
      q.colorType.poke(1.U); q.hasFormatQualifier.poke(true.B)
      q.shaderFormat.poke(TextureImageFormat.id("RGBA32UI").U)
      q.x.poke(2.U); q.y.poke(1.U); q.z.poke(selected.U)
      val b = q.imageBinding; b.unitIndex.poke((operations % 4).U)
      b.bound.poke(true.B); b.complete.poke(true.B); b.stageMask.poke(4.U)
      b.readable.poke(true.B); b.writable.poke(true.B)
      b.storageFormat.poke(TextureImageFormat.id("RGBA32UI").U)
      b.imageFormat.poke(TextureImageFormat.id("RGBA32UI").U)
      b.compatibleBySize.poke(true.B); b.maxLevel.poke(15.U)
      b.layered.poke(layered.B); b.layer.poke(selected.U)
      val v = b.view; v.target.poke(target.U)
      v.allocationBase.poke(0x1000.U); v.allocationLimit.poke(0x2000.U); v.base.poke(0x1000.U)
      v.texWidth.poke(3.U); v.texHeight.poke((if (target == 2) 3 else 2).U)
      v.texDepth.poke((if (target == 3) 2 else 1).U); v.layers.poke((if (target == 1) 3 else 1).U)
      v.rowPitch.poke(64.U); v.slicePitch.poke((if (target == 3) 128 else 0).U)
      v.layerPitch.poke((if (target == 2) 256 else if (target == 1) 128 else 0).U)
      v.blockBytes.poke(16.U)
      configure(q)
      expectedIdentity = q.identity.peek().litValue; expectedUnit = q.unitIndex.peek().litValue
      expectedFormat = b.imageFormat.peek().litValue; expectedKind = q.colorType.peek().litValue
      expectedWrite = q.isWrite.peek().litToBoolean
      d.io.in.valid.poke(true.B); tick(); d.io.in.valid.poke(false.B); zero(q)
    }
    def finish(address: Option[BigInt] = None, status: Int = 0,
        allocationBase: BigInt = 0x1000, allocationLimit: BigInt = 0x2000): Unit = {
      var steps = 0
      while (!d.io.out.valid.peek().litToBoolean && steps < 40) { tick(); steps += 1 }
      assert(d.io.out.valid.peek().litToBoolean, "image binding did not drain")
      val r = d.io.out.bits
      assert(r.identity.peek().litValue == expectedIdentity)
      r.tag.expect(expectedTag.U); r.unitIndex.expect(expectedUnit.U); r.isWrite.expect(expectedWrite.B)
      r.format.expect(expectedFormat.U); r.colorType.expect(expectedKind.U)
      r.status.expect(status.U); r.validTexel.expect(address.nonEmpty.B)
      r.address.expect(address.getOrElse(BigInt(0)).U)
      r.allocationBase.expect((if (address.nonEmpty) allocationBase else BigInt(0)).U)
      r.allocationLimit.expect((if (address.nonEmpty) allocationLimit else BigInt(0)).U)
      val held = r.peek().litValue; val oldCancel = d.io.cancel.peek().litToBoolean
      d.io.cancel.poke(true.B); d.io.in.valid.poke(true.B)
      for (_ <- 0 until 4) {
        d.io.in.ready.expect(false.B); d.io.busy.expect(true.B); tick()
        d.io.out.valid.expect(true.B); assert(r.peek().litValue == held)
      }
      d.io.in.valid.poke(false.B); d.io.cancel.poke(oldCancel.B)
      d.io.out.ready.poke(true.B); tick(); d.io.out.ready.poke(false.B)
      d.io.busy.expect(false.B)
    }
  }

  it should "map all layered targets and single-layer bindings with captured formats and exact addresses" in {
    simulate(new ShaderImageBindingAddress) { d =>
      val t = new Driver(d)
      for (target <- 0 to 3; layered <- Seq(false, true); write <- Seq(false, true)) {
        val selected = Seq(0, 2, 5, 1)(target)
        t.begin(target, layered, selected, write, q => {
          if (target == 0) { q.imageBinding.layer.poke("hffffffff".U); q.z.poke("hffffffff".U) }
          else if (!layered) q.z.poke("hffffffff".U)
        })
        val plane = Seq(0, 128, 256, 128)(target)
        t.finish(Some(BigInt(0x1000 + 64 + 32 + selected * plane)))
      }
      for (name <- ShaderImageFormat.names) {
        val f = ImageFormatReference.format(name)
        t.begin(configure = q => {
          q.imageBinding.storageFormat.poke(TextureImageFormat.id(name).U)
          q.imageBinding.imageFormat.poke(TextureImageFormat.id(name).U)
          q.shaderFormat.poke(TextureImageFormat.id(name).U); q.colorType.poke(f.kind.U)
          q.imageBinding.view.blockBytes.poke(f.bytes.U)
        })
        t.finish(Some(BigInt(0x1000 + 64 + 2 * f.bytes)))
      }
      // Equal-size reinterpretation consumes the image-unit format. It must
      // not silently retain the storage's component type or channel count.
      for (bySize <- Seq(false, true)) {
        t.begin(configure = q => {
          q.imageBinding.storageFormat.poke(TextureImageFormat.id("RGBA8").U)
          q.imageBinding.imageFormat.poke(TextureImageFormat.id("R32UI").U)
          q.shaderFormat.poke(TextureImageFormat.id("R32UI").U)
          q.imageBinding.view.blockBytes.poke(4.U); q.imageBinding.compatibleBySize.poke(bySize.B)
        })
        t.finish(if (bySize) Some(BigInt(0x1048)) else None)
      }
      t.begin(write = true, configure = q => { q.hasFormatQualifier.poke(false.B); q.shaderFormat.poke(63.U) })
      t.finish(Some(BigInt(0x1060)))
      assert(t.operations == 32)
      info("SHADER_IMAGE_BINDING targets=4 layeredAndSingle=true formats=13 operations=32 fourUnits=true sizeVsClass=true heldSnapshots=true")
    }
  }

  it should "make nonexistent texels zero no-ops without truncating coordinates or selected layers" in {
    simulate(new ShaderImageBindingAddress) { d =>
      val t = new Driver(d)
      val changes: Seq[ShaderImageBindingAddressRequest => Unit] = Seq(
        q => { q.imageBinding.bound.poke(false.B); zero(q.imageBinding.identity); q.imageBinding.stageMask.poke(0.U) },
        q => q.imageBinding.complete.poke(false.B),
        q => q.imageBinding.level.poke(0x10000.U),
        q => q.imageBinding.baseLevel.poke(1.U),
        q => q.imageBinding.storageFormat.poke(TextureImageFormat.id("RGBA16UI").U),
        q => q.imageBinding.storageFormat.poke(TextureImageFormat.id("R8").U),
        q => q.x.poke("hffffffff".U),
        q => q.y.poke("hffffffff".U))
      for (write <- Seq(false, true); change <- changes) { t.begin(write = write, configure = change); t.finish() }
      for (write <- Seq(false, true); layered <- Seq(false, true); (target, selected) <- Seq(1 -> 0x10002, 2 -> 13, 3 -> 0x10001)) {
        t.begin(target, layered, selected, write); t.finish()
      }
      assert(t.operations == 28)
      info("SHADER_IMAGE_BINDING invalidNoOps=28 fullWidthCoordinates=true cubeFacesCheckedBeforeNarrowing=true loadStoreSymmetry=true")
    }
  }

  it should "reject owner permission shader-type and backing-span violations before publishing an address" in {
    simulate(new ShaderImageBindingAddress) { d =>
      val t = new Driver(d)
      val changes: Seq[(Int, ShaderImageBindingAddressRequest => Unit)] = Seq(
        1 -> (q => q.unitIndex.poke(4.U)),
        2 -> (q => q.imageBinding.identity.generation.poke(0.U)),
        2 -> (q => q.imageBinding.unitIndex.poke(0xffffffffL.U)),
        3 -> (q => q.imageBinding.readable.poke(false.B)),
        3 -> (q => q.imageBinding.stageMask.poke(1.U)),
        3 -> (q => q.stage.poke(7.U)),
        1 -> (q => q.imageBinding.view.level.poke(1.U)),
        1 -> (q => q.imageBinding.view.block4x4.poke(true.B)),
        1 -> (q => q.imageBinding.view.blockBytes.poke(8.U)),
        1 -> (q => q.imageBinding.view.texWidth.poke(0.U)),
        6 -> (q => q.colorType.poke(0.U)),
        6 -> (q => q.shaderTarget.poke(1.U)),
        6 -> (q => q.hasFormatQualifier.poke(false.B)),
        6 -> (q => q.shaderFormat.poke(TextureImageFormat.id("RGBA32I").U)),
        4 -> (q => q.imageBinding.view.rowPitch.poke(16.U)),
        4 -> (q => q.imageBinding.view.allocationLimit.poke(0x1040.U)),
        4 -> (q => q.imageBinding.view.allocationLimit.poke(((BigInt(1) << 32) + 64).U)))
      for ((status, change) <- changes) { t.begin(configure = change); t.finish(status = status) }
      t.begin(write = true, configure = q => q.imageBinding.writable.poke(false.B)); t.finish(status = 3)
      t.begin(); t.finish(Some(BigInt(0x1060)))
      info("SHADER_IMAGE_BINDING rejected=18 identityAndStage=true shaderQualifierAndKind=true completeLevelBounds=true healthyReuse=true")
    }
  }

  it should "drain an accepted address calculation on cancellation and retain a primary bounds error" in {
    simulate(new ShaderImageBindingAddress) { d =>
      val t = new Driver(d)
      for (write <- Seq(false, true); delay <- Seq(0, 3)) {
        t.begin(target = 3, selected = 1, write = write)
        for (_ <- 0 until delay) t.tick()
        d.io.cancel.poke(true.B); t.finish(status = 5); d.io.cancel.poke(false.B)
      }
      t.begin(configure = q => q.imageBinding.view.rowPitch.poke(16.U))
      for (_ <- 0 until 3) t.tick()
      d.io.cancel.poke(true.B); t.finish(status = 4); d.io.cancel.poke(false.B)
      t.begin(); t.finish(Some(BigInt(0x1060)))
      info("SHADER_IMAGE_BINDING cancelBeforeAndAfterAddressAdmission=true drain=true primaryBoundsPreserved=true healthyReuse=true")
    }
  }
}
