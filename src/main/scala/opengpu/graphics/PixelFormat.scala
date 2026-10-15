package opengpu.graphics

import chisel3._
import chisel3.util._

/** Memory byte order, independent of the shader's canonical 0xRRGGBBAA word.
  * Zero preserves the original ABI. RGBA/BGRA/RGBX/BGRX name bytes at
  * increasing addresses, matching Gallium on the little-endian host.
  */
object PixelFormat {
  val width = 3
  val native = 0
  val rgba = 1
  val bgra = 2
  val rgbx = 3
  val bgrx = 4

  def decode(word: UInt, format: UInt): UInt = MuxLookup(format, word)(Seq(
    rgba.U -> Cat(word(7, 0), word(15, 8), word(23, 16), word(31, 24)),
    bgra.U -> Cat(word(23, 16), word(15, 8), word(7, 0), word(31, 24)),
    rgbx.U -> Cat(word(7, 0), word(15, 8), word(23, 16), 255.U(8.W)),
    bgrx.U -> Cat(word(23, 16), word(15, 8), word(7, 0), 255.U(8.W))))

  def encode(word: UInt, format: UInt): UInt = MuxLookup(format, word)(Seq(
    rgba.U -> Cat(word(7, 0), word(15, 8), word(23, 16), word(31, 24)),
    bgra.U -> Cat(word(7, 0), word(31, 24), word(23, 16), word(15, 8)),
    rgbx.U -> Cat(255.U(8.W), word(15, 8), word(23, 16), word(31, 24)),
    bgrx.U -> Cat(255.U(8.W), word(31, 24), word(23, 16), word(15, 8))))
}
