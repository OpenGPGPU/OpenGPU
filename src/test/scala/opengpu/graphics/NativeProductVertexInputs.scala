package opengpu.graphics

/** GPU-fetchable, non-indexed triangle lists for the product recipes.
  * This is input preparation only; it never substitutes CPU-shaded pixels for
  * shader execution. Each vertex carries clip position, UV and draw modulation.
  */
object NativeProductVertexInputs {
  val strideBytes: Int = 40
  final case class Upload(base: BigInt, bytes: Vector[Byte], fields: Map[String, BigInt]) {
    val limit: BigInt = fields("draw.vertex.geometry.vertex.input.bindings[0].limit")
  }

  def encode(draw: NativeProductWorkloads.Draw, base: BigInt): Upload = {
    val vertices = draw.triangles.flatMap(t => Vector(t.a, t.b, t.c))
    val length = vertices.size.toLong * strideBytes
    val allocationBytes = (length + 63) & ~63L
    require(base >= 0 && base % 64 == 0 && base < (BigInt(1) << 32),
      "vertex allocation base must be a line-aligned uint32 address")
    require(allocationBytes <= Int.MaxValue && base + allocationBytes <= (BigInt(1) << 32),
      "vertex upload must fit its backing vector and the GPU address space")
    val bytes = vertices.flatMap { v =>
      (Vector(v.x, v.y, v.z, v.w, v.u, v.v) ++ draw.rgba).flatMap { value =>
        val bits = java.lang.Float.floatToRawIntBits(value)
        Vector.tabulate(4)(i => ((bits >>> (8 * i)) & 255).toByte)
      }
    }
    val prefix = "draw.vertex.geometry.vertex.input."
    var fields = Map[String, BigInt](
      prefix + "version" -> 1, prefix + "count" -> vertices.size,
      prefix + "instances" -> 1, prefix + "indexFormat" -> 0,
      prefix + "bindings[0].base" -> base,
      prefix + "bindings[0].limit" -> (base + length),
      prefix + "bindings[0].allocationBase" -> base,
      prefix + "bindings[0].allocationLimit" -> (base + allocationBytes),
      prefix + "bindings[0].stride" -> strideBytes,
      prefix + "bindings[0].divisor" -> 0)
    for ((offset, components, slot) <- Vector((0, 4, 0), (16, 2, 1), (24, 4, 2))) {
      val key = prefix + s"attributes[$slot]."
      fields ++= Map[String, BigInt](key + "enabled" -> 1,
        key + "bindingIndex" -> 0, key + "offset" -> offset,
        key + "components" -> components, key + "format" -> VertexInputFormat.float32)
    }
    Upload(base, bytes.padTo(allocationBytes.toInt, 0.toByte), fields)
  }
}
