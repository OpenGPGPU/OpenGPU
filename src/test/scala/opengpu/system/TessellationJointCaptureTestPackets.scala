package opengpu.system

/** Independent committed ABI catalogs encode metadata only. These packets contain no shader instructions, predicted VS
  * results or patch contents.
  */
private[system] object TessellationJointCaptureTestPackets {
  def wide(w: Array[BigInt], at: Int, value: BigInt): Unit = { w(at) = value & 0xffffffffL; w(at + 1) = value >> 32 }
  private def header(magic: BigInt, bytes: Int, version: Int): Array[BigInt] = {
    val w = Array.fill[BigInt](bytes / 4)(0)
    Seq(magic, BigInt(bytes), BigInt(version), BigInt(16), BigInt(16), BigInt(16), BigInt(4), BigInt(4)).zipWithIndex
      .foreach { case (v, i) => w(i) = v };
    w
  }
  private val frameFields = {
    val s = scala.io.Source.fromFile("docs/abi/native-binned-graphics-v1.json")
    val json =
      try ujson.read(s.mkString)
      finally s.close()
    json("frame")("fields").arr.map(f => f("name").str -> (f("word").num.toInt, f("bits").num.toInt)).toMap
  }
  def frameSet(w: Array[BigInt], name: String, v: BigInt): Unit = {
    val (word, bits) = frameFields("frame." + name); require(v >= 0 && v < (BigInt(1) << bits))
    for (i <- 0 until (bits + 31) / 32) w(word + i) = (v >> (32 * i)) & 0xffffffffL
  }
  def frame(empty: Boolean = false): Array[BigInt] = {
    val w = header(BigInt("474e4601", 16), 2048, 1)
    def set(n: String, v: BigInt): Unit = frameSet(w, n, v)
    for (
      prefix <- Seq(
        "bins.bins.pool.identity",
        "bins.producer.arena.identity",
        "image.pixels.colors.identity",
        "states.identity"
      )
    ) {
      set(prefix + ".context", 7); set(prefix + ".pass", 8); set(prefix + ".generation", 9)
    }
    set("version", 1); set("bins.bins.version", 3); set("bins.bins.tileCount", 1)
    set("bins.bins.directoryBase", 0x100000); set("bins.bins.directoryLimit", 0x110000)
    set("bins.bins.pool.version", 2); set("bins.bins.pool.base", 0x120000)
    set("bins.bins.pool.initialLimit", 0x121000); set("bins.bins.pool.limit", 0x130000)
    set("bins.bins.pool.pageSizeLog2", 12)
    set("bins.producer.arena.version", 1); set("bins.producer.arena.base", 0x140000);
    set("bins.producer.arena.limit", 0x150000)
    set("bins.producer.version", 1); set("bins.producer.screenWidth", 4); set("bins.producer.screenHeight", 4)
    set("bins.producer.tileLog2", 3); set("image.version", 1); set("image.pixels.version", 1)
    set("image.pixels.colors.version", 1); set("image.pixels.colors.tileLog2", 3)
    set("image.pixels.colors.tileWidth", 4); set("image.pixels.colors.tileHeight", 4)
    set("image.pixels.colors.sampleMode", 2); set("image.pixels.colors.reservedBytes", 4096)
    for (i <- 0 until 4) {
      val c = s"image.pixels.colors.colors[$i]"; set(c + ".enabled", 1); set(c + ".channelMask", 15)
      set(c + ".format", 35); set(c + ".loadOp", 1); set(c + ".offset", i * 1024)
      for (k <- 0 until 4) set(c + s".clear[$k]", BigInt("3f800000", 16) + i * 4 + k)
      val v = s"image.views[$i]"; val base = BigInt(0x200000 + i * 0x10000)
      set(v + ".allocationBase", base); set(v + ".allocationLimit", base + 4096)
      set(v + ".base", base); set(v + ".limit", base + 1024); set(v + ".imageWidth", 4); set(v + ".imageHeight", 4)
      set(v + ".rowPitch", 256); set(v + ".store", 1); set(v + ".writable", 1)
    }
    set("states.version", 1); if (!empty) { set("states.base", 0x160000); set("states.limit", 0x160200) }
    set("scratchStride", 65536)
    for (i <- 0 until 4) {
      set(s"scratch[$i].base", 0x180000 + i * 65536); set(s"scratch[$i].limit", 0x190000 + i * 65536)
    }
    w
  }
  def sequence(domain: Int): Array[BigInt] = {
    val w = Array.fill[BigInt](512)(0); val s = if (domain == 0) 0 else 1
    Seq(
      0 -> (BigInt("474e5407", 16) + domain),
      1 -> BigInt(2048),
      2 -> BigInt(7 + domain),
      16 -> BigInt(50),
      41 -> BigInt(77),
      184 -> BigInt(7),
      185 -> BigInt(9),
      186 -> BigInt(8),
      (214 + s) -> BigInt(64),
      (215 + s) -> BigInt(2),
      (216 + s) -> BigInt(60),
      (217 + s) -> BigInt(32),
      (218 + s) -> BigInt(120),
      (219 + s) -> BigInt(100),
      (236 + s) -> BigInt(8),
      496 -> BigInt(7),
      497 -> BigInt(8),
      498 -> BigInt(9),
      499 -> BigInt(3),
      500 -> BigInt(0x6000),
      504 -> BigInt(3),
      505 -> BigInt(0x7000),
      509 -> BigInt(0xa000),
      510 -> BigInt(0xa200)
    ).foreach { case (i, v) => w(i) = v }
    w
  }
  def source(): Array[BigInt] = {
    val w = Array.fill[BigInt](128)(0)
    Seq(
      BigInt("47565301", 16),
      BigInt(512),
      BigInt(1),
      BigInt(16),
      BigInt(16),
      BigInt(16),
      BigInt(64),
      BigInt(32),
      BigInt(16),
      BigInt(8),
      BigInt(2)
    ).zipWithIndex.foreach { case (v, i) => w(i) = v }
    Seq(
      16 -> BigInt(7),
      17 -> BigInt(8),
      18 -> BigInt(9),
      20 -> BigInt(2),
      21 -> BigInt(100),
      22 -> BigInt(50),
      24 -> BigInt(0xc000),
      25 -> BigInt(0xc800),
      27 -> BigInt(0x5000),
      28 -> BigInt(0x5100),
      30 -> BigInt(0x5010),
      32 -> BigInt(1),
      34 -> BigInt(10),
      36 -> BigInt(5),
      40 -> BigInt(2),
      41 -> BigInt(64),
      42 -> BigInt(32),
      43 -> BigInt(60),
      44 -> BigInt(120),
      45 -> BigInt(2)
    ).foreach { case (i, v) => w(i) = v }
    for (i <- 0 until 64) w(64 + i) = 1 | (i << 2); w
  }
  def draw(): Array[BigInt] = {
    val w = header(BigInt("474e4401", 16), 2048, 1)
    val bindings = Seq(
      Seq(182, 183, 185, 186, 187, 189),
      Seq(190, 192, 194, 195, 196, 198),
      Seq(199, 200, 202, 203, 204, 206),
      Seq(207, 208, 210, 211, 212, 214),
      Seq(215, 216, 218, 219, 220, 222),
      Seq(223, 224, 226, 227, 228, 230),
      Seq(231, 232, 234, 235, 236, 238),
      Seq(239, 240, 242, 243, 244, 246),
      Seq(247, 248, 250, 251, 252, 254),
      Seq(255, 256, 258, 259, 260, 262),
      Seq(263, 264, 266, 267, 268, 270),
      Seq(271, 272, 274, 275, 276, 278),
      Seq(279, 280, 282, 283, 284, 286),
      Seq(287, 288, 290, 291, 292, 294),
      Seq(295, 296, 298, 299, 300, 302),
      Seq(303, 304, 306, 307, 308, 310)
    )
    w(29) = 3; w(30) = 19; w(31) = 1; w(35) = 16
    for (i <- 0 until 16) {
      val a = 36 + 9 * i; w(a) = i; w(a + 1) = 4; for (c <- 0 until 4) w(a + 2 + c) = 0x1000 + i * 4 + c
      w(a + 6) = 1; w(a + 8) = i * 4; val b = bindings(i); val base = BigInt(0x80000 + i * 0x2000)
      w(b(0)) = base; wide(w, b(1), base + 4096); w(b(2)) = base + 64; w(b(3)) = i % 4
      wide(w, b(4), base + 1088); w(b(5)) = 32 + i * 4
    }
    w(180) = 17; w(181) = 0xfffffff9L; w(311) = 20; w(312) = 9; w(313) = 11
    w(314) = 7; w(315) = 9; w(316) = 8; w(317) = 2; w(318) = 0xa4000; wide(w, 320, 0xa5000)
    w(322) = 0xa4040; wide(w, 323, 0xa4140); w(325) = 5; w(327) = 2
    w(336) = 0x20000; wide(w, 337, 0x20000 + 136 * 128 * 4); w(339) = 0x20000; wide(w, 340, 0x20000 + 136 * 128 * 4)
    w(342) = 0x5010; wide(w, 343, BigInt("ffffffffffffffff", 16)); w(345) = 16; w(346) = 2
    w(506) = 1; w(507) = 0x6000; w
  }
}
