package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

object EmitExactClipper {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new NearClipStage(GraphicsConfig()),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

class ClipperExactDivisionSpec extends AnyFlatSpec {
  behavior of "Clipping with a shared iterative divider"
  private type Vertex = Vector[BigInt]
  private val signedFields = Set(0, 1, 2, 3, 7)
  private val byteFields = Set(4, 5, 6, 10)
  private def stored(value: BigInt, field: Int): BigInt = {
    val width = if (byteFields(field)) 8 else 32
    val bits = value & ((BigInt(1) << width) - 1)
    if (signedFields(field) && bits.testBit(31)) bits - (BigInt(1) << 32) else bits
  }
  private def distance(v: Vertex, plane: Int, wNear: Int): BigInt = plane match {
    case 0 => v(3) - wNear
    case 1 => v(0) + v(3)
    case 2 => v(3) - v(0)
    case 3 => v(1) + v(3)
    case 4 => v(3) - v(1)
    case 5 => v(2) + v(3)
    case 6 => v(3) - v(2)
  }
  // Independent integer Sutherland-Hodgman model. BigInt division truncates
  // toward zero; each newly stored field has the original interface width.
  private def reference(input: Vector[Vertex], wNear: Int): Vector[Vertex] = {
    var polygon = input
    for (plane <- 0 until 7 if polygon.size >= 3) {
      polygon = polygon.indices.flatMap { i =>
        val a = polygon((i + polygon.size - 1) % polygon.size)
        val b = polygon(i)
        val da = distance(a, plane, wNear); val db = distance(b, plane, wNear)
        if ((da >= 0) != (db >= 0)) {
          val intersection = a.indices.map { field =>
            stored(a(field) + (b(field) - a(field)) * da / (da - db), field)
          }.toVector
          if (db >= 0) Vector(intersection, b) else Vector(intersection)
        } else if (db >= 0) Vector(b) else Vector.empty
      }.toVector
    }
    if (polygon.size < 3) Vector.empty
    else (1 until polygon.size - 1).flatMap(i => Vector(polygon(0), polygon(i), polygon(i + 1))).toVector
  }
  private def write(port: ClipVertexColor, v: Vertex): Unit = {
    Seq(port.x, port.y, port.z, port.w, port.depth).zip(Seq(0, 1, 2, 3, 7))
      .foreach { case (p, i) => p.poke(v(i).S) }
    Seq(port.color.r, port.color.g, port.color.b, port.uv.u, port.uv.v, port.alpha)
      .zip(Seq(4, 5, 6, 8, 9, 10)).foreach { case (p, i) => p.poke(v(i).U) }
  }
  private def read(port: ClipVertexColor): Vertex = Vector(
    port.x.peek().litValue, port.y.peek().litValue, port.z.peek().litValue, port.w.peek().litValue,
    port.color.r.peek().litValue, port.color.g.peek().litValue, port.color.b.peek().litValue,
    port.depth.peek().litValue, port.uv.u.peek().litValue, port.uv.v.peek().litValue,
    port.alpha.peek().litValue)

  it should "match every clipped field, retain results, cancel on reset and reuse the engine" in {
    simulate(new NearClipStage(GraphicsConfig())) { dut =>
      dut.io.start.poke(false.B); dut.io.wNear.poke(1.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val random = new scala.util.Random(0xc11f)
      def vertex(x: Int, y: Int, z: Int, w: Int, index: Int): Vertex =
        Vector[BigInt](x, y, z, w, (index * 97) & 255, (index * 131) & 255,
          (index * 193) & 255, if (index % 2 == 0) Int.MinValue else Int.MaxValue,
          if (index % 2 == 0) BigInt("ffffffff", 16) else BigInt(1),
          BigInt("80000000", 16) + index, (index * 67) & 255)
      val inside = Vector(vertex(-10000, -10000, 0, 65536, 0),
        vertex(10000, -10000, 0, 65536, 1), vertex(0, 10000, 0, 65536, 2))
      val extremes = Vector(vertex(Int.MinValue, Int.MaxValue, Int.MinValue, 65536, 0),
        vertex(Int.MaxValue, Int.MinValue, Int.MaxValue, Int.MaxValue, 1),
        vertex(0, 0, 0, Int.MinValue, 2))
      val sides = Seq((-131072, 0, 0), (131072, 0, 0), (0, -131072, 0),
        (0, 131072, 0), (0, 0, -131072), (0, 0, 131072)).map { case (x, y, z) =>
        Vector(vertex(x, y, z, 65536, 0), inside(1), inside(2))
      }
      val cases = Vector((inside, 1), (extremes, 65535)) ++
        sides.map((_, 1)) ++ Vector.fill(12) {
          ((0 until 3).map(i => vertex(random.nextInt(393217) - 196608,
            random.nextInt(393217) - 196608, random.nextInt(393217) - 196608,
            random.nextInt(196609) - 65536, i)).toVector, 32768)
        }
      def launch(triangle: Vector[Vertex], near: Int): Unit = {
        triangle.zipWithIndex.foreach { case (v, i) => write(dut.io.tri(i), v) }
        dut.io.wNear.poke(near.U); dut.io.start.poke(true.B); dut.clock.step()
        dut.io.start.poke(false.B)
        // In-flight geometry is a snapshot, including unsigned attributes.
        dut.io.tri.foreach(p => write(p, inside.head))
      }
      var totalCycles = 0; var vertices = 0
      for (((triangle, near), job) <- cases.zipWithIndex) {
        launch(triangle, near)
        var cycles = 0
        while (dut.io.busy.peek().litToBoolean && cycles < 62000) {
          dut.io.start.poke((cycles % 173 == 0).B) // ignored while busy
          dut.clock.step(); cycles += 1
        }
        dut.io.start.poke(false.B)
        assert(cycles < 62000)
        val expected = reference(triangle, near)
        dut.io.done.expect(true.B); dut.io.outValid.expect((expected.size / 3).U)
        expected.zipWithIndex.foreach { case (v, i) => assert(read(dut.io.out(i)) == v, s"job=$job vertex=$i") }
        if (job == 0) assert(cycles == 28, s"fully-inside path gained cycles: $cycles")
        dut.clock.step(3)
        expected.zipWithIndex.foreach { case (v, i) => assert(read(dut.io.out(i)) == v) }
        totalCycles += cycles; vertices += expected.size
      }
      launch(extremes, 65535); dut.clock.step(30)
      dut.io.busy.expect(true.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.busy.expect(false.B); dut.io.done.expect(false.B); dut.io.outValid.expect(0.U)
      launch(inside, 1); dut.clock.step(28)
      dut.io.done.expect(true.B); dut.io.outValid.expect(1.U)
      reference(inside, 1).zipWithIndex.foreach { case (v, i) => assert(read(dut.io.out(i)) == v) }
      info(s"jobs=${cases.size} comparedFields=${vertices * 11} totalCycles=$totalCycles insideCycles=28 resetReuse=true")
    }
  }
}
