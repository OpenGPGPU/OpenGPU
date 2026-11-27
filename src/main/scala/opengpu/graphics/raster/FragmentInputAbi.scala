package opengpu.graphics

import chisel3._

/** Versions 3/4 enable centroid qualifiers without changing the corresponding version 1/2 shader SoA offsets. Version 4
  * retains pointCoord and kind flags.
  */
object FragmentInputAbi {
  val center = 1; val pointCoord = 2; val centroid = 3; val centroidPointCoord = 4
  // Internal geometry input; public capability and descriptor versions remain 1–4.
  val geometry = 5
  // Opt-in sample-plane inputs; geometrySample is internal like geometry.
  val sample = 6; val geometrySample = 7
  // Separate opt-in primitive metadata, without a geometry/layer sideband.
  // These internal ABIs are not advertised by the frozen GSS1 contract.
  val primitive = 8; val primitiveSample = 9
  def hasSample(version: UInt): Bool =
    version === sample.U || version === geometrySample.U || version === primitiveSample.U
  def hasSample(version: Int): Boolean = version == sample || version == geometrySample || version == primitiveSample
  def supported(version: UInt, geometryInput: Boolean = false, sampleShading: Boolean = false): Bool =
    supported(version, geometryInput, sampleShading, false)
  def supported(version: UInt, geometryInput: Boolean, sampleShading: Boolean, primitiveIdInput: Boolean): Bool =
    legal(version) || (geometryInput.B && version === geometry.U) ||
      (sampleShading.B && (version === sample.U || (geometryInput.B && version === geometrySample.U))) ||
      (primitiveIdInput.B && (version === primitive.U || (sampleShading.B && version === primitiveSample.U)))
  def hasGeometry(version: UInt): Bool = version === geometry.U || version === geometrySample.U
  def hasGeometry(version: Int): Boolean = version == geometry || version == geometrySample
  def hasPrimitiveId(version: UInt): Bool =
    hasGeometry(version) || version === primitive.U || version === primitiveSample.U
  def hasPrimitiveId(version: Int): Boolean = hasGeometry(version) || version == primitive || version == primitiveSample
  def metadataWords(version: UInt): UInt = Mux(hasGeometry(version), 2.U, Mux(hasPrimitiveId(version), 1.U, 0.U))
  def metadataWords(version: Int): Int = if (hasGeometry(version)) 2 else if (hasPrimitiveId(version)) 1 else 0

  /** Replay must never manufacture the GS layer/primitive provenance for an ordinary primitive. The common
    * WindowPrimitive ID suffices only for8/9.
    */
  def replaySupported(
      version: UInt,
      geometryValid: Bool,
      geometryInput: Boolean = false,
      sampleShading: Boolean = false,
      primitiveIdInput: Boolean = false
  ): Bool =
    supported(version, geometryInput, sampleShading, primitiveIdInput) &&
      (!hasGeometry(version) || geometryValid)
  def legal(version: UInt): Bool = version >= center.U && version <= centroidPointCoord.U
  def hasPointCoord(version: UInt): Bool =
    version === pointCoord.U || version === centroidPointCoord.U || hasPrimitiveId(version) || hasSample(version)
  def hasCentroid(version: UInt): Bool =
    version === centroid.U || version === centroidPointCoord.U || hasPrimitiveId(version) || hasSample(version)
  def hasPointCoord(version: Int): Boolean =
    version == pointCoord || version == centroidPointCoord || hasPrimitiveId(version) || hasSample(version)
  def header(prefix: String): String = Seq(
    "CENTER" -> center,
    "POINT_COORD" -> pointCoord,
    "CENTROID" -> centroid,
    "CENTROID_POINT_COORD" -> centroidPointCoord,
    "SAMPLE" -> sample
  ).map { case (n, v) => s"#define ${prefix}FRAGMENT_VERSION_$n ${v}u\n" }.mkString
}
