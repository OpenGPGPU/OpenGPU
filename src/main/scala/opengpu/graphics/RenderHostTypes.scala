package opengpu.graphics

import chisel3._
import opengpu.dma.{CopyEngine, FillEngine, StridedCopyEngine}

/** Render status, legacy configuration shadows, DMA and scanout registers.
  * Unified descriptors are the sole source of render launch configuration. */
object RenderHostRegs {
  /** First invalid byte offset (exclusive end of the register file). */
  val END               = 0x148
  val ID                = 0x00
  val CONTROL           = 0x04
  val STATUS            = 0x08
  val IRQ               = 0x0C
  val CMD_BASE          = 0x10
  val CMD_COUNT         = 0x14
  val COLOR_BASE        = 0x18
  val DEPTH_BASE        = 0x1C
  val STRIDE            = 0x20
  val DEPTH_TEST_ENABLE = 0x24
  val DEPTH_FUNC        = 0x28
  val DEPTH_WRITE_ENABLE= 0x2C
  val CULL_MODE         = 0x30
  val TEX_BASE          = 0x34
  val TEX_WIDTH         = 0x38
  val TEX_HEIGHT        = 0x3C
  /** bit0: CLAMP; bits[5:2]: max mip level; bit8: sampling enable. */
  val TEX_CONFIG        = 0x40
  /** Display registers are independent of the execution COLOR_BASE/STRIDE. */
  val SCANOUT_BASE      = 0x44
  val SCANOUT_STRIDE    = 0x48
  val SCANOUT_WIDTH     = 0x4C
  val SCANOUT_HEIGHT    = 0x50
  /** 0: packed RGBA8888 (0xRRGGBBAA), 1: DRM XRGB8888. */
  val SCANOUT_FORMAT    = 0x54
  /** bit0: scanout enable; bit1: hardware vblank IRQ enable. */
  val SCANOUT_CONTROL   = 0x58
  /** bit0: active (enable && non-zero base/stride/size). */
  val SCANOUT_STATUS    = 0x5C
  /** bit0: fragment core; bit2: vertex core;
    * bits 15:8: fragment batch capacity; bit21: hardware vblank. */
  val CAPABILITIES      = 0x60
  /** AXI clocks between vblank IRQs while scanout is active and CONTROL.bit1
    * is set. Zero disables the counter. */
  val SCANOUT_PERIOD    = 0x64
  /** Hardware clear (FillEngine): destination base, byte count (multiple of
    * the 64-byte line) and the 32-bit fill pattern. */
  val CLEAR_BASE        = 0x88
  val CLEAR_BYTES       = 0x8C
  val CLEAR_PATTERN     = 0x90
  /** Write 1 to start one clear of the programmed range. */
  val CLEAR_START       = 0x94
  /** Hardware colour blit (CopyEngine): 64-byte aligned, non-overlapping
    * source/destination regions and a byte count that is a multiple of 64. */
  val BLIT_SRC_BASE     = 0x98
  val BLIT_DST_BASE     = 0x9C
  val BLIT_BYTES        = 0xA0
  /** Write 1 to start one blit of the programmed range. */
  val BLIT_START        = 0xA4
  /** Hardware two-dimensional copy (StridedCopyEngine). */
  val STRIDED_SRC_BASE  = 0xA8
  val STRIDED_DST_BASE  = 0xAC
  val STRIDED_WIDTH     = 0xB0
  val STRIDED_HEIGHT    = 0xB4
  val STRIDED_SRC_STRIDE= 0xB8
  val STRIDED_DST_STRIDE= 0xBC
  val STRIDED_START     = 0xC0
  /** Legacy sample-mode shadow; unified renders use descriptor word 9. */
  val MSAA_CONFIG       = 0x134
  /** bit0 stencil-test enable; bits[6:4] func; bits[9:7] fail op;
    * bits[12:10] z-fail op; bits[15:13] z-pass op. */
  val STENCIL_CONFIG    = 0x13c
  /** bits[7:0] ref, bits[15:8] read mask, bits[23:16] write mask. */
  val STENCIL_REF_MASKS = 0x140
  /** bit0 present; bits[7:4] src factor; bits[11:8] dst factor;
    * bits[14:12] equation.  Present overrides legacy source-over. */
  val BLEND_CONFIG      = 0x144
}

/** A host memory-mapped register access (read or write of one 32-bit word). */
class RenderHostRegRequest(val addressWidth: Int = 10) extends Bundle {
  /** Byte address within the register file. */
  val addr = UInt(addressWidth.W)
  val data = UInt(32.W)
  /** Byte lane write-enable; for reads this is ignored. */
  val strb = UInt(4.W)
  val isWrite = Bool()
}

/** Response to a register read. */
class RenderHostRegResponse extends Bundle {
  val data = UInt(32.W)
  /** Combinational disposition of the current request. */
  val ok = Bool()
}
