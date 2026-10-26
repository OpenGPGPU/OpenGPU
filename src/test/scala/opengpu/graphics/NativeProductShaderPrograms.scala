package opengpu.graphics

/** Native version1 SoA shaders for the product scene inputs. Machine-code
  * generation only; execution on the full native host is separately qualified.
  */
object NativeProductShaderPrograms {
  private def addi(rd: Int, rs: Int, n: Int): BigInt =
    (BigInt(n & 4095) << 20) | (BigInt(rs) << 15) | (BigInt(rd) << 7) | 0x13
  private def address(rd: Int, base: Int, n: Int): Vector[BigInt] = {
    if (n >= -2048 && n < 2048) Vector(addi(rd, base, n))
    else {
      val high = (n + 2048) >> 12
      Vector((BigInt(high & 0xfffff) << 12) | (BigInt(rd) << 7) | 0x37,
        addi(rd, rd, n - (high << 12)),
        (BigInt(base) << 20) | (BigInt(rd) << 15) | (BigInt(rd) << 7) | 0x33)
    }
  }
  private def constant(rd: Int, raw: BigInt): Vector[BigInt] = {
    val n = raw & 0xffffffffL
    Vector((((n + 2048) >> 12) & 0xfffff) << 12 | (BigInt(rd) << 7) | 0x37,
      addi(rd, rd, if ((n & 4095) >= 2048) (n & 4095).toInt - 4096 else (n & 4095).toInt))
  }
  private def vm(base: Int, v: Int, store: Boolean): BigInt =
    (BigInt(1) << 25) | (BigInt(base) << 15) | (BigInt(6) << 12) |
      (BigInt(v) << 7) | (if (store) 0x27 else 7)
  private def broadcast(v: Int, scalar: Int): BigInt =
    (BigInt(0x17) << 26) | (BigInt(1) << 25) | (BigInt(scalar) << 15) |
      (BigInt(4) << 12) | (BigInt(v) << 7) | 0x57
  private val start = Vector[BigInt](0x00241393, 0x007083b3, 0x0104f057)
  val cease: BigInt = 0x30500073
  private def stride(capacity: Int): Int = {
    require(capacity > 0 && capacity <= 128)
    capacity * 4
  }
  def vertexPrefixBytes(capacity: Int): Int = 19 * stride(capacity)
  def fragmentPrefixBytes(capacity: Int): Int = 16 * stride(capacity)

  /** Two vec4 attributes: position and UV/padding. Export one vec4 varying. */
  def vertex(capacity: Int): Vector[BigInt] = {
    val s = stride(capacity)
    start ++ (0 until 8).flatMap { i =>
      address(5, 7, i * s) ++ Vector(vm(5, 1, false)) ++
        address(6, 7, (11 + i) * s) ++ Vector(vm(6, 1, true))
    } :+ cease
  }

  /** One RGBA output, no depth override, color0 enabled. Four scalar FP32
    * modulation words follow the private prefix in each engine scratch view.
    * Textured draws capture interpolated UV, use binding0/explicit LOD0 and
    * sample all four components; untextured draws multiply opaque white.
    */
  def fragment(capacity: Int, textured: Boolean): Vector[BigInt] = {
    val s = stride(capacity)
    val code = scala.collection.mutable.ArrayBuffer.empty[BigInt]; code ++= start
    if (textured) {
      code ++= address(5, 7, 6 * s); code += vm(5, 10, false)
      code ++= address(5, 7, 7 * s); code += vm(5, 11, false)
      for (pair <- 0 until 8) {
        if (pair != 0) { code += broadcast(10, 0); code += broadcast(11, 0) }
        code += (BigInt(3) << 26) | (BigInt(1) << 25) | (BigInt(11) << 20) |
          (BigInt(10) << 15) | (BigInt(pair) << 12) | 0x2b
      }
      code += broadcast(5, 0); code += addi(14, 0, 1); code += broadcast(6, 14)
    }
    for (c <- 0 until 4) {
      if (textured) code += (BigInt(4) << 26) | (BigInt(1) << 25) |
        (BigInt(6) << 20) | (BigInt(5) << 15) | (BigInt(c) << 12) | (BigInt(7) << 7) | 0x2b
      else { code ++= constant(13, 0x3f800000); code += broadcast(7, 13) }
      code ++= address(13, 1, fragmentPrefixBytes(capacity) + c * 4)
      code += (BigInt(13) << 15) | (BigInt(2) << 12) | (BigInt(13) << 7) | 3
      code += broadcast(8, 13)
      code += (BigInt(0x24) << 26) | (BigInt(1) << 25) | (BigInt(7) << 20) |
        (BigInt(8) << 15) | (BigInt(1) << 12) | (BigInt(9) << 7) | 0x57
      code ++= address(6, 7, (10 + c) * s); code += vm(6, 9, true)
    }
    code += broadcast(7, 0); code ++= address(6, 7, 14 * s); code += vm(6, 7, true)
    code += addi(13, 0, 5); code += broadcast(7, 13)
    code ++= address(6, 7, 15 * s); code += vm(6, 7, true)
    code += cease; code.toVector
  }
}
