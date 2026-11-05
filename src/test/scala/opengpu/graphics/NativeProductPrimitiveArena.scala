package opengpu.graphics

/** GPU-memory allocation for complete product geometry, independent of frame
  * storage. Mapping the larger range does not allocate its untouched pages.
  */
private[opengpu] object NativeProductPrimitiveArena {
  val recordBytes: Long =
    WindowPrimitiveRecordLayout.lineCount(new WindowPrimitiveRecord(VertexShaderStageConfig())) * 64L
  // PrimitiveClipStage owns a nine-vertex polygon and exports at most seven fans.
  val maximumClippedTriangles = 7
  private val minimumBytes = BigInt(16) << 20
  private val originalBase = BigInt(0x03000000L)
  private val expandedBase = BigInt(0x60000000L)
  private val addressLimit = BigInt(1) << 32

  def requiredBytes(inputTriangles: BigInt): BigInt = {
    require(inputTriangles >= 0, "negative product triangle count")
    inputTriangles * maximumClippedTriangles * recordBytes
  }

  def validate(inputTriangles: BigInt, base: BigInt, limit: BigInt): Unit = {
    val needed = requiredBytes(inputTriangles)
    require(base >= 0 && limit >= base && limit <= addressLimit &&
      (base & 63) == 0 && (limit & 63) == 0, "invalid product primitive arena bounds")
    require(limit - base >= needed,
      s"product primitive arena has ${limit-base} bytes; $inputTriangles input triangles " +
        s"need $needed bytes for the bounded clipping output")
  }

  def allocate(inputTriangles: BigInt): NativeProductFrameInputs.Region = {
    val bytes = requiredBytes(inputTriangles).max(minimumBytes)
    val base = if (bytes <= minimumBytes) originalBase else expandedBase
    val limit = base + bytes
    validate(inputTriangles, base, limit)
    NativeProductFrameInputs.Region("primitive-arena", base.toLong, limit.toLong)
  }
}
