package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class NativeProductVertexInputsSpec extends AnyFlatSpec {
  behavior of "GPU vertex inputs for product scenes"

  it should "fetch every recipe's ordered position UV and modulation from descriptor addresses" in {
    for (scene <- NativeProductWorkloads.suite(32, 32); draw <- scene.draws) {
      val upload = NativeProductVertexInputs.encode(draw, 0x10000)
      val prefix = "draw.vertex.geometry.vertex.input."
      val fields = upload.fields
      def field(key: String): BigInt = fields(prefix + key)
      val vertices = draw.triangles.flatMap(t => Vector(t.a, t.b, t.c))
      assert(field("count") == vertices.size && field("instances") == 1)
      assert(field("indexFormat") == 0 && field("bindings[0].divisor") == 0)
      assert(field("bindings[0].limit") == upload.limit)
      assert(field("bindings[0].allocationLimit") == upload.base + upload.bytes.size)
      assert(field("bindings[0].allocationLimit") % 64 == 0)
      assert(upload.bytes.drop((upload.limit - upload.base).toInt).forall(_ == 0))
      assert(field("bindings[0].allocationBase") == upload.base)
      for ((vertex, index) <- vertices.zipWithIndex;
           (expected, slot) <- Vector(Vector(vertex.x, vertex.y, vertex.z, vertex.w),
             Vector(vertex.u, vertex.v), draw.rgba).zipWithIndex) {
        val attr = s"attributes[$slot]."
        assert(field(attr + "enabled") == 1 && field(attr + "format") == VertexInputFormat.float32)
        assert(field(attr + "components") == expected.size)
        val binding = s"bindings[${field(attr + "bindingIndex")}]."
        val address = field(binding + "base") + index * field(binding + "stride") + field(attr + "offset")
        for ((value, component) <- expected.zipWithIndex) {
          val offset = (address - upload.base).toInt + component * 4
          val bits = (0 until 4).foldLeft(0)((n, b) => n | ((upload.bytes(offset + b) & 255) << (8 * b)))
          assert(bits == java.lang.Float.floatToRawIntBits(value), s"${scene.id} vertex=$index slot=$slot component=$component")
        }
      }
    }
  }

  it should "permit a legal 4GiB end and reject wrapped or misaligned fetch views" in {
    val draw = NativeProductWorkloads.scene("textured-3d", 32, 32).draws.head
    val size = draw.triangles.size * 3 * NativeProductVertexInputs.strideBytes
    val end = BigInt(1) << 32
    val padded = (size + 63) & ~63
    val upload = NativeProductVertexInputs.encode(draw, end - padded)
    assert(upload.base + upload.bytes.size == end && upload.limit <= end)
    for (base <- Seq(BigInt(-64), BigInt(1), BigInt(4), end, end - padded + 64))
      intercept[IllegalArgumentException] { NativeProductVertexInputs.encode(draw, base) }
  }
}
