package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedFragmentImageStateReaderSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero, Memory}
  it should "publish a state and its captured image root only after both validate and drain" in {
    val f = FragmentShaderConfig(2, 4)
    simulate(new TypedFragmentImageStateReader(GpuConfig(lanes = 4, warps = 2), f,
      GraphicsConfig(screenWidth = 16, screenHeight = 16))) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val mem = new Memory(d.io.memoryRequest.bits, d.io.memoryResponse.bits, d.io.memoryRequest.valid,
        d.io.memoryRequest.ready, d.io.memoryResponse.valid, d.io.memoryResponse.ready)
      def tick(): Unit = { mem.drive(); mem.capture(); d.clock.step() }
      def until(p: => Boolean): Unit = { var n = 0; while (!p && n < 5000) { tick(); n += 1 }
        assert(p, s"state/image timeout: seen=${mem.seen.map(_.address.toString(16)).mkString(",")} pending=${mem.pending.size} out=${d.io.out.valid.peek().litToBoolean}") }
      val values = Map[String, BigInt](
        "version" -> 1, "engineCount" -> 1, "fragment.version" -> 1,
        "fragment.identity.context" -> 7, "fragment.identity.pass" -> 8, "fragment.identity.generation" -> 9,
        "fragment.shaderPc" -> 0x1000, "fragment.varyingSlots" -> 2, "fragment.colorSlots" -> 4,
        "fragment.colorMask" -> 15, "fragment.scratch.base" -> 0x100000, "fragment.scratch.limit" -> 0x104000,
        "fragment.scratch.allocationBase" -> 0x100000, "fragment.scratch.allocationLimit" -> 0x104000,
        "tests.version" -> 1)
      val fields = TypedFragmentStateLayout.fields(d.io.out.bits.state)
      def install(early: Boolean = false, enabled: Boolean = true): Unit = {
        for (w <- 0 until 128) mem.putWord(0x2000 + 4 * w, 0)
        for ((v, w) <- Vector[BigInt](TypedFragmentStateLayout.magic, 512, 1, 4, 7, 8, 9, 0).zipWithIndex)
          mem.putWord(0x2000 + 4 * w, v)
        for (field <- fields) {
          val value = field.name match {
            case "fragment.earlyDepthSafe" if early => BigInt(1)
            case "image.version" if enabled => BigInt(1)
            case "image.address" if enabled => BigInt(0x5000)
            case _ => values.getOrElse(field.name, BigInt(0))
          }
          val raw = value & ((BigInt(1) << (field.words * 32)) - 1)
          for (w <- 0 until field.words) mem.putWord(0x2000 + 4 * (field.word + w), (raw >> (w * 32)) & 0xffffffffL)
        }
      }
      def root(context: Int = 7, table: Int = 0x8000): Unit = {
        for (w <- 0 until 16) mem.putWord(0x5000 + 4 * w, 0)
        for ((v, w) <- Vector[BigInt](ShaderImagePublicationLayout.magic, 64, 1, context, 8, 9, 1, 1, table, table + 1024, 0).zipWithIndex)
          mem.putWord(0x5000 + 4 * w, v)
      }
      def begin(): Unit = {
        val q = d.io.in.bits; q.state.arena.version.poke(1.U); q.state.arena.base.poke("h2000".U)
        q.state.arena.limit.poke("h2200".U); q.state.arena.identity.context.poke(7.U)
        q.state.arena.identity.pass.poke(8.U); q.state.arena.identity.generation.poke(9.U); q.state.handle.poke(0.U)
        d.io.in.valid.poke(true.B); until(d.io.in.ready.peek().litToBoolean); tick(); d.io.in.valid.poke(false.B)
        q.state.arena.base.poke("h6000".U)
      }
      def result(code: Int, hit: Boolean = false, enabled: Boolean = false): Unit = {
        until(d.io.out.valid.peek().litToBoolean); d.io.out.bits.status.expect(code.U)
        d.io.out.bits.stateValid.expect((code == 0).B); d.io.out.bits.image.enabled.expect(enabled.B)
        if (code == 0) d.io.out.bits.stateHit.expect(hit.B)
        val held = d.io.out.bits.peek().litValue; val wasCancelled = d.io.cancel.peek().litToBoolean
        d.io.cancel.poke(true.B)
        for (_ <- 0 until 3) { tick(); assert(d.io.out.bits.peek().litValue == held) }
        d.io.cancel.poke(wasCancelled.B)
        assert(mem.pending.isEmpty && mem.reply.isEmpty)
        d.io.out.ready.poke(true.B); tick(); d.io.out.ready.poke(false.B)
      }
      install(); root(); begin(); result(0, enabled = true)
      assert(mem.seen.count(_.address == 0x5000) == 1)
      val stateReads = mem.seen.count(_.address >= 0x2000) - mem.seen.count(_.address >= 0x2200)
      assert(stateReads == 8)
      root(table = 0x9000); begin(); until(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.image.table.base.expect("h9000".U); result(0, hit = true, enabled = true)
      assert(mem.seen.count(_.address == 0x5000) == 2)
      root(context = 99); begin(); result(5)
      root(); mem.faultAt = Set(BigInt(0x5000)); begin(); result(3); mem.faultAt = Set.empty
      d.io.invalidate.poke(true.B); tick(); d.io.invalidate.poke(false.B); install(enabled = false)
      begin(); result(0)
      d.io.invalidate.poke(true.B); tick(); d.io.invalidate.poke(false.B); install(); root()
      val beforeCancel = mem.seen.count(_.address == 0x5000)
      mem.beforeRequest = r => if (r.address == 0x5000) mem.holdResponses = true
      begin(); until(mem.seen.count(_.address == 0x5000) > beforeCancel)
      mem.beforeRequest = _ => ()
      d.io.memoryResponse.valid.poke(true.B); d.io.memoryResponse.bits.transactionId.poke(2.U)
      d.io.memoryResponse.ready.expect(false.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      d.io.cancel.poke(true.B); for (_ <- 0 until 4) { tick(); d.io.out.valid.expect(false.B) }
      mem.holdResponses = false; result(4); d.io.cancel.poke(false.B)
      d.io.invalidate.poke(true.B); tick(); d.io.invalidate.poke(false.B); install(early = true); root()
      val roots = mem.seen.count(_.address == 0x5000); begin(); result(1)
      assert(mem.seen.count(_.address == 0x5000) == roots)
      d.io.invalidate.poke(true.B); tick(); d.io.invalidate.poke(false.B); install(); root()
      mem.holdResponses = true; begin(); until(mem.pending.nonEmpty); d.io.cancel.poke(true.B)
      for (_ <- 0 until 4) { tick(); d.io.out.valid.expect(false.B) }
      mem.holdResponses = false; result(4); d.io.cancel.poke(false.B)
      begin(); result(0, enabled = true)
    }
  }
}
