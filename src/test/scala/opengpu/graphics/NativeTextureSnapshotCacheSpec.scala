package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeTextureSnapshotCacheSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private def key(k: NativeTextureSnapshotKey, unit: Int = 0, generation: Int = 13,
      resource: BigInt = 0x9000, lod: BigInt = 0, context: Int = 7, pass: Int = 11): Unit = {
    k.resources.version.poke(1.U); k.resources.address.poke(resource.U)
    k.identity.context.poke(context.U); k.identity.pass.poke(pass.U); k.identity.generation.poke(generation.U)
    k.unit.poke(unit.U); k.lod.poke(lod.U)
  }
  private def write(d: NativeTextureSnapshotCache, unit: Int, tag: BigInt): Unit = {
    zero(d.io.write.bits); key(d.io.write.bits.key, unit = unit)
    d.io.write.bits.template.group.tag.poke(tag.U)
    d.io.write.valid.poke(true.B); d.clock.step(); d.io.write.valid.poke(false.B)
  }
  it should "match all resource identity binding and LOD bits while retaining the checked template" in {
    simulate(new NativeTextureSnapshotCache()) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      write(d, 0, 0xdeadbeefL); key(d.io.lookup); d.io.hit.expect(true.B)
      d.io.template.group.tag.expect(0xdeadbeefL.U)
      key(d.io.lookup, unit = 1); d.io.hit.expect(false.B)
      key(d.io.lookup, generation = 14); d.io.hit.expect(false.B)
      key(d.io.lookup, context = 8); d.io.hit.expect(false.B)
      key(d.io.lookup, pass = 12); d.io.hit.expect(false.B)
      key(d.io.lookup, resource = 0x19000); d.io.hit.expect(false.B)
      key(d.io.lookup, lod = 0x80000000L); d.io.hit.expect(false.B)
      key(d.io.lookup); d.io.lookup.resources.version.poke(0.U); d.io.hit.expect(false.B)
      key(d.io.lookup); d.io.hit.expect(true.B)
    }
  }
  it should "update an existing binding without consuming another slot and clear a concurrent write" in {
    simulate(new NativeTextureSnapshotCache()) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for (u <- 0 until 4) write(d, u, 100 + u)
      for (u <- 0 until 4) { key(d.io.lookup, unit = u); d.io.hit.expect(true.B) }
      write(d, 2, 202); key(d.io.lookup, unit = 2); d.io.template.group.tag.expect(202.U)
      for (u <- 0 until 4) { key(d.io.lookup, unit = u); d.io.hit.expect(true.B) }
      write(d, 4, 104); key(d.io.lookup); d.io.hit.expect(false.B)
      for (u <- 1 to 4) { key(d.io.lookup, unit = u); d.io.hit.expect(true.B) }
      d.io.clear.poke(true.B); d.io.write.valid.poke(true.B); d.io.hit.expect(false.B)
      d.clock.step(); d.io.clear.poke(false.B); d.io.write.valid.poke(false.B)
      for (u <- 0 to 4) { key(d.io.lookup, unit = u); d.io.hit.expect(false.B) }
    }
  }
  it should "replace and invalidate a single entry without a variable row index" in {
    simulate(new NativeTextureSnapshotCache(1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      write(d, 0, 10); key(d.io.lookup); d.io.hit.expect(true.B)
      write(d, 1, 11); key(d.io.lookup); d.io.hit.expect(false.B)
      key(d.io.lookup, unit = 1); d.io.hit.expect(true.B); d.io.template.group.tag.expect(11.U)
      d.io.clear.poke(true.B); d.clock.step(); d.io.clear.poke(false.B); d.io.hit.expect(false.B)
    }
  }
}
