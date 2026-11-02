package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationQuadCuQualificationHarness(config: GpuConfig) extends Module {
  val gpu = Module(new GpuSystem(config, numComputeUnits = 1, commandIdWidth = 4, transactionsPerCu = 4,
    enableGraphicsKernels = true, geometryAvailable = true, instructionCacheSets = 4, instructionCacheWays = 1,
    vectorCacheSets = 4, vectorCacheWays = 1))
  val pipeline = Module(new TessellationQuadEvaluationPipeline)
  val executor = Module(new TessellationQuadCuEvaluator(config, 4, 1))
  val io = IO(new Bundle {
    val system = chiselTypeOf(gpu.io)
    val begin = Flipped(Decoupled(new TessellationQuadTask))
    val out = Decoupled(new EvaluatedQuadPrimitive); val completion = Decoupled(new QuadPipelineCompletion)
    val cancel = Input(Bool())
  })
  io.system <> gpu.io; pipeline.io.begin <> io.begin; io.out <> pipeline.io.out
  io.completion <> pipeline.io.completion; pipeline.io.cancel := io.cancel
  executor.io.request <> pipeline.io.request; pipeline.io.response <> executor.io.response
  executor.io.cancel := io.cancel; executor.io.count := 0.U; executor.io.selections := 0.U.asTypeOf(executor.io.selections)
  executor.io.commandId := 3.U; executor.io.kernel := 0.U.asTypeOf(executor.io.kernel)
  executor.io.kernel.kernelPc := 0x1000.U; executor.io.outputComponents := 3.U
  executor.io.inspect.ready := true.B; executor.io.reply.valid := false.B; executor.io.reply.bits := 0.U.asTypeOf(executor.io.reply.bits)
  val g = gpu.io.graphicsKernels.get
  g.launch <> executor.io.launch; g.geometryTask.get := executor.io.geometryTask
  executor.io.kernelCompletion <> g.completion; executor.io.event <> g.geometryEvent.get
  executor.io.owner := g.owned; executor.io.taskIds := g.taskIds; g.geometryCancel.get := executor.io.cancelCu
}

class TessellationQuadCuQualificationSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def geometry(fn: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt =
    (BigInt(fn) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(rd) << 7) | 0x0b
  it should "execute quad triangle and point vertices on a real shared CU" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new TessellationQuadCuQualificationHarness(config)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val sys = d.io.system; sys.graphicsDrained.poke(true.B); sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B)
      val program = Vector(BigInt("00000093",16), BigInt("00000113",16), geometry(32,3,1,2),
        BigInt("00000213",16), geometry(33,0,4,3), BigInt("00100113",16), geometry(32,3,1,2),
        BigInt("00100213",16), geometry(33,0,4,3), BigInt("00200113",16), geometry(32,3,1,2),
        BigInt("00200213",16), geometry(33,0,4,3), geometry(35), geometry(36), BigInt("30500073",16))
      val code = program.zipWithIndex.map { case (word, i) => word << (32 * i) }.sum
      val zeroWord = BigInt(0); val oneWord = BigInt("3f800000",16)
      def run(points: Boolean, level: BigInt): Unit = {
        val t = d.io.begin.bits; t.taskId.poke(19.U); t.spacing.poke(0.U)
        for (i <- 0 until 4) t.outer(i).poke(level.U)
        for (i <- 0 until 2) t.inner(i).poke(level.U)
        t.pointMode.poke(points.B); t.clockwise.poke(false.B)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        val expected = if (points) Vector(Vector((zeroWord,zeroWord)), Vector((oneWord,zeroWord)),
          Vector((oneWord,oneWord)), Vector((zeroWord,oneWord)))
        else Vector(Vector((zeroWord,zeroWord),(oneWord,zeroWord),(oneWord,oneWord)),
          Vector((zeroWord,zeroWord),(oneWord,oneWord),(zeroWord,oneWord)))
        val pending = mutable.Queue.empty[(BigInt, Int)]
        val observed = mutable.ArrayBuffer.empty[Vector[(BigInt, BigInt)]]
        val expectedCount = if (level == oneWord) expected.size else if (points) 9 else 8
        var cycle = 0; var published = 0
        while (!d.io.completion.valid.peek().litToBoolean && cycle < 200000) {
          sys.memoryRequest.ready.poke((cycle % 3 != 0).B)
          val reply = pending.headOption.filter(_._2 <= cycle); sys.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach(r => { sys.memoryResponse.bits.transactionId.poke(r._1.U); sys.memoryResponse.bits.readData.poke(code.U) })
          if (reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
          if (sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean) {
            sys.memoryRequest.bits.address.expect(0x1000.U)
            pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue, cycle + 3))
          }
          sys.graphicsKernels.get.trap.valid.expect(false.B)
          val ready = cycle % 4 != 0; d.io.out.ready.poke(ready.B)
          if (ready && d.io.out.valid.peek().litToBoolean) {
            val q = d.io.out.bits; q.taskId.expect(19.U); q.sequence.expect(published.U); q.point.expect(points.B)
            val vertices = (0 until (if (points) 1 else 3)).map { vertex =>
              q.vertices(vertex)(2).expect(0.U)
              (q.vertices(vertex)(0).peek().litValue, q.vertices(vertex)(1).peek().litValue)
            }.toVector
            if (level == oneWord) assert(vertices == expected(published))
            observed += vertices; published += 1
          }
          d.clock.step(); cycle += 1
        }
        assert(cycle < 200000 && pending.isEmpty && published == expectedCount)
        d.io.completion.bits.status.expect(0.U); d.io.completion.bits.generated.expect(expectedCount.U)
        d.io.completion.bits.published.expect(expectedCount.U); d.io.completion.ready.poke(true.B)
        d.clock.step(); d.io.completion.ready.poke(false.B); d.io.out.ready.poke(false.B)
        if (level != oneWord) {
          val scale = BigInt(1) << 24
          def fixed(bits: BigInt): BigInt = BigInt((java.lang.Float.intBitsToFloat(bits.toInt).toDouble * scale.toDouble).toLong)
          if (points) {
            val set = observed.map(t => (fixed(t.head._1), fixed(t.head._2))).toSet
            assert(set == (for (u <- 0 to 2; v <- 0 to 2) yield (scale * u / 2, scale * v / 2)).toSet)
          } else {
            val areas = observed.map { t =>
              val a=(fixed(t(0)._1),fixed(t(0)._2)); val b=(fixed(t(1)._1),fixed(t(1)._2)); val c=(fixed(t(2)._1),fixed(t(2)._2))
              (b._1-a._1)*(c._2-a._2) - (b._2-a._2)*(c._1-a._1)
            }
            assert(areas.forall(_ > 0) && areas.sum == 2 * scale * scale)
          }
        }
      }
      run(points = false, level = oneWord); run(points = true, level = oneWord)
      run(points = false, level = BigInt("40000000",16)); run(points = true, level = BigInt("40000000",16))
    }
  }
}
