package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import opengpu.graphics.WindowRecordTest._
import org.scalatest.flatspec.AnyFlatSpec

/** Actual domain adapter followed by the production GNG input mapper. Input peers supply raw TES interface words; there
  * is no CU or shader execution.
  */
private class TessellationGeometryRawHarness(domain: Int, p: VertexShaderStageConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TessellationGeometryRawRequest(p)))
    val raw = Output(Valid(new TessellationGeometryRawPacket(p)))
    val allowTransfer = Input(Bool())
    val task = Decoupled(new NativeGeometryInputTask)
    val fault = Decoupled(new TessellationGeometryRawFault)
    val mapperFault = Decoupled(new GeometryPrimitiveInputFault)
    val cancel = Input(Bool()); val cancelPending = Output(Bool()); val busy = Output(Bool())
  })
  private val raw = Wire(Decoupled(new TessellationGeometryRawPacket(p)))
  domain match {
    case 0 =>
      val child = Module(new TessellationIsolineGeometryRawBridge(p))
      child.io.in.valid := io.in.valid; io.in.ready := child.io.in.ready
      child.io.in.bits.link := io.in.bits.link
      child.io.in.bits.evaluated.taskId := io.in.bits.tesTaskId;
      child.io.in.bits.evaluated.sequence := io.in.bits.tesSequence
      child.io.in.bits.evaluated.point := io.in.bits.point
      for (i <- 0 until 2) child.io.in.bits.evaluated.vertices(i) := io.in.bits.vertices(i)
      raw <> child.io.out; io.fault <> child.io.fault; child.io.cancel := io.cancel
      io.cancelPending := child.io.cancelPending; io.busy := child.io.busy
    case 1 =>
      val child = Module(new TessellationTriangleGeometryRawBridge(p))
      child.io.in.valid := io.in.valid; io.in.ready := child.io.in.ready
      child.io.in.bits.link := io.in.bits.link
      child.io.in.bits.evaluated.taskId := io.in.bits.tesTaskId;
      child.io.in.bits.evaluated.sequence := io.in.bits.tesSequence
      child.io.in.bits.evaluated.point := io.in.bits.point; child.io.in.bits.evaluated.vertices := io.in.bits.vertices
      raw <> child.io.out; io.fault <> child.io.fault; child.io.cancel := io.cancel
      io.cancelPending := child.io.cancelPending; io.busy := child.io.busy
    case 2 =>
      val child = Module(new TessellationQuadGeometryRawBridge(p))
      child.io.in.valid := io.in.valid; io.in.ready := child.io.in.ready
      child.io.in.bits.link := io.in.bits.link
      child.io.in.bits.evaluated.taskId := io.in.bits.tesTaskId;
      child.io.in.bits.evaluated.sequence := io.in.bits.tesSequence
      child.io.in.bits.evaluated.point := io.in.bits.point; child.io.in.bits.evaluated.vertices := io.in.bits.vertices
      raw <> child.io.out; io.fault <> child.io.fault; child.io.cancel := io.cancel
      io.cancelPending := child.io.cancelPending; io.busy := child.io.busy
  }
  private val mapper = Module(new NativeGeometryInputMapper(p))
  mapper.io.in.valid := raw.valid && io.allowTransfer; mapper.io.in.bits := raw.bits.request
  raw.ready := mapper.io.in.ready && io.allowTransfer
  io.raw.valid := raw.valid; io.raw.bits := raw.bits
  io.task <> mapper.io.task; io.mapperFault <> mapper.io.fault
}

class TessellationGeometryRawBridgeSpec extends AnyFlatSpec {
  private val p = VertexShaderStageConfig(varyings = 16)
  // Independent linked semantic inventory: position, sparse location13, then
  // the remaining original slots. Full64 includes position, not64+position.
  private val sparse = Vector(56, 57, 58, 60, 61, 62)
  private val sources = Vector(0, 1, 2, 3) ++ sparse ++ (4 until 67).filterNot(sparse.contains).take(53) ++ Vector(67)
  private def word(vertex: Int, component: Int): BigInt = BigInt("80000000", 16) + vertex * 4096 + component * 17
  private class Driver(val d: TessellationGeometryRawHarness, val domain: Int) {
    private var expectedSources = sources
    private var expectedPacked = Vector.range(0, 64)
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def until(done: => Boolean): Unit = {
      var n = 0; while (!done && n < 700) { d.clock.step(); n += 1 };
      assert(done, "raw mapping exceeded64+3*64 bounded steps")
    }
    def prepare(point: Boolean = false, components: Int = 64, duplicate: Boolean = false): Unit = {
      expectedSources = if (duplicate) sources.updated(5, 56) else sources
      expectedPacked = if (duplicate) Vector.range(0, 64).updated(5, 4) else Vector.range(0, 64)
      zero(d.io.in.bits); val q = d.io.in.bits; val b = q.link; val g = b.program
      b.version.poke(1.U); b.domain.poke(domain.U); identity(b.identity)
      b.expectedTesTaskId.poke(41.U); b.expectedTesSequence.poke(7.U)
      b.tesLanguagePatchId.poke(123.U); b.gsTaskId.poke(501.U); b.gsInputPrimitiveId.poke(31.U)
      b.inputSequence.poke(99.U); b.firstOutputSequence.poke(((BigInt(1) << 63) + 17).U)
      b.instanceId.poke(5.U); b.baseInstance.poke(8.U); b.tesOutputComponents.poke(64.U)
      identity(g.identity); g.inputVertices.poke((if (point) 1 else if (domain == 0) 2 else 3).U)
      g.inputComponents.poke(components.U); g.invocations.poke(32.U); g.outputTopology.poke(2.U)
      g.maxVertices.poke(16.U); g.outputComponents.poke(64.U)
      g.vertexVaryingSlots.poke(16.U); g.fragmentVaryingSlots.poke(16.U); g.programVersion.poke(1.U)
      g.pc.poke(0x900004.U); g.code.base.poke(0x900000.U); g.code.limit.poke(0x900800.U)
      g.code.allocationBase.poke(0x900000.U); g.code.allocationLimit.poke(0x900800.U)
      g.uniforms.version.poke(2.U); g.uniforms.address.poke(0xa00000.U)
      for (c <- 0 until components) {
        g.inputSources(c).poke(expectedSources(c).U)
        b.evaluationComponents(c).valid.poke(true.B); b.evaluationComponents(c).component.poke(expectedPacked(c).U)
      }
      for (c <- 0 until 68) { g.outputSources(c).valid.poke(true.B); g.outputSources(c).bits.poke((c % 64).U) }
      q.tesTaskId.poke(41.U); q.tesSequence.poke(7.U); q.point.poke(point.B)
      for (v <- 0 until 3; c <- 0 until 64) q.vertices(v)(c).poke(word(v, c).U)
    }
    def accept(): Unit = {
      d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step()
      d.io.in.valid.poke(false.B); zero(d.io.in.bits)
    }
    def raw(point: Boolean = false, components: Int = 64): Unit = {
      until(d.io.raw.valid.peek().litToBoolean); d.io.fault.valid.expect(false.B)
      val out = d.io.raw.bits; val q = out.request; val b = q.primitive
      out.tesTaskId.expect(41.U); out.tesSequence.expect(7.U); out.tesLanguagePatchId.expect(123.U)
      out.firstOutputSequence.expect(((BigInt(1) << 63) + 17).U)
      q.taskId.expect(501.U); b.primitiveId.expect(31.U); b.sequence.expect(99.U)
      b.instanceId.expect(5.U); b.baseInstance.expect(8.U)
      val count = if (point) 1 else if (domain == 0) 2 else 3; b.vertexCount.expect(count.U)
      b.topology.expect((if (point) 0 else if (domain == 0) 1 else 4).U)
      if (components > 4) {
        // Independent literal: raw semantic4+4*13 is packed4, not raw56.
        assert(b.vertices(0).varyings(13)(0).peek().litValue == word(0, 4), "semantic56 must map packed4")
        b.vertices(0).varyings(14)(0).expect(word(0, 7).U)
        if (components == 64) b.vertices(0).varyings(15)(3).expect(word(0, 63).U)
      }
      for (v <- 0 until 6; s <- 0 until 68) {
        val dense = expectedSources.take(components).indexOf(s)
        val value = if (v < count && dense >= 0) word(v, expectedPacked(dense)) else BigInt(0)
        if (s < 4) b.vertices(v).position(s).expect(value.U)
        else b.vertices(v).varyings((s - 4) / 4)((s - 4) % 4).expect(value.U)
      }
      val held = out.peek().litValue;
      for (_ <- 0 until 4) { d.clock.step(); d.io.in.ready.expect(false.B); assert(out.peek().litValue == held) }
    }
    def transfer(point: Boolean = false, components: Int = 64): Unit = {
      d.io.allowTransfer.poke(true.B); d.clock.step(); d.io.allowTransfer.poke(false.B)
      until(d.io.task.valid.peek().litToBoolean); d.io.mapperFault.valid.expect(false.B)
      val t = d.io.task.bits.invocation; val count = if (point) 1 else if (domain == 0) 2 else 3
      t.taskId.expect(501.U); t.primitiveId.expect(31.U); t.inputVertices.expect(count.U);
      t.inputComponents.expect(components.U)
      for (v <- 0 until 6; c <- 0 until 64) {
        t.vertices(v)
          .components(c)
          .expect((if (v < count && c < components) word(v, expectedPacked(c)) else BigInt(0)).U)
      }
      for (v <- 0 until 6) { t.vertices(v).layer.expect(0.U); t.vertices(v).shaderPrimitiveId.expect(0.U) }
      val held = d.io.task.bits.peek().litValue; d.clock.step(3); assert(d.io.task.bits.peek().litValue == held)
      d.io.task.ready.poke(true.B); d.clock.step(); d.io.task.ready.poke(false.B)
    }
    def healthy(point: Boolean = false, components: Int = 64): Unit = {
      prepare(point, components); accept(); raw(point, components); transfer(point, components)
      d.io.busy.expect(false.B); d.io.in.ready.expect(true.B)
    }
    def fault(code: Int): Unit = {
      until(d.io.fault.valid.peek().litToBoolean)
      d.io.raw.valid.expect(false.B); val f = d.io.fault.bits; f.status.expect(code.U)
      f.tesTaskId.expect(41.U); f.tesSequence.expect(7.U); f.gsTaskId.expect(501.U); f.gsInputPrimitiveId.expect(31.U)
      val held = f.peek().litValue; d.clock.step(3); assert(f.peek().litValue == held); d.io.in.ready.expect(false.B)
      d.io.fault.ready.poke(true.B); d.clock.step(); d.io.fault.ready.poke(false.B); d.io.busy.expect(false.B)
    }
  }
  for (domain <- 0 until 3) {
    it should s"preserve domain$domain raw point and ordinary primitives through the real GNG mapper" in {
      simulate(new TessellationGeometryRawHarness(domain, p)) { d =>
        val x = new Driver(d, domain)
        for (point <- Seq(false, true); components <- Seq(0, 8, 64)) x.healthy(point, components)
      }
    }
  }
  it should "reject malformed mappings and retain cancellation offers before clean reuse" in {
    simulate(new TessellationGeometryRawHarness(0, p)) { d =>
      val x = new Driver(d, 0)
      for (
        problem <- Seq(
          "version",
          "domain",
          "task",
          "sequence",
          "identity",
          "exports",
          "adjacency",
          "mapvalid",
          "maprange",
          "highbits",
          "unusedvalid",
          "unuseddata",
          "overlap",
          "uniform"
        )
      ) {
        x.prepare(components = 8); val b = d.io.in.bits.link
        problem match {
          case "version"     => b.version.poke(0.U)
          case "domain"      => b.domain.poke(1.U)
          case "task"        => b.expectedTesTaskId.poke(42.U)
          case "sequence"    => b.expectedTesSequence.poke(8.U)
          case "identity"    => b.program.identity.context.poke(99.U)
          case "exports"     => b.tesOutputComponents.poke(65.U)
          case "adjacency"   => b.program.inputVertices.poke(4.U)
          case "mapvalid"    => b.evaluationComponents(4).valid.poke(false.B)
          case "maprange"    => b.evaluationComponents(4).component.poke(64.U)
          case "highbits"    => b.evaluationComponents(4).component.poke(BigInt("80000004", 16).U)
          case "unusedvalid" => b.evaluationComponents(8).valid.poke(true.B)
          case "unuseddata"  => b.evaluationComponents(8).component.poke(1.U)
          case "overlap"     => b.program.inputSources(5).poke(56.U)
          case "uniform"     => b.program.uniforms.version.poke(1.U)
        }
        x.accept(); x.fault(1)
      }
      x.prepare(components = 8, duplicate = true); x.accept(); x.raw(components = 8); x.transfer(components = 8)
      x.prepare(); d.io.in.bits.link.version.poke(0.U); d.io.cancel.poke(true.B); x.accept(); x.fault(1);
      d.io.cancel.poke(false.B)
      x.prepare(); d.io.cancel.poke(true.B); x.accept(); x.fault(4); d.io.cancel.poke(false.B)
      x.prepare(); x.accept(); d.clock.step(70); d.io.cancel.poke(true.B); x.fault(4); d.io.cancel.poke(false.B)
      x.prepare(); x.accept(); x.raw(); val held = d.io.raw.bits.peek().litValue
      d.io.cancel.poke(true.B); d.io.cancelPending.expect(true.B)
      d.clock.step(4); assert(d.io.raw.valid.peek().litToBoolean, "owed raw offer withdrawn by cancellation");
      assert(d.io.raw.bits.peek().litValue == held)
      d.io.cancel.poke(false.B); d.io.cancelPending.expect(true.B)
      x.transfer(); x.fault(4); x.healthy()
    }
  }
}
