package opengpu.core.memory

import chisel3._
import chisel3.util._

private[memory] object L2PriorityEncoder {
  def apply(bits: UInt): UInt = {
    val width = bits.getWidth
    if (width <= 8) PriorityEncoder(bits)
    else {
      val indexWidth = log2Ceil(width)
      val lowWidth = 1 << (indexWidth - 1)
      val low = bits(lowWidth - 1, 0)
      val high = bits(width - 1, lowWidth)
      val chooseLow = low.orR
      Cat(!chooseLow, Mux(chooseLow,
        apply(low).pad(indexWidth - 1),
        apply(high).pad(indexWidth - 1)))
    }
  }
}
