package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics.tessellation._
import opengpu.system._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Original VS execution and an actual patch SRAM, without native admission, TCS/TES execution or a claim of
  * whole-owner retirement qualification.
  */
private class TessellationVertexPatchSourceHarness extends Module {
  val config = GpuConfig(lanes = 16, warps = 8, l2Sets = 8, l2Ways = 2)
  val p = VertexShaderStageConfig(VertexInputFetchConfig(1, 1), 1)
  private val slots = GpuSystem.totalMemoryTransactions(2, 4, 4)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationVertexPatchTask(p)))
    val completion = Decoupled(new TessellationVertexPatchCompletion)
    val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val inspected = Decoupled(new TessellationPatchReply)
    val end = Flipped(Decoupled(Bool()))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, slots))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, slots)))
    val cancel = Input(Bool()); val sealedInput = Output(Bool()); val patchBusy = Output(Bool())
    val sourceBusy = Output(Bool()); val launched = Output(Bool())
  })
  private val source = Module(new TessellationVertexPatchSource(config, p, SharedCuShaderConfig(2)))
  private val store = Module(new TessellationPatchStore(zeroOnBegin = true))
  private val system = Module(
    new GpuSystem(
      config,
      numComputeUnits = 2,
      enableGraphicsKernels = true,
      graphicsHostTransactions = 4,
      instructionCacheSets = 4,
      instructionCacheWays = 1,
      vectorCacheSets = 4,
      vectorCacheWays = 1
    )
  )
  private def tie(data: Data): Unit = data match {
    case r: Record                                                     => r.elements.values.foreach(tie)
    case v: Vec[_]                                                     => v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b := false.B
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s := 0.S
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u := 0.U
    case _                                                             => ()
  }
  tie(system.io)
  system.io.graphicsKernels.get <> source.io.shared
  system.io.graphicsHostRequest <> source.io.memoryRequest
  source.io.memoryResponse <> system.io.graphicsHostResponse
  system.io.graphicsDrained := !source.io.busy
  system.io.graphicsShaderL1Invalidate.ready := true.B
  io.memoryRequest <> system.io.memoryRequest; system.io.memoryResponse <> io.memoryResponse
  source.io.cancel := io.cancel
  io.sourceBusy := source.io.busy; io.launched := source.io.shared.launch.fire

  private object State extends ChiselEnum { val idle, opening, start, running, inspect = Value }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationVertexPatchTask(p))
  private val sealedInput = RegInit(false.B)
  private val inspectionReply = RegInit(false.B)
  store.io.begin.valid := state === State.idle && io.task.valid
  store.io.begin.bits := io.task.bits.layout
  io.task.ready := state === State.idle && store.io.begin.ready
  store.io.beginResult.ready := state === State.opening
  source.io.task.valid := state === State.start; source.io.task.bits := task
  io.completion.valid := state === State.running && source.io.completion.valid
  io.completion.bits := source.io.completion.bits
  source.io.completion.ready := state === State.running && io.completion.ready

  store.io.access.valid := Mux(state === State.inspect, io.inspect.valid, source.io.load.valid)
  store.io.access.bits := Mux(state === State.inspect, io.inspect.bits, source.io.load.bits)
  source.io.load.ready := state === State.running && store.io.access.ready
  io.inspect.ready := state === State.inspect && store.io.access.ready
  when(store.io.access.fire) { inspectionReply := state === State.inspect }
  source.io.reply.valid := store.io.reply.valid && !inspectionReply
  source.io.reply.bits := 0.U.asTypeOf(source.io.reply.bits)
  source.io.reply.bits.result := store.io.reply.bits
  io.inspected.valid := store.io.reply.valid && inspectionReply
  io.inspected.bits := store.io.reply.bits
  store.io.reply.ready := Mux(inspectionReply, io.inspected.ready, source.io.reply.ready)
  source.io.seal.ready := state === State.running && store.io.access.ready && !source.io.load.valid
  when(source.io.seal.fire) { sealedInput := true.B }
  io.sealedInput := sealedInput; io.patchBusy := store.io.busy
  store.io.end.valid := state === State.inspect && io.end.valid
  store.io.end.bits := io.end.bits
  io.end.ready := state === State.inspect && store.io.end.ready
  switch(state) {
    is(State.idle) { when(io.task.fire) { task := io.task.bits; sealedInput := false.B; state := State.opening } }
    is(State.opening) {
      when(store.io.beginResult.fire) { assert(store.io.beginResult.bits === 0.U); state := State.start }
    }
    is(State.start) { when(source.io.task.fire) { state := State.running } }
    is(State.running) { when(io.completion.fire) { state := State.inspect } }
    is(State.inspect) { when(io.end.fire) { state := State.idle } }
  }
  when(source.io.seal.fire) { assert(!source.io.cancelPatch) }
}

class TessellationVertexPatchSourceSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private def addi(rd: Int, rs: Int, imm: Int): BigInt =
    (BigInt(imm & 4095) << 20) | (BigInt(rs) << 15) | (BigInt(rd) << 7) | 0x13
  private def add(rd: Int, a: Int, b: Int): BigInt = (BigInt(b) << 20) | (BigInt(a) << 15) | (BigInt(rd) << 7) | 0x33
  private def address(rd: Int, base: Int, offset: Int): Seq[BigInt] = {
    val hi = (offset + 2048) >> 12; val lo = offset - (hi << 12)
    if (hi == 0) Seq(addi(rd, base, lo))
    else Seq((BigInt(hi) << 12) | (BigInt(rd) << 7) | 0x37, add(rd, base, rd), addi(rd, rd, lo))
  }
  private def vectorMemory(base: Int, reg: Int, write: Boolean): BigInt =
    (BigInt(1) << 25) | (BigInt(base) << 15) | (BigInt(6) << 12) | (BigInt(reg) << 7) | (if (write) 0x27 else 7)
  private def program: Seq[BigInt] = {
    val stride = 16 * 8 * 4
    val code = mutable.ArrayBuffer[BigInt](
      (BigInt(2) << 20) | (BigInt(8) << 15) | (BigInt(1) << 12) | (BigInt(7) << 7) | 0x13,
      add(7, 1, 7),
      (BigInt(3) << 30) | (BigInt(0x10) << 20) | (BigInt(16) << 15) | (BigInt(7) << 12) | 0x57
    )
    // Copy raw attribute position and original IDs; no interpolated stand-in.
    for ((in, out) <- Seq(0 -> 7, 1 -> 8, 2 -> 9, 3 -> 10, 5 -> 11, 4 -> 12, 6 -> 13, 0 -> 14)) {
      code ++= address(5, 7, in * stride); code += vectorMemory(5, 1, false)
      code ++= address(6, 7, out * stride); code += vectorMemory(6, 1, true)
    }
    code += BigInt("30500073", 16); code.toVector
  }
  it should "execute sliced VS inputs on two physical CUs and acknowledge each word into a retained patch before seal and reuse" in {
    simulate(new TessellationVertexPatchSourceHarness) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val memory = new Memory(
        d.io.memoryRequest.bits,
        d.io.memoryResponse.bits,
        d.io.memoryRequest.valid,
        d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,
        d.io.memoryResponse.ready
      )
      program.zipWithIndex.foreach { case (w, i) => memory.putWord(0x1000 + 4 * i, w) }
      def attr(v: Int, c: Int): BigInt = BigInt(0x3f000000L + v * 256 + c)
      for (v <- 0 until 16; c <- 0 until 4) memory.putWord(0x4000 + v * 16 + c * 4, attr(v, c))
      var launches = 0
      def tick(): Unit = {
        memory.drive(); memory.capture(); if (d.io.launched.peek().litToBoolean) launches += 1; d.clock.step()
      }
      def until(done: => Boolean): Unit = {
        var n = 0; while (!done && n < 30000) { tick(); n += 1 }; assert(done, s"VS to patch stalled at $n")
      }
      def view(v: VertexInputView, base: Int, bytes: Int): Unit = {
        v.base.poke(base.U); v.limit.poke((base + bytes).U); v.allocationBase.poke((base & ~63).U)
        v.allocationLimit.poke(((base + bytes + 63) & ~63).U)
      }
      for ((first, count, instance) <- Seq((2, 3, 3), (1, 2, 4))) {
        val t = d.io.task.bits; zero(t); t.version.poke(1.U); t.taskId.poke(77.U)
        t.layout.inputVertices.poke(count.U); t.layout.inputComponents.poke(8.U)
        t.layout.outputVertices.poke(count.U); t.layout.outputComponents.poke(4.U)
        t.slice.version.poke(1.U); t.slice.firstOrdinal.poke(first.U); t.slice.count.poke(count.U)
        t.slice.firstInstance.poke(instance.U); t.slice.instances.poke(1.U)
        val draw = t.draw; draw.version.poke(1.U); draw.shaderPc.poke(0x1000.U)
        draw.input.version.poke(1.U); identity(draw.input.identity)
        draw.input.count.poke(8.U); draw.input.instances.poke(6.U)
        draw.input.firstVertex.poke(1.U); draw.input.baseInstance.poke(2.U)
        view(draw.input.bindings(0), 0x4000, 256); draw.input.bindings(0).stride.poke(16.U)
        draw.input.attributes(0).enabled.poke(true.B); draw.input.attributes(0).components.poke(4.U)
        draw.input.attributes(0).format.poke(VertexInputFormat.float32.U)
        view(draw.scratch, 0x100000, d.p.prefixBytes(128, 1, 1))
        draw.attributeSlots.poke(1.U); draw.varyingSlots.poke(1.U); draw.varyingMask.poke(15.U)
        draw.sampler.texWidth.poke(1.U); draw.sampler.texHeight.poke(1.U)
        for (c <- 0 until 8) {
          t.components(c).kind.poke((if (c < 4) 0 else 1).U)
          t.components(c).component.poke((c % 4).U)
        }
        d.io.task.valid.poke(true.B); until(d.io.task.ready.peek().litToBoolean); tick()
        d.io.task.valid.poke(false.B); zero(t)
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(0.U); d.io.completion.bits.taskId.expect(77.U)
        d.io.completion.bits.vertices.expect(count.U); d.io.completion.bits.words.expect((8 * count).U)
        d.io.completion.bits.source.status.expect(0.U); d.io.completion.bits.source.batches.expect(1.U)
        d.io.completion.bits.source.localBatches.expect(0.U); d.io.completion.bits.source.spilledBatches.expect(0.U)
        d.io.completion.bits.source.restarts.expect(0.U)
        d.io.sealedInput.expect(true.B); d.io.patchBusy.expect(true.B); d.io.sourceBusy.expect(true.B)
        val held = d.io.completion.bits.peek().litValue
        for (_ <- 0 until 5) {
          tick(); assert(d.io.completion.bits.peek().litValue == held); d.io.task.ready.expect(false.B)
        }
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
        d.io.sourceBusy.expect(false.B); d.io.patchBusy.expect(true.B)
        for (v <- 0 until count; c <- 0 until 8) {
          val vertexId = 1 + first + v
          val expected =
            if (c < 4) attr(vertexId, c)
            else Vector(BigInt(instance), BigInt(vertexId), BigInt(2), attr(vertexId, 0))(c - 4)
          zero(d.io.inspect.bits); d.io.inspect.bits.vertex.poke(v.U); d.io.inspect.bits.component.poke(c.U)
          d.io.inspect.valid.poke(true.B); until(d.io.inspect.ready.peek().litToBoolean); tick();
          d.io.inspect.valid.poke(false.B)
          until(d.io.inspected.valid.peek().litToBoolean)
          d.io.inspected.bits.status.expect(0.U); d.io.inspected.bits.data.expect(expected.U)
          for (_ <- 0 until 2) {
            tick(); d.io.inspected.valid.expect(true.B); d.io.inspected.bits.data.expect(expected.U)
          }
          d.io.inspected.ready.poke(true.B); tick(); d.io.inspected.ready.poke(false.B)
        }
        assert(memory.pending.isEmpty && memory.reply.isEmpty)
        d.io.end.valid.poke(true.B); until(d.io.end.ready.peek().litToBoolean); tick(); d.io.end.valid.poke(false.B)
        d.io.patchBusy.expect(false.B)
      }
      assert(launches == 2, "original VS work did not execute exactly once per patch")
    }
  }
}
