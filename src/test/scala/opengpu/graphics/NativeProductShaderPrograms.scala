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

  /** Fragment ABI v1, two varyings (UV and RGBA), one FP32 color output.
    * Captured floating texture operands select nearest/repeat unit zero;
    * resource descriptors supply the image and sampler. Helpers run the same
    * program; the stage suppresses their pixel updates. No depth override.
    */
  def fragment(lanes: Int, warps: Int, textured: Boolean): Vector[BigInt] = {
    require(Set(4, 8, 16, 32)(lanes) && warps >= 1 && warps <= 32)
    val stride = 4 * lanes * warps
    val inputs = 14
    def load(array: Int, register: Int): Vector[BigInt] =
      address(5, 7, array * stride) :+ vectorMemory(5, register, false)
    def store(array: Int, register: Int): Vector[BigInt] =
      address(6, 7, (inputs + array) * stride) :+ vectorMemory(6, register, true)
    def broadcast(register: Int, scalar: Int): BigInt =
      (BigInt(0x17) << 26) | (BigInt(1) << 25) | (BigInt(scalar) << 15) |
        (BigInt(4) << 12) | (BigInt(register) << 7) | 0x57
    def param(pair: Int, a: Int, b: Int): BigInt =
      (BigInt(3) << 26) | (BigInt(1) << 25) | (BigInt(b) << 20) |
        (BigInt(a) << 15) | (BigInt(pair) << 12) | 0x2b
    val startup = Vector[BigInt](BigInt("00241393", 16),
      BigInt("007083b3", 16), BigInt("0104f057", 16))
    val coordinates = if (textured)
      load(6, 10) ++ load(7, 11) ++ Vector(broadcast(12, 0), param(0, 10, 11)) ++
        (1 until 8).map(p => param(p, 12, 12))
    else Vector.empty
    val colors = (0 until 4).flatMap { c =>
      val modulation = load(10 + c, 1)
      val sampled = if (textured) Vector(
        // Sample component c with image unit and implicit-LOD control zero.
        (BigInt(4) << 26) | (BigInt(1) << 25) | (BigInt(12) << 20) |
          (BigInt(12) << 15) | (BigInt(c) << 12) | (BigInt(2) << 7) | 0x2b,
        (BigInt(0x24) << 26) | (BigInt(1) << 25) | (BigInt(2) << 20) |
          (BigInt(1) << 15) | (BigInt(1) << 12) | (BigInt(1) << 7) | 0x57)
      else Vector.empty
      modulation ++ sampled ++ store(c, 1)
    }
    startup ++ coordinates ++ colors ++ Vector(addi(12, 0, 5), broadcast(1, 12)) ++
      store(5, 1) ++ Vector(BigInt("30500073", 16))
  }
}
