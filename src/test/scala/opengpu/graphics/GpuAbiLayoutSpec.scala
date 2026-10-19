package opengpu.graphics

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import opengpu.command.{GpuCommandOpcode, RenderStatus}
import org.scalatest.flatspec.AnyFlatSpec

/** Synchronization checks between the hardware/Scala definitions and the
  * user-visible ABI in `driver/gpu_abi.h`.
  *
  * The header is the contract the Linux driver and userspace compile against;
  * the register map, capability word, record layouts and encoding shifts are
  * duplicated in RTL.  This spec parses the header and compares every
  * duplicated constant, so a change to either side fails the build instead of
  * silently desynchronizing the UAPI from the hardware.
  */
class GpuAbiLayoutSpec extends AnyFlatSpec {
  behavior of "gpu_abi.h"

  private val headerPath = Paths.get("driver", "gpu_abi.h")

  private val defines: Map[String, Long] = {
    assert(Files.exists(headerPath), s"ABI header not found at $headerPath")
    val text = new String(Files.readAllBytes(headerPath), StandardCharsets.UTF_8)
    CHeader.parse(text)
  }

  private def check(name: String, expected: Long): Unit = {
    assert(defines.contains(name), s"ABI header does not define $name")
    assert(defines(name) == expected,
      s"$name=${defines(name)} does not match the hardware value $expected")
  }

  private def checkAll(pairs: Seq[(String, Long)]): Unit =
    for ((name, expected) <- pairs) check(name, expected)

  it should "match render completion status encodings" in {
    check("GPU_RENDER_STATUS_SUCCESS", RenderStatus.success.litValue.toLong)
    check("GPU_RENDER_STATUS_MEMORY_FAULT", RenderStatus.memoryFault.litValue.toLong)
    check("GPU_RENDER_STATUS_INVALID_SAMPLE_MODE", RenderStatus.invalidSampleMode.litValue.toLong)
  }

  it should "match the bounded tile descriptor and its precise completion statuses" in {
    checkAll(Seq(
      "GPU_TILE_JOB_MAGIC" -> TileJobMemoryLayout.magic.toLong,
      "GPU_TILE_JOB_TYPED_MAGIC" -> TileJobMemoryLayout.typedMagic.toLong,
      "GPU_TILE_COMMAND_PAYLOAD_WORDS" -> TileCommandMemoryLayout.payloadWords.toLong,
      "GPU_TILE_COMMAND_V1_BYTES" -> TileCommandMemoryLayout.legacyBytes.toLong,
      "GPU_TILE_COMMAND_V2_BYTES" -> TileCommandMemoryLayout.typedBytes.toLong,
      "GPU_TILE_COMMAND_DRAW" -> TileCommandMemoryLayout.draw.toLong,
      "GPU_TILE_COMMAND_CLEAR" -> TileCommandMemoryLayout.clear.toLong,
      "GPU_TILE_JOB_WORDS" -> TileJobMemoryLayout.words.toLong,
      "GPU_TILE_JOB_BYTES" -> TileJobMemoryLayout.bytes.toLong,
      "GPU_TILE_RESULT_SUCCESS" -> TilePassStatus.success.toLong,
      "GPU_TILE_RESULT_INVALID" -> TilePassStatus.invalidDescriptor.toLong,
      "GPU_TILE_RESULT_CANCELLED" -> TilePassStatus.cancelled.toLong,
      "GPU_TILE_RESULT_ATTACHMENT_FAULT" -> TilePassStatus.memoryFault.toLong,
      "GPU_TILE_RESULT_EXECUTION_FAULT" -> TilePassStatus.executionFault.toLong,
      "GPU_TILE_RESULT_COMMAND_FAULT" -> TilePassStatus.commandFault.toLong,
      "GPU_TILE_RESULT_TEXTURE_FAULT" -> TilePassStatus.textureFault.toLong))
  }

  it should "match the explicit GBJ1 host resource map and distinct status encodings" in {
    checkAll(Seq(
      "GPU_REG_BIN_CAPABILITY_PTR" -> TileBinHostRegs.DISCOVERY.toLong,
      "GPU_REG_BIN_CAPABILITIES" -> TileBinHostRegs.CAPABILITIES.toLong,
      "GPU_REG_BIN_CAPABILITIES_END" -> TileBinHostRegs.END.toLong,
      "GPU_BIN_CAP_MAGIC" -> 0x47424301L, "GPU_BIN_CAP_WORDS" -> 8L,
      "GPU_BIN_CAP_BANKED_SAMPLES" -> (1L<<TileBinCapabilityFlags.BankedSamples),
      "GPU_BIN_CAP_LOCAL_PAYLOADS" -> (1L<<TileBinCapabilityFlags.LocalPayloads),
      "GPU_UCMD_OP_BIN_RENDER" -> GpuCommandOpcode.tileRender.litValue.toLong,
      "GPU_BIN_JOB_MAGIC" -> TileBinJobMemoryLayout.magic.toLong,
      "GPU_BIN_JOB_WORDS" -> TileBinJobMemoryLayout.words.toLong,
      "GPU_BIN_JOB_BYTES" -> TileBinJobMemoryLayout.bytes.toLong,
      "GPU_BIN_APPEND_BYTES" -> TileBinJobMemoryLayout.appendBytes.toLong,
      "GPU_BIN_RESULT_SUCCESS" -> PrimitiveBinStatus.success.toLong,
      "GPU_BIN_RESULT_INVALID" -> PrimitiveBinStatus.invalidDescriptor.toLong,
      "GPU_BIN_RESULT_CANCELLED" -> PrimitiveBinStatus.cancelled.toLong,
      "GPU_BIN_RESULT_EXHAUSTED" -> PrimitiveBinStatus.exhausted.toLong,
      "GPU_BIN_RESULT_MEMORY_FAULT" -> PrimitiveBinStatus.memoryFault.toLong,
      "GPU_BIN_RESULT_INVALID_ORDER" -> PrimitiveBinStatus.invalidOrder.toLong,
      "GPU_BIN_RESULT_INVALID_PAGE" -> PrimitiveBinReadStatus.invalidPage.toLong,
      "GPU_BIN_RESULT_CLOSED" -> PrimitiveBinStatus.closed.toLong,
      "GPU_BIN_RESULT_INVALID_HANDLE" -> ResolvedTileBinStatus.invalidHandle.toLong,
      "GPU_BIN_RESULT_ENCODING" -> TileBinRenderStatus.invalidEncoding.toLong,
      "GPU_BIN_RESULT_ATTACHMENT_FAULT" -> TileBinRenderStatus.attachmentFault.toLong,
      "GPU_BIN_RESULT_SHADER_FAULT" -> TileBinRenderStatus.shaderFault.toLong,
      "GPU_BIN_RESULT_TEXTURE_FAULT" -> TileBinRenderStatus.textureFault.toLong))
  }

  it should "match physical publication range failure statuses" in {
    checkAll(Seq(
      "GPU_INVALIDATE_RESULT_SUCCESS" -> opengpu.command.InvalidateStatus.success.litValue.toLong,
      "GPU_INVALIDATE_RESULT_ADDRESS_OVERFLOW" -> opengpu.command.InvalidateStatus.addressOverflow.litValue.toLong))
  }

  it should "match the render-host register map" in {
    checkAll(Seq(
      "GPU_REG_ID" -> RenderHostRegs.ID,
      "GPU_REG_CONTROL" -> RenderHostRegs.CONTROL,
      "GPU_REG_STATUS" -> RenderHostRegs.STATUS,
      "GPU_REG_IRQ" -> RenderHostRegs.IRQ,
      "GPU_REG_CMD_BASE" -> RenderHostRegs.CMD_BASE,
      "GPU_REG_CMD_COUNT" -> RenderHostRegs.CMD_COUNT,
      "GPU_REG_COLOR_BASE" -> RenderHostRegs.COLOR_BASE,
      "GPU_REG_DEPTH_BASE" -> RenderHostRegs.DEPTH_BASE,
      "GPU_REG_STRIDE" -> RenderHostRegs.STRIDE,
      "GPU_REG_DEPTH_TEST" -> RenderHostRegs.DEPTH_TEST_ENABLE,
      "GPU_REG_DEPTH_FUNC" -> RenderHostRegs.DEPTH_FUNC,
      "GPU_REG_DEPTH_WRITE" -> RenderHostRegs.DEPTH_WRITE_ENABLE,
      "GPU_REG_CULL_MODE" -> RenderHostRegs.CULL_MODE,
      "GPU_REG_TEX_BASE" -> RenderHostRegs.TEX_BASE,
      "GPU_REG_TEX_WIDTH" -> RenderHostRegs.TEX_WIDTH,
      "GPU_REG_TEX_HEIGHT" -> RenderHostRegs.TEX_HEIGHT,
      "GPU_REG_TEX_CONFIG" -> RenderHostRegs.TEX_CONFIG,
      "GPU_REG_SCANOUT_BASE" -> RenderHostRegs.SCANOUT_BASE,
      "GPU_REG_SCANOUT_STRIDE" -> RenderHostRegs.SCANOUT_STRIDE,
      "GPU_REG_SCANOUT_WIDTH" -> RenderHostRegs.SCANOUT_WIDTH,
      "GPU_REG_SCANOUT_HEIGHT" -> RenderHostRegs.SCANOUT_HEIGHT,
      "GPU_REG_SCANOUT_FORMAT" -> RenderHostRegs.SCANOUT_FORMAT,
      "GPU_REG_SCANOUT_CONTROL" -> RenderHostRegs.SCANOUT_CONTROL,
      "GPU_REG_SCANOUT_STATUS" -> RenderHostRegs.SCANOUT_STATUS,
      "GPU_REG_SCANOUT_PERIOD" -> RenderHostRegs.SCANOUT_PERIOD,
      "GPU_REG_CAPABILITIES" -> RenderHostRegs.CAPABILITIES,
      "GPU_REG_CLEAR_BASE" -> RenderHostRegs.CLEAR_BASE,
      "GPU_REG_CLEAR_BYTES" -> RenderHostRegs.CLEAR_BYTES,
      "GPU_REG_CLEAR_PATTERN" -> RenderHostRegs.CLEAR_PATTERN,
      "GPU_REG_CLEAR_START" -> RenderHostRegs.CLEAR_START,
      "GPU_REG_BLIT_SRC_BASE" -> RenderHostRegs.BLIT_SRC_BASE,
      "GPU_REG_BLIT_DST_BASE" -> RenderHostRegs.BLIT_DST_BASE,
      "GPU_REG_BLIT_BYTES" -> RenderHostRegs.BLIT_BYTES,
      "GPU_REG_BLIT_START" -> RenderHostRegs.BLIT_START,
      "GPU_REG_STRIDED_SRC_BASE" -> RenderHostRegs.STRIDED_SRC_BASE,
      "GPU_REG_STRIDED_DST_BASE" -> RenderHostRegs.STRIDED_DST_BASE,
      "GPU_REG_STRIDED_WIDTH" -> RenderHostRegs.STRIDED_WIDTH,
      "GPU_REG_STRIDED_HEIGHT" -> RenderHostRegs.STRIDED_HEIGHT,
      "GPU_REG_STRIDED_SRC_STRIDE" -> RenderHostRegs.STRIDED_SRC_STRIDE,
      "GPU_REG_STRIDED_DST_STRIDE" -> RenderHostRegs.STRIDED_DST_STRIDE,
      "GPU_REG_STRIDED_START" -> RenderHostRegs.STRIDED_START,
      "GPU_REG_MSAA_CONFIG" -> RenderHostRegs.MSAA_CONFIG,
      "GPU_REG_STENCIL_CONFIG" -> RenderHostRegs.STENCIL_CONFIG,
      "GPU_REG_STENCIL_REF_MASKS" -> RenderHostRegs.STENCIL_REF_MASKS,
      "GPU_REG_BLEND_CONFIG" -> RenderHostRegs.BLEND_CONFIG
    ).map { case (n, v) => (n, v.toLong) })
  }

  it should "match the unified-command register bank" in {
    checkAll(Seq(
      "GPU_REG_UCMD_ID" -> GpuCommandMmioRegs.COMMAND_ID,
      "GPU_REG_UCMD_OPCODE" -> GpuCommandMmioRegs.OPCODE,
      "GPU_REG_UCMD_KERNEL_PC" -> GpuCommandMmioRegs.KERNEL_PC,
      "GPU_REG_UCMD_KERNARG" -> GpuCommandMmioRegs.KERNARG,
      "GPU_REG_UCMD_GRID_X" -> GpuCommandMmioRegs.GRID_X,
      "GPU_REG_UCMD_GRID_Y" -> GpuCommandMmioRegs.GRID_Y,
      "GPU_REG_UCMD_GRID_Z" -> GpuCommandMmioRegs.GRID_Z,
      "GPU_REG_UCMD_LOCAL_X" -> GpuCommandMmioRegs.LOCAL_X,
      "GPU_REG_UCMD_LOCAL_Y" -> GpuCommandMmioRegs.LOCAL_Y,
      "GPU_REG_UCMD_LOCAL_Z" -> GpuCommandMmioRegs.LOCAL_Z,
      "GPU_REG_UCMD_FLAGS" -> GpuCommandMmioRegs.FLAGS,
      "GPU_REG_UCMD_DMA_DEPENDENCY" -> GpuCommandMmioRegs.DMA_DEPENDENCY,
      "GPU_REG_UCMD_SOURCE" -> GpuCommandMmioRegs.SOURCE,
      "GPU_REG_UCMD_DESTINATION" -> GpuCommandMmioRegs.DESTINATION,
      "GPU_REG_UCMD_BYTES" -> GpuCommandMmioRegs.BYTES,
      "GPU_REG_UCMD_PATTERN" -> GpuCommandMmioRegs.PATTERN,
      "GPU_REG_UCMD_WIDTH" -> GpuCommandMmioRegs.WIDTH,
      "GPU_REG_UCMD_HEIGHT" -> GpuCommandMmioRegs.HEIGHT,
      "GPU_REG_UCMD_SOURCE_STRIDE" -> GpuCommandMmioRegs.SOURCE_STRIDE,
      "GPU_REG_UCMD_DEST_STRIDE" -> GpuCommandMmioRegs.DESTINATION_STRIDE,
      "GPU_REG_UCMD_WAIT_EVENT" -> GpuCommandMmioRegs.WAIT_EVENT,
      "GPU_REG_UCMD_SIGNAL_EVENT" -> GpuCommandMmioRegs.SIGNAL_EVENT,
      "GPU_REG_UCMD_SUBMIT" -> GpuCommandMmioRegs.SUBMIT,
      "GPU_REG_UCMD_STATUS" -> GpuCommandMmioRegs.STATUS,
      "GPU_REG_UCMD_COMPLETION" -> GpuCommandMmioRegs.COMPLETION,
      "GPU_REG_UCMD_COMPLETION_BYTES_LO" -> GpuCommandMmioRegs.COMPLETION_BYTES_LO,
      "GPU_REG_UCMD_COMPLETION_BYTES_HI" -> GpuCommandMmioRegs.COMPLETION_BYTES_HI,
      "GPU_REG_UCMD_COMPLETION_POP" -> GpuCommandMmioRegs.COMPLETION_POP,
      "GPU_REG_UCMD_RESET" -> GpuCommandMmioRegs.RESET,
      "GPU_REG_UCMD_SAMPLE_MODE" -> GpuCommandMmioRegs.SAMPLE_MODE,
      "GPU_REG_UCMD_VECTOR_SATP" -> GpuCommandMmioRegs.VECTOR_SATP,
      "GPU_REG_UCMD_INSTRUCTION_SATP" -> GpuCommandMmioRegs.INSTRUCTION_SATP,
      "GPU_REG_UCMD_TLB_FLUSH" -> GpuCommandMmioRegs.TLB_FLUSH,
      "GPU_REG_SHADER_CONFIG" -> GpuCommandMmioRegs.SHADER_CONFIG
    ).map { case (n, v) => (n, v.toLong) })
  }

  it should "match the scoped TLB-flush and satp field encoding" in {
    checkAll(Seq[(String, Long)](
      "GPU_TLB_FLUSH_FULL" -> GpuCommandMmioRegs.TLB_FLUSH_FULL.toLong,
      "GPU_TLB_FLUSH_ASID" -> GpuCommandMmioRegs.TLB_FLUSH_ASID.toLong,
      "GPU_TLB_FLUSH_ASID_SHIFT" ->
        GpuCommandMmioRegs.TLB_FLUSH_ASID_SHIFT.toLong,
      "GPU_TLB_FLUSH_ASID_MASK" ->
        GpuCommandMmioRegs.TLB_FLUSH_ASID_MASK.toLong,
      "GPU_TLB_FLUSH_VPN" -> GpuCommandMmioRegs.TLB_FLUSH_VPN.toLong,
      "GPU_TLB_FLUSH_VPN_SHIFT" ->
        GpuCommandMmioRegs.TLB_FLUSH_VPN_SHIFT.toLong,
      "GPU_TLB_FLUSH_VPN_MASK" ->
        GpuCommandMmioRegs.TLB_FLUSH_VPN_MASK.toLong,
      "GPU_SATP_ENABLE" -> GpuCommandMmioRegs.SATP_ENABLE.toLong,
      "GPU_SATP_ASID_SHIFT" -> GpuCommandMmioRegs.SATP_ASID_SHIFT.toLong,
      "GPU_SATP_ASID_MASK" -> GpuCommandMmioRegs.SATP_ASID_MASK.toLong
    ))
  }

  it should "match the capability-word bit assignments" in {
    checkAll(Seq(
      "GPU_CAP_FRAGMENT_CORE" -> (1L << GpuCapabilities.FragmentCore),
      "GPU_CAP_VERTEX_CORE" -> (1L << GpuCapabilities.VertexCore),
      "GPU_CAP_CLEAR_ENGINE" -> (1L << GpuCapabilities.ClearEngine),
      "GPU_CAP_BLIT_ENGINE" -> (1L << GpuCapabilities.BlitEngine),
      "GPU_CAP_STRIDED_ENGINE" -> (1L << GpuCapabilities.StridedEngine),
      "GPU_CAP_UNIFIED_COMMANDS" -> (1L << GpuCapabilities.UnifiedCommands),
      "GPU_CAP_UNIFIED_RESET" -> (1L << GpuCapabilities.UnifiedReset),
      "GPU_CAP_PERSISTENT_DEPTH" -> (1L << GpuCapabilities.PersistentDepth),
      "GPU_CAP_UNIFIED_RENDER" -> (1L << GpuCapabilities.UnifiedRender),
      "GPU_CAP_PROTECTED_VM_STATE" -> (1L << GpuCapabilities.ProtectedVmState),
      "GPU_CAP_PERSISTENT_TILE_JOBS" -> (1L << GpuCapabilities.PersistentTileJobs),
      "GPU_CAP_TYPED_TILE_COMMANDS" -> (1L << GpuCapabilities.TypedTileCommands),
      "GPU_CAP_HW_VBLANK" -> (1L << GpuCapabilities.HwVblank),
      "GPU_CAP_COMPUTE_SCALAR_FPU" -> (1L << GpuCapabilities.ComputeScalarFpu),
      "GPU_CAP_RUNTIME_SHADER_ABI" -> (1L << GpuCapabilities.RuntimeShaderAbi),
      "GPU_CAP_SHADER_CONFIG_INFO" -> (1L << GpuCapabilities.ShaderConfigInfo),
      "GPU_CAP_MSAA" -> (1L << GpuCapabilities.Msaa),
      "GPU_CAP_MSAA_MAX_MODE_SHIFT" -> GpuCapabilities.MsaaMaxModeShift.toLong,
      "GPU_CAP_MSAA_MAX_MODE_MASK" ->
        (0x3L << GpuCapabilities.MsaaMaxModeShift),
      "GPU_CAP_FRAGMENT_BATCH_SHIFT" ->
        GpuCapabilities.FragmentBatchShift.toLong,
      "GPU_CAP_FRAGMENT_BATCH_MASK" ->
        (0xffL << GpuCapabilities.FragmentBatchShift)
    ))
  }

  it should "match shader resource fields across hardware and the DRM UAPI" in {
    val fields = Seq(
      "LANES_SHIFT" -> ShaderResourceConfig.LanesShift,
      "WARPS_SHIFT" -> ShaderResourceConfig.WarpsShift,
      "BANKS_SHIFT" -> ShaderResourceConfig.BanksShift,
      "CUS_SHIFT" -> ShaderResourceConfig.ComputeUnitsShift,
      "FIELD_MASK" -> ShaderResourceConfig.FieldMask)
    val uapi = CHeader.parse(new String(Files.readAllBytes(
      Paths.get("driver", "opengpu_drm.h")), StandardCharsets.UTF_8))
    fields.foreach { case (name, value) =>
      check("GPU_SHADER_CONFIG_" + name, value.toLong)
      assert(uapi("OPENGPU_SHADER_CONFIG_" + name) == value.toLong)
    }
    assert(uapi("OPENGPU_PARAM_SHADER_CONFIG") == 1)
    assert(uapi("OPENGPU_CAP_SHADER_CONFIG_INFO") == (1L << GpuCapabilities.ShaderConfigInfo))
  }

  it should "match the fragment output-control ABI" in {
    checkAll(Seq(
      "GPU_FRAGMENT_ABI_LEGACY" -> FragmentShaderAbi.Legacy.toLong,
      "GPU_FRAGMENT_ABI_MULTISAMPLE" -> FragmentShaderAbi.Multisample.toLong,
      "GPU_FRAGMENT_CONTROL_EMIT" -> (1L << FragmentShaderAbi.EmitBit),
      "GPU_FRAGMENT_CONTROL_DEPTH_OVERRIDE" ->
        (1L << FragmentShaderAbi.DepthOverrideBit),
      "GPU_FRAGMENT_CONTROL_VALID_MASK" -> 0x3L
    ))
  }

  it should "match the unified-command opcodes" in {
    checkAll(Seq(
      "GPU_UCMD_OP_KERNEL" -> GpuCommandOpcode.kernel.litValue.toLong,
      "GPU_UCMD_OP_COPY" -> GpuCommandOpcode.copy.litValue.toLong,
      "GPU_UCMD_OP_FILL" -> GpuCommandOpcode.fill.litValue.toLong,
      "GPU_UCMD_OP_STRIDED_COPY" -> GpuCommandOpcode.stridedCopy.litValue.toLong,
      "GPU_UCMD_OP_RESOLVE" -> GpuCommandOpcode.resolve.litValue.toLong,
      "GPU_UCMD_OP_INVALIDATE" -> GpuCommandOpcode.invalidate.litValue.toLong,
      "GPU_UCMD_OP_RENDER" -> GpuCommandOpcode.render.litValue.toLong,
      "GPU_UCMD_OP_TILE_RENDER" -> GpuCommandOpcode.tileRender.litValue.toLong
    ))
  }

  it should "match the draw, vertex and job record sizes" in {
    checkAll(Seq(
      "GPU_DRAW_WORDS" -> 40L,
      "GPU_VERT_DRAW_WORDS" -> 40L,
      "GPU_VERTEX_STRIDE_BYTES" -> 32L,
      "GPU_JOB_WORDS" -> 16L
    ))
  }

  it should "match the blend and stencil encoding shifts" in {
    checkAll(Seq(
      "GPU_BLEND_PRESENT" -> 1L,
      "GPU_BLEND_SRC_SHIFT" -> 4L,
      "GPU_BLEND_SRC_MASK" -> (0xfL << 4),
      "GPU_BLEND_DST_SHIFT" -> 8L,
      "GPU_BLEND_DST_MASK" -> (0xfL << 8),
      "GPU_BLEND_EQ_SHIFT" -> 12L,
      "GPU_BLEND_EQ_MASK" -> (0x7L << 12),
      "GPU_STENCIL_CFG_FUNC_SHIFT" -> 0L,
      "GPU_STENCIL_CFG_FUNC_MASK" -> 0x7L,
      "GPU_STENCIL_CFG_FAIL_SHIFT" -> 3L,
      "GPU_STENCIL_CFG_FAIL_MASK" -> (0x7L << 3),
      "GPU_STENCIL_CFG_ZFAIL_SHIFT" -> 6L,
      "GPU_STENCIL_CFG_ZFAIL_MASK" -> (0x7L << 6),
      "GPU_STENCIL_CFG_ZPASS_SHIFT" -> 9L,
      "GPU_STENCIL_CFG_ZPASS_MASK" -> (0x7L << 9),
      "GPU_STENCIL_REF_SHIFT" -> 0L,
      "GPU_STENCIL_RMASK_SHIFT" -> 8L,
      "GPU_STENCIL_WMASK_SHIFT" -> 16L
    ))
  }

  it should "match the job-state stencil-test encoding" in {
    checkAll(Seq(
      "GPU_JOB_STATE_STENCIL_TEST" -> (1L << 17)
    ))
  }
}

/** Minimal object-like C-macro evaluator for `gpu_abi.h`.
  *
  * Only integer constant expressions built from hex/decimal literals,
  * parentheses, `|`, `<<`, `*`, `+`, `-` and previously defined object-like
  * macros are folded; anything else (function-like macros, string bodies,
  * forward references) is ignored.  That is sufficient for every layout
  * constant the ABI spec synchronizes.
  */
private object CHeader {
  private val Define =
    """^\s*#\s*define\s+([A-Za-z_]\w*)(\([^)]*\))?\s+(.*?)\s*$""".r
  private val Token =
    """0[xX][0-9a-fA-F]+[uUlL]*|0[bB][01]+[uUlL]*|\d+[uUlL]*|[A-Za-z_]\w*|<<|>>|\||\(|\)|\*|\+|-|&""".r

  def parse(text: String): Map[String, Long] = {
    val out = scala.collection.mutable.LinkedHashMap.empty[String, Long]
    for (line <- text.split("\n")) line match {
      case Define(name, params, body) if params == null =>
        eval(stripComments(body), out.toMap).foreach(out(name) = _)
      case _ =>
    }
    out.toMap
  }

  private def stripComments(s: String): String = {
    val noBlock = s.replaceAll("""/\*.*?\*/""", " ")
    val slash = noBlock.indexOf("//")
    (if (slash >= 0) noBlock.substring(0, slash) else noBlock).trim
  }

  private def eval(expr: String, defs: Map[String, Long]): Option[Long] =
    try {
      val tokens = Token.findAllIn(expr).toVector
      if (tokens.isEmpty) None else Some(new ExprParser(tokens, defs).parse())
    } catch { case _: Throwable => None }

  private class ExprParser(tokens: Vector[String], defs: Map[String, Long]) {
    private var pos = 0
    private def peek: Option[String] =
      if (pos < tokens.length) Some(tokens(pos)) else None
    private def eat(op: String): Boolean =
      if (peek.contains(op)) { pos += 1; true } else false

    def parse(): Long = {
      val v = parseOr()
      if (pos != tokens.length) throw new RuntimeException("trailing tokens")
      v
    }

    private def parseOr(): Long = {
      var v = parseShift()
      while (eat("|")) v |= parseShift()
      v
    }

    private def parseShift(): Long = {
      var v = parseAdd()
      while (eat("<<")) v <<= parseAdd().toInt
      v
    }

    private def parseAdd(): Long = {
      var v = parseMul()
      var go = true
      while (go) {
        if (eat("+")) v += parseMul()
        else if (eat("-")) v -= parseMul()
        else go = false
      }
      v
    }

    private def parseMul(): Long = {
      var v = parsePrimary()
      while (eat("*")) v *= parsePrimary()
      v
    }

    private def parsePrimary(): Long = {
      val t = tokens(pos); pos += 1
      if (t == "(") {
        val v = parseOr()
        if (!eat(")")) throw new RuntimeException("missing )")
        v
      } else if (t.head.isDigit) parseNumber(t)
      else defs.getOrElse(t, throw new RuntimeException(s"unknown $t"))
    }

    private def parseNumber(t: String): Long = {
      val body = t.replaceAll("""[uUlL]+$""", "")
      if (body.startsWith("0x") || body.startsWith("0X"))
        java.lang.Long.parseLong(body.substring(2), 16)
      else if (body.startsWith("0b") || body.startsWith("0B"))
        java.lang.Long.parseLong(body.substring(2), 2)
      else body.toLong
    }
  }
}
