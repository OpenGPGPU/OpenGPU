package opengpu.graphics

/** Executable CU programs for the product input ABI. No CPU-shaded exports. */
object NativeProductShaderPrograms {
  val vertexAttributes: Int = 3
  val vertexVaryings: Int = 2
  val vertexVaryingMask: Int = 255

  private def addi(rd: Int, rs: Int, value: Int): BigInt =
    (BigInt(value & 4095) << 20) | (BigInt(rs) << 15) | (BigInt(rd) << 7) | 0x13
  private def address(rd: Int, base: Int, offset: Int): Vector[BigInt] = {
    if (offset >= -2048 && offset < 2048) Vector(addi(rd, base, offset))
    else {
      val high = (offset + 2048) >> 12
      val low = offset - (high << 12)
      Vector((BigInt(high & 0xfffff) << 12) | (BigInt(rd) << 7) | 0x37,
        addi(rd, rd, low),
        (BigInt(base) << 20) | (BigInt(rd) << 15) | (BigInt(rd) << 7) | 0x33)
    }
  }
  private def vectorMemory(base: Int, register: Int, write: Boolean): BigInt =
    (BigInt(1) << 25) | (BigInt(base) << 15) | (BigInt(6) << 12) |
      (BigInt(register) << 7) | (if (write) 0x27 else 7)

  /** VertexShaderStage ABI v1: 12 attributes + 3 built-in input arrays,
    * followed by four position arrays and eight varying arrays. The UV
    * attribute's default Z/W values are retained, followed by RGBA modulation.
    */
  def vertex(lanes: Int, warps: Int): Vector[BigInt] = {
    require(Set(4, 8, 16, 32)(lanes) && warps >= 1 && warps <= 32)
    val stride = 4 * lanes * warps
    val inputs = 4 * vertexAttributes + 3
    val startup = Vector[BigInt](BigInt("00241393", 16),
      BigInt("007083b3", 16), BigInt("0104f057", 16))
    startup ++ (0 until 12).flatMap { component =>
      address(5, 7, component * stride) ++ Vector(vectorMemory(5, 1, false)) ++
        address(6, 7, (inputs + component) * stride) ++
        Vector(vectorMemory(6, 1, true))
    } ++ Vector(BigInt("30500073", 16))
  }
}
